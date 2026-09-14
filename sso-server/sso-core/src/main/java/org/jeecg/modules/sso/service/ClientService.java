package org.jeecg.modules.sso.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jeecg.modules.sso.entity.SsoClient;
import org.jeecg.modules.sso.mapper.SsoClientMapper;
import org.jeecg.modules.sso.mapper.SsoClientRedirectUriMapper;
import org.springframework.stereotype.Service;

/** 客户端登记按 client_id 精确读取；短缓存避免数据库短暂抖动阻断认证。 */
@Service
public class ClientService {
    private static final long CACHE_MILLIS = 60_000L;
    private final SsoClientMapper clientMapper;
    private final SsoClientRedirectUriMapper redirectMapper;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<String, CacheEntry>();

    public ClientService(SsoClientMapper clientMapper, SsoClientRedirectUriMapper redirectMapper) {
        this.clientMapper = clientMapper;
        this.redirectMapper = redirectMapper;
    }

    public SsoClient getEnabled(String clientId) {
        CacheEntry entry = load(clientId);
        return entry == null ? null : entry.client;
    }

    public boolean redirectUriRegistered(String clientId, String redirectUri) {
        CacheEntry entry = load(clientId);
        return entry != null && entry.redirectUris.contains(redirectUri);
    }

    public void evict(String clientId) {
        cache.remove(clientId);
    }

    private CacheEntry load(String clientId) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return null;
        }
        CacheEntry cached = cache.get(clientId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < CACHE_MILLIS) {
            return cached;
        }
        SsoClient client = clientMapper.selectOne(new LambdaQueryWrapper<SsoClient>()
                .eq(SsoClient::getClientId, clientId));
        if (client == null || !Integer.valueOf(1).equals(client.getStatus())) {
            cache.remove(clientId);
            return null;
        }
        List<String> uris = redirectMapper.selectUrisByClientId(clientId);
        CacheEntry fresh = new CacheEntry(client, uris == null ? Collections.<String>emptyList() : uris, now);
        cache.put(clientId, fresh);
        return fresh;
    }

    private static final class CacheEntry {
        private final SsoClient client;
        private final List<String> redirectUris;
        private final long loadedAt;
        private CacheEntry(SsoClient client, List<String> redirectUris, long loadedAt) {
            this.client = client;
            this.redirectUris = redirectUris;
            this.loadedAt = loadedAt;
        }
    }
}
