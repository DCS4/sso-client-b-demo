package org.jeecg.modules.sso.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.PostConstruct;
import org.jeecg.modules.sso.config.SsoProperties;
import org.springframework.stereotype.Service;

/**
 * 维护 key ring：JWKS 只发布当前签名 key，以及配置显式允许且仍在保留期内的额外 key。
 * 私钥目录中的其它 JWK 不会因为文件存在而被意外信任或发布。
 */
@Service
public class SigningKeyService {
    private final SsoProperties properties;
    private final Map<String, RSAKey> keys = new LinkedHashMap<String, RSAKey>();
    private RSAKey current;

    public SigningKeyService(SsoProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void initialize() {
        try {
            Path directory = Paths.get(properties.getKeyStorePath());
            boolean directoryExisted = Files.exists(directory);
            Files.createDirectories(directory);
            if (!directoryExisted) {
                setOwnerOnlyPermissions(directory, true);
            }
            requireOwnerOnlyPermissions(directory, true);
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(path -> path.getFileName().toString().endsWith(".jwk"))
                        .peek(path -> requireOwnerOnlyPermissions(path, false))
                        .forEach(this::loadQuietly);
            }
            current = keys.get(properties.getCurrentKid());
            if (current == null || current.isPrivate() == false) {
                current = new RSAKeyGenerator(2048)
                        .keyID(properties.getCurrentKid())
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256)
                        .generate();
                Path target = directory.resolve(properties.getCurrentKid() + ".jwk");
                Files.write(target, current.toJSONString().getBytes(StandardCharsets.UTF_8));
                setOwnerOnlyPermissions(target, false);
                requireOwnerOnlyPermissions(target, false);
                keys.put(current.getKeyID(), current);
            }
            validateConfiguredAdditionalKids();
        } catch (Exception e) {
            throw new IllegalStateException("SSO签名密钥初始化失败", e);
        }
    }

    public synchronized RSAPrivateKey currentPrivateKey() {
        try {
            return current.toRSAPrivateKey();
        } catch (Exception e) {
            throw new IllegalStateException("当前签名私钥不可用", e);
        }
    }

    public synchronized RSAPublicKey currentPublicKey() {
        try {
            return current.toRSAPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException("当前签名公钥不可用", e);
        }
    }

    public synchronized String currentKid() {
        return current.getKeyID();
    }

    public synchronized String jwksJson() {
        List<JWK> publicKeys = new ArrayList<JWK>();
        Instant now = Instant.now();
        for (RSAKey key : keys.values()) {
            if (isPublished(key.getKeyID(), now)) {
                publicKeys.add(key.toPublicJWK());
            }
        }
        return new JWKSet(publicKeys).toString();
    }

    private void validateConfiguredAdditionalKids() {
        Instant now = Instant.now();
        for (Map.Entry<String, Instant> entry : properties.getJwksAdditionalKids().entrySet()) {
            String kid = entry.getKey();
            Instant publishUntil = entry.getValue();
            if (kid == null || kid.trim().isEmpty() || publishUntil == null) {
                throw new IllegalStateException("JWKS 额外 kid 必须同时配置 kid 和截止时间");
            }
            if (publishUntil.isAfter(now) && !keys.containsKey(kid)) {
                throw new IllegalStateException("JWKS 额外 kid 对应 JWK 文件不存在: " + kid);
            }
        }
    }

    private boolean isPublished(String kid, Instant now) {
        if (current != null && current.getKeyID().equals(kid)) {
            return true;
        }
        Instant publishUntil = properties.getJwksAdditionalKids().get(kid);
        return publishUntil != null && now.isBefore(publishUntil);
    }

    private void loadQuietly(Path path) {
        try {
            RSAKey key = RSAKey.parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            keys.put(key.getKeyID(), key);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取签名密钥 " + path, e);
        }
    }

    private void requireOwnerOnlyPermissions(Path path, boolean directory) {
        try {
            if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
                return;
            }
            String expected = directory ? "rwx------" : "rw-------";
            if (!Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString(expected))) {
                throw new IllegalStateException("SSO 私钥目录或 JWK 文件权限必须为 " + expected + ": " + path);
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("无法校验 SSO 私钥文件权限: " + path, ex);
        }
    }

    private void setOwnerOnlyPermissions(Path path, boolean directory) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                String expected = directory ? "rwx------" : "rw-------";
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(expected));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("无法收紧 SSO 私钥文件权限: " + path, ex);
        }
    }
}
