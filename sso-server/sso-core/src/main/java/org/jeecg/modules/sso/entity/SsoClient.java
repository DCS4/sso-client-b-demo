package org.jeecg.modules.sso.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sso_client")
public class SsoClient {
    private String id;
    private String clientId;
    private String clientName;
    private String clientHmacKeyCipher;
    private String clientHmacKeyVersion;
    private Integer status;
    private String transport;
    private String backchannelLogoutUri;
    private String postLogoutRedirectUri;
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getClientId() { return clientId; }
    public void setClientId(String value) { clientId = value; }
    public String getClientName() { return clientName; }
    public void setClientName(String value) { clientName = value; }
    public String getClientHmacKeyCipher() { return clientHmacKeyCipher; }
    public void setClientHmacKeyCipher(String value) { clientHmacKeyCipher = value; }
    public String getClientHmacKeyVersion() { return clientHmacKeyVersion; }
    public void setClientHmacKeyVersion(String value) { clientHmacKeyVersion = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
    public String getTransport() { return transport; }
    public void setTransport(String value) { transport = value; }
    public String getBackchannelLogoutUri() { return backchannelLogoutUri; }
    public void setBackchannelLogoutUri(String value) { backchannelLogoutUri = value; }
    public String getPostLogoutRedirectUri() { return postLogoutRedirectUri; }
    public void setPostLogoutRedirectUri(String value) { postLogoutRedirectUri = value; }
}
