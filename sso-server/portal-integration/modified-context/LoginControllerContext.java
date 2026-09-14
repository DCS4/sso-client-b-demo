package org.jeecg.modules.system.controller;

import com.alibaba.fastjson.JSONObject;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.constant.CacheConstant;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.util.PasswordUtil;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.base.service.BaseCommonService;
import org.jeecg.modules.system.entity.SysUser;
import org.jeecg.modules.system.model.SysLoginModel;
import org.jeecg.modules.system.service.ISysUserService;
import org.jeecg.modules.system.sso.PortalSsoInnerClient;
import org.jeecg.modules.system.sso.PortalSsoMapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * ============================================================================
 * SSO改造说明 - LoginController 完整上下文备份
 * ============================================================================
 * 本类展示了在原有 JeecgBoot (或任意 Spring Boot 系统) 的 LoginController 中，
 * 如何以【极小侵入、零破坏原登录安全】的方式接入 SSO 门户会话：
 * 
 * 1. 字段注入：
 *    - @Autowired(required = false) private PortalSsoInnerClient portalSsoInnerClient;
 *    - @Autowired(required = false) private PortalSsoMapService portalSsoMapService;
 *    - private static final String PORTAL_SSO_REF_COOKIE = "PORTAL_SSO_REF";
 * 
 * 2. 登录改造 (login 方法)：
 *    - 原有流程：校验验证码 -> 校验用户名密码 -> 签发本地 JWT Token；
 *    - SSO 挂载点：在本地 JWT 签发完成（step.4 之后），调用 attachSsoSession()；
 *    - 效果：若 SSO 可用，通过 HttpOnly Cookie 种下 SSO_SID 与 PORTAL_SSO_REF，
 *      并在返回结果中带上 ssoRedirect（供前端自动跳转回业务客户端）；
 *      若 SSO 异常，直接 catch 降级为本地普通登录，完全不阻塞原系统运行！
 * 
 * 3. 登出改造 (logout 方法)：
 *    - 原有流程：清空 Redis 本地 Token 与 Shiro Subject；
 *    - SSO 挂载点：登出前先通知 SSO 服务端撤销全局会话，并生成 ssoLogoutRedirect URL，
 *      最后将该 URL 返回给前端，触发跨域全局联动登出。
 * ============================================================================
 */
@RestController
@RequestMapping("/sys")
@Slf4j
public class LoginControllerContext {

    @Autowired
    private ISysUserService sysUserService;
    @Autowired
    private RedisUtil redisUtil;
    @Resource
    private BaseCommonService baseCommonService;

    // ------------------------------------------------------------------------
    // SSO 依赖注入 (均为 optional，未开启 SSO 时安全降级为 null)
    // ------------------------------------------------------------------------
    @Autowired(required = false)
    private PortalSsoInnerClient portalSsoInnerClient;

    @Autowired(required = false)
    private PortalSsoMapService portalSsoMapService;

    private static final String PORTAL_SSO_REF_COOKIE = "PORTAL_SSO_REF";

    /**
     * 完整登录接口 (带 SSO 会话联动)
     */
    @ApiOperation("登录接口")
    @RequestMapping(value = "/login", method = RequestMethod.POST)
    public Result<JSONObject> login(@RequestBody SysLoginModel sysLoginModel,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        Result<JSONObject> result = new Result<JSONObject>();
        String username = sysLoginModel.getUsername();
        String password = sysLoginModel.getPassword();

        // step.1 校验验证码 (保留原系统逻辑)
        // ... 原验证码逻辑 ...

        // step.2 校验用户名密码
        SysUser sysUser = sysUserService.getUserByName(username);
        if (sysUser == null) {
            return result.error500("该用户不存在");
        }
        String userpassword = PasswordUtil.encrypt(username, password, sysUser.getSalt());
        String syspassword = sysUser.getPassword();
        if (!syspassword.equals(userpassword)) {
            return result.error500("用户名或密码错误");
        }

        // step.3 用户状态检查 (是否冻结)
        if (sysUser.getStatus() != 1) {
            return result.error500("该用户已冻结");
        }

        // step.4 登录成功生成本地 JWT Token
        // userInfo(sysUser, result, request); -> 生成 token 并存入 result.getResult()

        // --------------------------------------------------------------------
        // step.4.1 SSO登录钩子：仅在本地登录已成功后执行，失败降级，不影响原有登录
        // --------------------------------------------------------------------
        attachSsoSession(sysUser, sysLoginModel, result, request, response);

        // step.5 清理验证码与重试失败计数
        // ...
        return result;
    }

