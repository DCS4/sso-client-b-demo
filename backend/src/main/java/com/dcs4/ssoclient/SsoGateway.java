package com.dcs4.ssoclient;

import com.dcs4.ssoclient.SsoModels.ActiveTokenData;
import com.dcs4.ssoclient.SsoModels.TokenData;
import com.dcs4.ssoclient.SsoModels.UserInfo;

/**
 * 【接入必要项 1/5：SSO 协议适配边界】
 *
 * <p>业务流程只依赖本接口，不关心 HTTP 表单、JSON 字段、client_secret 或具体 HTTP 客户端。
 * 其它厂商可以让现有后端 HTTP 客户端实现本接口；协议字段及错误处理参见 SsoClient。
 * 这里不是新增的服务端 API，也不改变原有 SSO V2 请求/响应契约。</p>
 */
public interface SsoGateway {
  /** 浏览器只导航到公开的授权地址，client_secret 和 Token 不得出现在 URL 中。 */
  String authorizeUrl(String state, String pageCode);

  /** 固定后端回调使用一次性 code 兑换 Token；code 不由前端兑换。 */
  TokenData exchangeCode(String code);

  /** 旧 AccessToken 无效时，使用服务端保存的 RefreshToken 换取完整的新 Token 对。 */
  TokenData refresh(String refreshToken);

  /** 每次受保护页面请求都实时校验；连接失败不能视为已授权。 */
  ActiveTokenData checkAccessToken(String accessToken, String pageCode);

  /** 后端根据已校验的 AccessToken 获取用户信息；不暴露给浏览器敏感凭据。 */
  UserInfo getUserInfo(String accessToken);

  /** 只撤销当前客户端的 Token family；不能代替 Portal 的全局 SID 登出。 */
  boolean logout(String accessToken);

  /** 协议拒绝与 SSO 服务不可达需区分，业务链路对不可达应 fail closed。 */
  class SsoClientException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean unavailable;

    public SsoClientException(String message, boolean unavailable) {
      super(message);
      this.unavailable = unavailable;
    }

    public SsoClientException(String message, boolean unavailable, Throwable cause) {
      super(message, cause);
      this.unavailable = unavailable;
    }

    public boolean isUnavailable() {
      return unavailable;
    }
  }
}
