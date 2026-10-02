package com.lily.builder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("lily.remediate")
public record RemediateProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String jevApiKey) {
}
