package org.jeecg.modules.sso.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 MySQL/InnoDB 条件更新的并发语义。此测试有意使用独立 JDBC SQL，不直接构造
 * MyBatis-Plus Mapper；生产 {@link LogoutOutboxService} 的逐条租约时间由单元测试覆盖。
 * 必须显式设置 SSO_TEST_MYSQL_JDBC_URL；测试只创建并删除随机命名的 sso_test_claim_* 表。
 */
@EnabledIfEnvironmentVariable(named = "SSO_TEST_MYSQL_JDBC_URL", matches = ".+")
class LogoutOutboxClaimMySqlIntegrationTest {
    private static String tableName;

    @BeforeAll
    static void createTable() throws Exception {
        tableName = "sso_test_claim_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + tableName + " ("
                    + "id VARCHAR(64) NOT NULL, status INT NOT NULL, next_retry_at DATETIME(6) NOT NULL, "
                    + "claim_token VARCHAR(64), claim_until DATETIME(6), update_time DATETIME(6), "
                    + "PRIMARY KEY (id)) ENGINE=InnoDB");
        }
    }

    @AfterAll
    static void dropTable() throws Exception {
        if (tableName == null) {
            return;
        }
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + tableName);
        }
    }

    @Test
    void onlyOneInstanceCanClaimTheSameDueEvent() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        insertEvent("event-concurrent", now.minusSeconds(1L), null, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> claimAfterBarrier("event-concurrent", "worker-a", now,
                    ready, start));
            Future<Integer> second = executor.submit(() -> claimAfterBarrier("event-concurrent", "worker-b", now,
                    ready, start));
            ready.await();
            start.countDown();

            assertEquals(1, first.get().intValue() + second.get().intValue());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseCanBeTakenOverAndStaleWorkerCannotWriteBack() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        insertEvent("event-takeover", now.minusSeconds(1L), "worker-old", now.minusSeconds(1L));

        assertEquals(1, claim("event-takeover", "worker-new", now));
        assertEquals(0, persistClaimedResult("event-takeover", "worker-old", 1));
        assertEquals(1, persistClaimedResult("event-takeover", "worker-new", 1));
        assertEquals(1, readStatus("event-takeover"));
    }

    private static int claimAfterBarrier(String id, String worker, LocalDateTime now, CountDownLatch ready,
                                         CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return claim(id, worker, now);
    }

    /** 与生产 claim 谓词等价的 SQL，仅验证 InnoDB 实际的条件更新语义。 */
    private static int claim(String id, String claimToken, LocalDateTime now) throws Exception {
        String sql = "UPDATE " + tableName + " SET claim_token = ?, claim_until = ?, update_time = ? "
                + "WHERE id = ? AND status IN (0, 2) AND next_retry_at <= ? "
                + "AND (claim_until IS NULL OR claim_until < ?)";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, claimToken);
            statement.setTimestamp(2, Timestamp.valueOf(now.plusSeconds(30L)));
            statement.setTimestamp(3, Timestamp.valueOf(now));
            statement.setString(4, id);
            statement.setTimestamp(5, Timestamp.valueOf(now));
            statement.setTimestamp(6, Timestamp.valueOf(now));
            return statement.executeUpdate();
        }
    }

    /** 与生产写回的 id + claim_token 所有权条件等价。 */
    private static int persistClaimedResult(String id, String claimToken, int status) throws Exception {
        String sql = "UPDATE " + tableName + " SET status = ?, claim_token = NULL, claim_until = NULL, "
                + "update_time = ? WHERE id = ? AND claim_token = ?";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, status);
            statement.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(3, id);
            statement.setString(4, claimToken);
            return statement.executeUpdate();
        }
    }

    private static void insertEvent(String id, LocalDateTime dueAt, String claimToken, LocalDateTime claimUntil)
            throws Exception {
        String sql = "INSERT INTO " + tableName
                + " (id, status, next_retry_at, claim_token, claim_until, update_time) VALUES (?, 0, ?, ?, ?, ?)";
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setTimestamp(2, Timestamp.valueOf(dueAt));
            statement.setString(3, claimToken);
            statement.setTimestamp(4, claimUntil == null ? null : Timestamp.valueOf(claimUntil));
            statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            statement.executeUpdate();
        }
    }

    private static int readStatus(String id) throws Exception {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM " + tableName + " WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static Connection openConnection() throws Exception {
        String url = System.getenv("SSO_TEST_MYSQL_JDBC_URL");
        String user = System.getenv("SSO_TEST_MYSQL_USER");
        String password = System.getenv("SSO_TEST_MYSQL_PASSWORD");
        return user == null || user.trim().isEmpty() ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password == null ? "" : password);
    }
}
