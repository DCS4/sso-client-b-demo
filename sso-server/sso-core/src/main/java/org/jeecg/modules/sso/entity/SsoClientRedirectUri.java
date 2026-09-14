package org.jeecg.modules.sso.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sso_client_redirect_uri")
public class SsoClientRedirectUri {
    private String id;
    private String clientId;
    private String redirectUri;
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getClientId() { return clientId; }
    public void setClientId(String value) { clientId = value; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String value) { redirectUri = value; }
}
