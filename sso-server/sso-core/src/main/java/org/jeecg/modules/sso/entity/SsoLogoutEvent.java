package org.jeecg.modules.sso.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("sso_logout_event")
public class SsoLogoutEvent {
    private String id;
    private String sid;
    private String sub;
    private String clientId;
    private String eventType;
    private String logoutUri;
    private String eventPayload;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String claimToken;
    private LocalDateTime claimUntil;
    private String lastError;
    private LocalDateTime deliveredAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getSid() { return sid; }
    public void setSid(String value) { sid = value; }
    public String getSub() { return sub; }
    public void setSub(String value) { sub = value; }
    public String getClientId() { return clientId; }
    public void setClientId(String value) { clientId = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public String getLogoutUri() { return logoutUri; }
    public void setLogoutUri(String value) { logoutUri = value; }
    public String getEventPayload() { return eventPayload; }
    public void setEventPayload(String value) { eventPayload = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer value) { retryCount = value; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime value) { nextRetryAt = value; }
    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String value) { claimToken = value; }
    public LocalDateTime getClaimUntil() { return claimUntil; }
    public void setClaimUntil(LocalDateTime value) { claimUntil = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime value) { deliveredAt = value; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime value) { createTime = value; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime value) { updateTime = value; }
}
