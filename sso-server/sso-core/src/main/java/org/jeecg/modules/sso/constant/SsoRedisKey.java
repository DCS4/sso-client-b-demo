package org.jeecg.modules.sso.constant;

public final class SsoRedisKey {
    public static final String SESSION = "sso:session:";
    public static final String SESSION_CLIENTS = "sso:session-clients:";
    public static final String USER_SESSIONS = "sso:user-sessions:";
    /** 某 subject 已撤销但通知尚未完整落库的 SID 索引，供 SSO 恢复任务按 sub 重试。 */
    public static final String USER_REVOCATIONS = "sso:user-revocations:";
    /** 含有撤销墓碑的 subject 集合，供后台恢复任务无需扫描 Redis keyspace 即可定位待恢复项。 */
    public static final String REVOCATION_SUBJECTS = "sso:revocation-subjects";
    /** 升级时旧版用户级撤销索引已回填到 REVOCATION_SUBJECTS 的完成标记。 */
    public static final String REVOCATION_SUBJECTS_MIGRATION = "sso:revocation-subjects:migration:v1";
    /** 禁用/删除用户的创建会话门禁。 */
    public static final String SUBJECT_DISABLED = "sso:subject-disabled:";
    /** Redis 已撤销、但 MySQL Outbox 尚未完整持久化时保留的通知快照。 */
    public static final String REVOCATION = "sso:revocation:";
    public static final String CODE = "sso:code:";
    public static final String PENDING = "sso:pending:";
    public static final String PENDING_COUNT = "sso:pending-count:";
    public static final String LOGOUT_REQUEST = "sso:logout-request:";
    public static final String CLIENT_NONCE = "sso:client-nonce:";
    public static final String COOKIE_NAME = "SSO_SID";
    public static final String COOKIE_PATH = "/sso/v1";

    private SsoRedisKey() {
    }
}
