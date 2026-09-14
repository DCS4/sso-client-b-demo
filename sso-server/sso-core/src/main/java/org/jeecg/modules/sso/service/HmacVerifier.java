package org.jeecg.modules.sso.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.constant.SsoRedisKey;
import org.jeecg.modules.sso.entity.SsoClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** B 后端请求认证：时间窗、nonce 一次性、原始 body 哈希和常量时间签名比较。 */
@Service
public class HmacVerifier {
    public static final String HEADER_CLIENT = "X-SSO-Client";
    public static final String HEADER_TIMESTAMP = "X-SSO-Timestamp";
    public static final String HEADER_NONCE = "X-SSO-Nonce";
    public static final String HEADER_SIGNATURE = "X-SSO-Signature";
    public static final String ATTRIBUTE_CLIENT_ID = "ssoClientId";

    private final ClientService clientService;
    private final ClientSecretCodec secretCodec;
    private final StringRedisTemplate redis;
    private final SsoProperties properties;

    public HmacVerifier(ClientService clientService, ClientSecretCodec secretCodec,
                        StringRedisTemplate redis, SsoProperties properties) {
        this.clientService = clientService;
        this.secretCodec = secretCodec;
        this.redis = redis;
        this.properties = properties;
    }

    public String verify(HttpServletRequest request, byte[] rawBody) {
        String clientId = request.getHeader(HEADER_CLIENT);
        SsoClient client = clientService.getEnabled(clientId);
        if (client == null) return null;
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);
        if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) return null;
        try {
            long requestSeconds = Long.parseLong(timestamp);
            long now = System.currentTimeMillis() / 1000L;
            if (Math.abs(now - requestSeconds) > properties.getTimestampWindow().getSeconds()) return null;
            String bodyHash = hexSha256(rawBody == null ? new byte[0] : rawBody);
            String canonical = request.getMethod() + "\n" + request.getRequestURI() + "\n" + clientId
                    + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
            byte[] expected = sign(secretCodec.decrypt(client.getClientHmacKeyCipher()), canonical);
            byte[] actual = Base64.getUrlDecoder().decode(signature);
            if (!MessageDigest.isEqual(expected, actual)) return null;
            // 非法签名不能消耗合法调用方的 nonce；仅验签成功后登记一次性 nonce。
            Boolean first = redis.opsForValue().setIfAbsent(
                    SsoRedisKey.CLIENT_NONCE + clientId + ":" + nonce, "1", properties.getNonceTtl());
            if (!Boolean.TRUE.equals(first)) return null;
            request.setAttribute(ATTRIBUTE_CLIENT_ID, clientId);
            return clientId;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] sign(String key, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexSha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
