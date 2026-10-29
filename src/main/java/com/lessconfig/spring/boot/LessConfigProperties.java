package com.lessconfig.spring.boot;

import org.jspecify.annotations.NullMarked;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Connection settings used to load configuration from LessConfig.
 *
 * @param endpoint API root URL without a version, for example {@code https://example.com/api}
 * @param token    API token used for Bearer authentication
 */
@ConfigurationProperties(prefix = "lessconfig")
@NullMarked
public record LessConfigProperties(String endpoint, String token) {

    /**
     * Creates validated connection settings.
     *
     * @param endpoint API root URL without a version; the SDK adds the operation's version
     * @param token    API authentication token
     * @throws IllegalArgumentException if either argument is blank
     */
    public LessConfigProperties {
        Assert.hasText(endpoint, "lessconfig.endpoint must not be blank");
        Assert.hasText(token, "lessconfig.token must not be blank");
    }

    /**
     * Returns a diagnostic representation with the token redacted.
     *
     * @return connection settings without the authentication token
     */
    @Override
    public String toString() {
        return "LessConfigProperties[endpoint=" + endpoint + ", token=******]";
    }
}
