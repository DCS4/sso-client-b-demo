package org.jeecg.modules.sso.service;

import java.util.Arrays;
import org.jeecg.modules.sso.config.SsoProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackchannelUriValidatorTest {
    private final SsoProperties properties = properties();
    private final BackchannelUriValidator validator = new BackchannelUriValidator(properties);

    @Test
    void acceptsOnlyConfiguredLiteralIpv4CidrAndTransport() {
        assertTrue(validator.isAllowed("http://192.9.230.35:8080/sso/backchannel-logout", "http"));
        assertFalse(validator.isAllowed("https://192.9.230.35:8080/sso/backchannel-logout", "http"));
        SsoProperties tooBroad = new SsoProperties();
        tooBroad.setBackchannelAllowedCidrs(Arrays.asList("192.9.230.0/23"));
        assertFalse(new BackchannelUriValidator(tooBroad)
                .isAllowed("http://192.9.230.35:8080/sso/backchannel-logout", "http"));
    }

    @Test
    void rejectsDnsLoopbackAndOutOfRangeTargets() {
        assertFalse(validator.isAllowed("http://sso.internal/logout", "http"));
        assertFalse(validator.isAllowed("http://127.0.0.1:8080/logout", "http"));
        assertFalse(validator.isAllowed("http://192.9.231.35:8080/logout", "http"));
    }

    @Test
    void rejectsEverySpecialUseTargetAfterItPassesTheCidrCheck() {
        assertSpecialTargetRejected("0.0.0.0");
        assertSpecialTargetRejected("127.0.0.1");
        assertSpecialTargetRejected("169.254.169.254");
        assertSpecialTargetRejected("100.64.1.1");
        assertSpecialTargetRejected("192.0.0.9");
        assertSpecialTargetRejected("192.0.2.1");
        assertSpecialTargetRejected("192.31.196.1");
        assertSpecialTargetRejected("192.52.193.1");
        assertSpecialTargetRejected("192.88.99.1");
        assertSpecialTargetRejected("192.175.48.1");
        assertSpecialTargetRejected("198.18.1.1");
        assertSpecialTargetRejected("198.51.100.1");
        assertSpecialTargetRejected("203.0.113.1");
        assertSpecialTargetRejected("239.1.1.1");
        assertSpecialTargetRejected("255.255.255.255");

        SsoProperties privateNetworks = new SsoProperties();
        privateNetworks.setBackchannelAllowedCidrs(Arrays.asList("10.10.10.0/24", "172.16.10.0/24",
                "192.168.10.0/24"));
        BackchannelUriValidator privateValidator = new BackchannelUriValidator(privateNetworks);
        assertTrue(privateValidator.isAllowed("http://10.10.10.10/logout", "http"));
        assertTrue(privateValidator.isAllowed("http://172.16.10.10/logout", "http"));
        assertTrue(privateValidator.isAllowed("http://192.168.10.10/logout", "http"));
    }

    private void assertSpecialTargetRejected(String address) {
        SsoProperties values = new SsoProperties();
        values.setBackchannelAllowedCidrs(Arrays.asList(address + "/32"));
        assertFalse(new BackchannelUriValidator(values).isAllowed("http://" + address + "/logout", "http"));
    }

    private SsoProperties properties() {
        SsoProperties value = new SsoProperties();
        value.setBackchannelAllowedCidrs(Arrays.asList("192.9.230.0/24"));
        return value;
    }
}
