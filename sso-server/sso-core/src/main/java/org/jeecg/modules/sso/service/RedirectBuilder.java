package org.jeecg.modules.sso.service;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class RedirectBuilder {
    public String build(String registeredUri, String code, String state) {
        return UriComponentsBuilder.fromHttpUrl(registeredUri)
                .queryParam("code", code).queryParam("state", state).build().encode().toUriString();
    }
}
