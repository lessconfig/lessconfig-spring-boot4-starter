package com.lessconfig.spring.boot;

import com.lessconfig.LessConfigClient;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Exposes the LessConfig client used during configuration loading.
 */
@AutoConfiguration
@ConditionalOnBean(LessConfigRuntime.class)
@EnableConfigurationProperties(LessConfigProperties.class)
@NullMarked
public class LessConfigAutoConfiguration {

    /**
     * Creates the auto-configuration exposing an imported configuration's shared client.
     */
    public LessConfigAutoConfiguration() {
    }

    /**
     * Exposes the shared client managed by the runtime.
     *
     * @param runtime resources transferred from the configuration loading phase
     * @return the client used to load and watch imported configurations
     */
    @Bean(destroyMethod = "")
    LessConfigClient lessConfigClient(LessConfigRuntime runtime) {
        return runtime.getClient();
    }
}
