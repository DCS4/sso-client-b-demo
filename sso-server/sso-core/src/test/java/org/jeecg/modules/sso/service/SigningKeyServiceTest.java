package org.jeecg.modules.sso.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.jeecg.modules.sso.config.SsoProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SigningKeyServiceTest {
    @TempDir Path keyStore;

    @Test
    void publishesOnlyCurrentAndUnexpiredExplicitAdditionalKids() throws Exception {
        setOwnerOnlyDirectoryPermissions(keyStore);
        writeKey("current");
        writeKey("retained");
        writeKey("expired");
        writeKey("unrelated");
        SsoProperties properties = new SsoProperties();
        properties.setKeyStorePath(keyStore.toString());
        properties.setCurrentKid("current");
        Map<String, Instant> additional = new LinkedHashMap<String, Instant>();
        additional.put("retained", Instant.now().plusSeconds(60));
        additional.put("expired", Instant.now().minusSeconds(1));
        properties.setJwksAdditionalKids(additional);

        SigningKeyService service = new SigningKeyService(properties);
        service.initialize();

        assertEquals(java.util.Arrays.asList("current", "retained"), JWKSet.parse(service.jwksJson())
                .getKeys().stream().map(key -> key.getKeyID()).sorted().collect(Collectors.toList()));
    }

    private void writeKey(String kid) throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID(kid).generate();
        Path target = keyStore.resolve(kid + ".jwk");
        Files.write(target, key.toJSONString().getBytes(StandardCharsets.UTF_8));
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private void setOwnerOnlyDirectoryPermissions(Path directory) throws Exception {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
