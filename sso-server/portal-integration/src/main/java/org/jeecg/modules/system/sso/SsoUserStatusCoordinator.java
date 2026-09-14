package org.jeecg.modules.system.sso;

import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.system.service.ISysUserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启用 SSO 时才接管用户状态变更，将原变更与 Outbox 写入放在同一事务。
 * 未启用时该 Bean 不存在，Controller 直接保持原 System 调用路径。
 */
@Service
@ConditionalOnProperty(prefix = "sso.integration", name = "enabled", havingValue = "true")
public class SsoUserStatusCoordinator {
    private final ISysUserService sysUserService;
    private final UserStatusOutboxService outboxService;

    public SsoUserStatusCoordinator(ISysUserService sysUserService, UserStatusOutboxService outboxService) {
        this.sysUserService = sysUserService;
        this.outboxService = outboxService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(String userId) {
        sysUserService.deleteUser(userId);
        outboxService.record(userId, UserStatusOutboxService.USER_DELETED);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteBatchUsers(String userIds) {
        sysUserService.deleteBatchUsers(userIds);
        for (String userId : userIds.split(",")) {
            if (oConvertUtils.isNotEmpty(userId)) {
                outboxService.record(userId, UserStatusOutboxService.USER_DELETED);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateFrozenUsers(String userIds, String status) {
        sysUserService.checkUserAdminRejectDel(userIds);
        for (String userId : userIds.split(",")) {
            if (oConvertUtils.isNotEmpty(userId)) {
                sysUserService.updateStatus(userId, status);
                outboxService.record(userId, CommonConstant.STATUS_1.equals(status)
                        ? UserStatusOutboxService.USER_ENABLED : UserStatusOutboxService.USER_DISABLED);
            }
        }
    }
}
