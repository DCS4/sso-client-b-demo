package com.dcs4.ssoclient.demo;

import com.dcs4.ssoclient.auth.LocalAccountDirectory;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 无需数据库的演示账号，可分别验证启用、停用和未录入。 */
@Component
public class DemoLocalAccountDirectory implements LocalAccountDirectory {
  private final Map<String, Boolean> accounts = new HashMap<>();

  public DemoLocalAccountDirectory() {
    accounts.put("108", true);
    accounts.put("109", false);
  }

  @Override
  public boolean isEnabled(String localUserId) {
    return Boolean.TRUE.equals(accounts.get(localUserId));
  }
}
