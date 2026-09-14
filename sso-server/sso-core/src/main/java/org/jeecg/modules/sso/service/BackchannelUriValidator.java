package org.jeecg.modules.sso.service;

import java.net.URI;
import java.util.List;
import org.jeecg.modules.sso.config.SsoProperties;
import org.springframework.stereotype.Service;

/**
 * 回调地址只接受字面 IPv4 与显式配置的企业 CIDR。即使 CIDR 被误配过宽，也拒绝特殊用途
 * 地址，避免 DNS 重绑定、环回地址和数据库登记信息共同构成 SSRF 出口。
 */
@Service
public class BackchannelUriValidator {
    /** Outbox 目标必须是精确的业务网段；禁止把全网或整个私网作为 SSRF 出站白名单。 */
    private static final int MIN_ALLOWED_CIDR_PREFIX = 24;
    private final SsoProperties properties;

    public BackchannelUriValidator(SsoProperties properties) {
        this.properties = properties;
    }

    public boolean isAllowed(String value, String transport) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                return false;
            }
            if (transport == null || !scheme.equalsIgnoreCase(transport)) {
                return false;
            }
            String host = uri.getHost();
            if (!isIpv4Literal(host)) {
                return false;
            }
            long address = ipv4ToLong(host);
            if (isSpecialUseAddress(address)) {
                return false;
            }
            List<String> allowedCidrs = properties.getBackchannelAllowedCidrs();
            if (allowedCidrs == null || allowedCidrs.isEmpty()) {
                return false;
            }
            for (String cidr : allowedCidrs) {
                if (inCidr(address, cidr)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 非法 URI、非 IPv4 文本或非法 CIDR 一律拒绝。
        }
        return false;
    }

    private boolean inCidr(long address, String cidr) {
        try {
            String[] pair = cidr.split("/", -1);
            if (pair.length != 2 || !isIpv4Literal(pair[0])) {
                return false;
            }
            int prefix = Integer.parseInt(pair[1]);
            if (prefix < MIN_ALLOWED_CIDR_PREFIX || prefix > 32) {
                return false;
            }
            long network = ipv4ToLong(pair[0]);
            long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
            return (address & mask) == (network & mask);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isSpecialUseAddress(long address) {
        // IANA 特殊用途段中，RFC1918 私网仍可由企业 CIDR 显式允许；其他这里列出的
        // 非公共业务目的地一律拒绝，包括共享地址、协议保留、文档、基准和组播段。
        return (address & 0xff000000L) == 0L
                || inRange(address, 0x64400000L, 10)       // 100.64.0.0/10 Shared Address Space
                || (address & 0xff000000L) == 0x7f000000L
                || (address & 0xffff0000L) == 0xa9fe0000L
                || inRange(address, 0xc0000000L, 24)       // 192.0.0.0/24 IETF Protocol Assignments
                || inRange(address, 0xc0000200L, 24)       // 192.0.2.0/24 TEST-NET-1
                || inRange(address, 0xc01fc400L, 24)       // 192.31.196.0/24 AS112-v4
                || inRange(address, 0xc034c100L, 24)       // 192.52.193.0/24 AMT
                || inRange(address, 0xc0586300L, 24)       // 192.88.99.0/24 Deprecated 6to4
                || inRange(address, 0xc0af3000L, 24)       // 192.175.48.0/24 Direct Delegation AS112
                || inRange(address, 0xc6120000L, 15)       // 198.18.0.0/15 Benchmarking
                || inRange(address, 0xc6336400L, 24)       // 198.51.100.0/24 TEST-NET-2
                || inRange(address, 0xcb007100L, 24)       // 203.0.113.0/24 TEST-NET-3
                || (address & 0xe0000000L) == 0xe0000000L;
    }

    private boolean inRange(long address, long network, int prefix) {
        long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        return (address & mask) == (network & mask);
    }

    private boolean isIpv4Literal(String value) {
        if (value == null || !value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return false;
        }
        String[] octets = value.split("\\.");
        for (String octet : octets) {
            int number = Integer.parseInt(octet);
            if (number < 0 || number > 255) {
                return false;
            }
        }
        return true;
    }

    private long ipv4ToLong(String value) {
        String[] octets = value.split("\\.");
        long result = 0L;
        for (String octet : octets) {
            result = (result << 8) | Integer.parseInt(octet);
        }
        return result;
    }
}
