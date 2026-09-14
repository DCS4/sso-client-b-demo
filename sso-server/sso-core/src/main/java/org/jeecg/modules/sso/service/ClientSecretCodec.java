package org.jeecg.modules.sso.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jeecg.modules.sso.config.SsoProperties;
import org.springframework.stereotype.Service;

/** 客户端 HMAC 密钥仅保存 AES-GCM 密文；每条密文使用独立随机 IV。 */
@Service
public class ClientSecretCodec {
    private static final int GCM_IV_BYTES = 12;
    private final SsoProperties properties;

    public ClientSecretCodec(SsoProperties properties) {
        this.properties = properties;
    }

    public String decrypt(String encoded) {
        try {
            String[] parts = encoded.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("HMAC密文格式必须为 base64(iv):base64(ciphertext)");
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey(), "AES"),
                    new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("客户端HMAC密钥解密失败", e);
        }
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey(), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("客户端HMAC密钥加密失败", e);
        }
    }

    private byte[] masterKey() {
        String raw = System.getenv(properties.getMasterKeyEnv());
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalStateException("缺少环境变量 " + properties.getMasterKeyEnv());
        }
        byte[] key = Base64.getDecoder().decode(raw);
        if (key.length != 32) {
            throw new IllegalStateException("SSO 主密钥必须是 Base64 编码的 32 字节值");
        }
        return key;
    }
}
