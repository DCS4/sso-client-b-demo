package org.jeecg.modules.sso.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Redis 已完成会话撤销、但 MySQL Outbox 尚未完整落库时的恢复任务。
 *
 * <p>门户本地登出会在 SSO 短暂不可用时继续完成，因此不能把再次调用
 * {@link LogoutService#revokeAndNotify(String)} 当作唯一恢复机会。本任务只遍历由 Lua
 * 原子维护的 subject 索引，不扫描 Redis keyspace。</p>
 */
@Slf4j
@Service
public class RevocationRecoveryService {
    private final SessionService sessionService;
    private final LogoutService logoutService;

    public RevocationRecoveryService(SessionService sessionService, LogoutService logoutService) {
        this.sessionService = sessionService;
        this.logoutService = logoutService;
    }

    @Scheduled(fixedDelayString = "${sso.revocation-recovery-fixed-delay-ms:10000}")
    public void recoverPendingRevocations() {
        Set<String> subjects;
        try {
            subjects = sessionService.getPendingRevocationSubjects();
        } catch (RuntimeException ex) {
            log.warn("读取 SSO 撤销恢复索引失败", ex);
            return;
        }
        for (String sub : subjects) {
            recoverSubject(sub);
        }
    }

    private void recoverSubject(String sub) {
        Set<String> sids;
        try {
            sids = sessionService.getPendingRevocationSids(sub);
        } catch (RuntimeException ex) {
            log.warn("读取 SSO 撤销恢复 SID 失败，sub={}", sub, ex);
            return;
        }
        for (String sid : sids) {
            try {
                logoutService.revokeAndNotify(sid);
            } catch (RuntimeException ex) {
                // 保留墓碑；下一轮仍按相同 sid/client 幂等补写 Outbox。
                log.warn("补写 SSO 登出 Outbox 失败，sidFingerprint={}，cause={}",
                        sidFingerprint(sid), ex.getClass().getSimpleName());
            }
        }
    }

    /** 日志仅保留诊断所需的不可逆短指纹，绝不输出 SSO_SID 原文。 */
    private String sidFingerprint(String sid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sid.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                value.append(String.format("%02x", Integer.valueOf(digest[index] & 0xff)));
            }
            return value.toString();
        } catch (Exception ignored) {
            return "unavailable";
        }
    }
}
