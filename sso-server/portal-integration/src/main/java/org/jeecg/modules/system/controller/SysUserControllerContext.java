package org.jeecg.modules.system.controller;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.base.service.BaseCommonService;
import org.jeecg.modules.system.entity.SysUser;
import org.jeecg.modules.system.service.ISysUserService;
import org.jeecg.modules.system.sso.SsoUserStatusCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

/**
 * ============================================================================
 * SSO改造说明 - SysUserController 完整上下文备份
 * ============================================================================
 * 本类展示了在用户管理后台中，当管理员对用户进行【删除】、【批量删除】、【冻结/解冻】时，
 * 如何通过 SsoUserStatusCoordinator 将状态变更同步发往 SSO，
 * 触发 SSO 全局会话踢出与已登录客户端 Back-channel 撤回：
 * 
 * 1. 字段注入：
 *    - @Autowired(required = false) private SsoUserStatusCoordinator ssoUserStatusCoordinator;
 * 
 * 2. 删除/冻结委托：
 *    - 若 ssoUserStatusCoordinator 为空，执行原有的单库业务逻辑；
 *    - 若不为空，委托 coordinator 触发事务发件箱（Outbox）写入与 SSO 会话销毁。
 * ============================================================================
 */
@RestController
@RequestMapping("/sys/user")
@Slf4j
public class SysUserControllerContext {

    @Autowired
    private ISysUserService sysUserService;
    @Resource
    private BaseCommonService baseCommonService;

    // ------------------------------------------------------------------------
    // SSO 状态协调器 (optional，若未配置则回退原逻辑)
    // ------------------------------------------------------------------------
    @Autowired(required = false)
    private SsoUserStatusCoordinator ssoUserStatusCoordinator;

    /**
     * 单个删除用户
     */
    @RequestMapping(value = "/delete", method = RequestMethod.DELETE)
    public Result<?> delete(@RequestParam(name="id",required=true) String id) {
        baseCommonService.addLog("删除用户，id： " + id, CommonConstant.LOG_TYPE_2, 3);
        List<String> userNameList = sysUserService.userIdToUsername(Arrays.asList(id));

        // 委托给 SSO 协调器处理 (内部级联删除本地用户 + 发送 SSO 用户注销事件)
        if (ssoUserStatusCoordinator == null) {
            this.sysUserService.deleteUser(id);
        } else {
            ssoUserStatusCoordinator.deleteUser(id);
        }

        if (!userNameList.isEmpty()) {
            String joinedString = String.join(",", userNameList);
            baseCommonService.addLog("删除用户，账号： " + joinedString, CommonConstant.LOG_TYPE_2, 3);
        }
        return Result.ok("删除用户成功");
    }

    /**
     * 批量删除用户
     */
    @RequestMapping(value = "/deleteBatch", method = RequestMethod.DELETE)
    public Result<?> deleteBatch(@RequestParam(name="ids",required=true) String ids) {
        baseCommonService.addLog("批量删除用户， ids： " + ids, CommonConstant.LOG_TYPE_2, 3);
        List<String> userNameList = sysUserService.userIdToUsername(Arrays.asList(ids.split(",")));

        // 委托给 SSO 协调器批量处理
        if (ssoUserStatusCoordinator == null) {
            this.sysUserService.deleteBatchUsers(ids);
        } else {
            ssoUserStatusCoordinator.deleteBatchUsers(ids);
        }
        return Result.ok("批量删除用户成功");
    }

    /**
     * 冻结 / 解冻用户状态
     */
    @RequestMapping(value = "/changeStatus", method = RequestMethod.POST)
    public Result<SysUser> changeStatus(@RequestBody JSONObject jsonObject) {
        Result<SysUser> result = new Result<SysUser>();
        try {
            String ids = jsonObject.getString("ids");
            String status = jsonObject.getString("status");

            if (ssoUserStatusCoordinator != null) {
                // 冻结操作通过 SSO 协调器处理：修改状态 + 写入发件箱 + 实时踢掉 SSO 全局会话
                ssoUserStatusCoordinator.updateFrozenUsers(ids, status);
            } else {
                sysUserService.checkUserAdminRejectDel(ids);
                String[] arr = ids.split(",");
                for (String id : arr) {
                    if (oConvertUtils.isNotEmpty(id)) {
                        sysUserService.updateStatus(id, status);
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            result.error500("操作失败" + e.getMessage());
        }
        return result;
    }
}