    /**
     * 完整登出接口 (带 SSO 全局联动登出)
     */
    @RequestMapping(value = "/logout")
    public Result<Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = request.getHeader(CommonConstant.X_ACCESS_TOKEN);
        if (oConvertUtils.isEmpty(token)) {
            return Result.error("退出登录失败！");
        }
        String username = JwtUtil.getUsername(token);
        SysUser sysUser = sysUserService.getUserByName(username);
        if (sysUser != null) {
            baseCommonService.addLog("用户名: " + sysUser.getRealname() + ",退出成功！", CommonConstant.LOG_TYPE_1, null, sysUser);
            log.info(" 用户名:  " + sysUser.getRealname() + ",退出成功！ ");

            // ----------------------------------------------------------------
            // SSO 退出钩子：向 SSO 申请退出并构建浏览器跳转地址
            // ----------------------------------------------------------------
            String ssoLogoutRedirect = null;
            try {
                if (portalSsoInnerClient != null && portalSsoMapService != null) {
                    String sid = portalSsoMapService.get(token);
                    if (oConvertUtils.isEmpty(sid)) {
                        sid = readCookie(request, PORTAL_SSO_REF_COOKIE);
                    }
                    if (oConvertUtils.isNotEmpty(sid)) {
                        String logoutRequest = portalSsoInnerClient.logout(sid);
                        ssoLogoutRedirect = portalSsoInnerClient.browserLogoutUrl(logoutRequest);
                        portalSsoMapService.delete(token);
                    }
                    response.addHeader("Set-Cookie", PORTAL_SSO_REF_COOKIE
                            + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
                }
            } catch (Exception e) {
                log.warn("SSO全局登出失败，继续执行本地登出", e);
            }

            // 清空本地 Token 缓存与 Shiro 权限
            redisUtil.del(CommonConstant.PREFIX_USER_TOKEN + token);
            redisUtil.del(CommonConstant.PREFIX_USER_SHIRO_CACHE + sysUser.getId());
            redisUtil.del(String.format("%s::%s", CacheConstant.SYS_USERS_CACHE, sysUser.getUsername()));
            SecurityUtils.getSubject().logout();

            Result<Object> logoutResult = Result.ok("退出登录成功！");
            // 若有全局退出跳转，则将 ssoLogoutRedirect 传递给前端
            if (ssoLogoutRedirect != null) {
                JSONObject payload = new JSONObject();
                payload.put("ssoLogoutRedirect", ssoLogoutRedirect);
                logoutResult.setResult(payload);
            }
            return logoutResult;
        } else {
            return Result.error("Token无效!");
        }
    }

    /**
     * 【核心挂载函数】为当前已认证用户在 SSO 服务端注册/续订全局会话
     */
    private void attachSsoSession(SysUser sysUser, SysLoginModel loginModel, Result<JSONObject> result,
                                  HttpServletRequest request, HttpServletResponse response) {
        if (portalSsoInnerClient == null || portalSsoMapService == null || result.getResult() == null) {
            return;
        }
        try {
            String token = result.getResult().getString("token");
            if (oConvertUtils.isEmpty(token)) {
                return;
            }
            String oldToken = request.getHeader(CommonConstant.X_ACCESS_TOKEN);
            String oldSid = portalSsoMapService.get(oldToken);
            if (oConvertUtils.isEmpty(oldSid)) {
                oldSid = readCookie(request, PORTAL_SSO_REF_COOKIE);
            }
            PortalSsoInnerClient.BeginSessionResult session = portalSsoInnerClient.beginSession(
                    sysUser.getId(), sysUser.getUsername(), loginModel.getPending(), oldSid);
            long maxAge = session.getExpiresAt() - System.currentTimeMillis() / 1000L;
            if (maxAge <= 0L) {
                throw new IllegalStateException("SSO返回了已过期会话");
            }
            // 种下 SSO_SID Cookie (仅 /sso/v1 路径可见，保护会话隔离)
            response.addHeader("Set-Cookie", "SSO_SID=" + session.getSid()
                    + "; Path=/sso/v1; Max-Age=" + maxAge + "; HttpOnly; SameSite=Lax");
            // 种下 PORTAL_SSO_REF Cookie (根路径，仅供下次同一浏览器登录时读取 oldSid)
            response.addHeader("Set-Cookie", PORTAL_SSO_REF_COOKIE + "=" + session.getSid()
                    + "; Path=/; Max-Age=" + maxAge + "; HttpOnly; SameSite=Lax");
            
            long localRemaining = JwtUtil.EXPIRE_TIME / 1000L;
            portalSsoMapService.save(token, session.getSid(), Math.min(maxAge, localRemaining));
            if (oConvertUtils.isNotEmpty(session.getRedirectUrl())) {
                result.getResult().put("ssoRedirect", session.getRedirectUrl());
            }
        } catch (Exception e) {
            log.error("SSO会话建立失败，降级为本地登录", e);
        }
    }

    /**
     * 读取指定名称的 Cookie
     */
    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
