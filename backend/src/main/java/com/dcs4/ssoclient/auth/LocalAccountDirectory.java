package com.dcs4.ssoclient.auth;

/** 按本地用户编号查询账号。实际接入时在实现类中调用已有用户服务。 */
public interface LocalAccountDirectory {
  /** 只有账号存在且启用时才返回 true。 */
  boolean isEnabled(String localUserId);
}
