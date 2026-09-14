package org.jeecg.modules.sso.service;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import org.jeecg.modules.sso.config.SsoProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenServiceTest {
    @Test
    void sessionStatusIsRs256JwsBoundToClientSidAndRequestNonce() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        SigningKeyService signingKeyService = mock(SigningKeyService.class);
        when(signingKeyService.currentKid()).thenReturn("test-kid");
        when(signingKeyService.currentPrivateKey()).thenReturn(key.toRSAPrivateKey());
        SsoProperties properties = new SsoProperties();
        properties.setIssuer("http://sso.example");
        properties.setAssertionTtl(Duration.ofSeconds(60));

        String serialized = new TokenService(properties, signingKeyService)
                .issueSessionStatus("sid-1", true, "client-b", "nonce-1");
        SignedJWT token = SignedJWT.parse(serialized);

        assertEquals("RS256", token.getHeader().getAlgorithm().getName());
        assertTrue(token.verify(new RSASSAVerifier(key.toRSAPublicKey())));
        assertEquals("client-b", token.getJWTClaimsSet().getStringClaim("client_id"));
        assertEquals("client-b", token.getJWTClaimsSet().getAudience().get(0));
        assertEquals("sid-1", token.getJWTClaimsSet().getStringClaim("sid"));
        assertEquals("nonce-1", token.getJWTClaimsSet().getStringClaim("request_nonce"));
        assertEquals(Boolean.TRUE, token.getJWTClaimsSet().getBooleanClaim("active"));
    }
}
