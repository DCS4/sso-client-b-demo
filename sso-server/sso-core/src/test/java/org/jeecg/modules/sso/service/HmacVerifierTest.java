package org.jeecg.modules.sso.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.entity.SsoClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HmacVerifierTest {
    @Mock private ClientService clientService;
    @Mock private ClientSecretCodec secretCodec;
    @Mock private StringRedisTemplate redis;

    @Test
    void invalidSignatureDoesNotConsumeNonce() {
        MockHttpServletRequest request = request(String.valueOf(System.currentTimeMillis() / 1000L), "invalid-signature");
        when(clientService.getEnabled("client-b")).thenReturn(client());
        when(secretCodec.decrypt("cipher")).thenReturn("secret");

        assertNull(verifier().verify(request, "{}".getBytes(StandardCharsets.UTF_8)));

        verifyNoInteractions(redis);
    }

    @Test
    void validSignatureConsumesNonceAfterVerification() throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        MockHttpServletRequest request = request(timestamp, signature(timestamp));
        when(clientService.getEnabled("client-b")).thenReturn(client());
        when(secretCodec.decrypt("cipher")).thenReturn("secret");
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("sso:client-nonce:client-b:nonce-1"), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        assertEquals("client-b", verifier().verify(request, "{}".getBytes(StandardCharsets.UTF_8)));

        verify(values).setIfAbsent(eq("sso:client-nonce:client-b:nonce-1"), eq("1"), any(Duration.class));
    }

    private HmacVerifier verifier() {
        SsoProperties properties = new SsoProperties();
        properties.setTimestampWindow(Duration.ofMinutes(2));
        properties.setNonceTtl(Duration.ofMinutes(5));
        return new HmacVerifier(clientService, secretCodec, redis, properties);
    }

    private SsoClient client() {
        SsoClient client = new SsoClient();
        client.setClientHmacKeyCipher("cipher");
        return client;
    }

    private MockHttpServletRequest request(String timestamp, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/sso/v1/exchange");
        request.addHeader(HmacVerifier.HEADER_CLIENT, "client-b");
        request.addHeader(HmacVerifier.HEADER_TIMESTAMP, timestamp);
        request.addHeader(HmacVerifier.HEADER_NONCE, "nonce-1");
        request.addHeader(HmacVerifier.HEADER_SIGNATURE, signature);
        return request;
    }

    private String signature(String timestamp) throws Exception {
        // Recompute the body hash so the test stays aligned with the protocol.
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        String hash = hex(digest.digest("{}".getBytes(StandardCharsets.UTF_8)));
        String canonical = "POST\n/sso/v1/exchange\nclient-b\n" + timestamp + "\nnonce-1\n" + hash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", Integer.valueOf(item & 0xff)));
        }
        return result.toString();
    }
}
