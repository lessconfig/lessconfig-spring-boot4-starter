package com.lessconfig.spring.boot;

import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Enables bean refresh when the application supplies Spring Cloud Context.
 * Uses the application's existing rebinder and refresh scope after imported properties change.
 * No Spring Cloud classes are required when this auto-configuration does not match.
 */
@AutoConfiguration(after = LessConfigAutoConfiguration.class, afterName = {
        "org.springframework.cloud.autoconfigure.RefreshAutoConfiguration",
        "org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration"})
@ConditionalOnClass({EnvironmentChangeEvent.class, ConfigurationPropertiesRebinder.class, RefreshScope.class})
@ConditionalOnBean(value = {LessConfigRuntime.class, ConfigurationPropertiesRebinder.class, RefreshScope.class},
        search = SearchStrategy.CURRENT)
@ConditionalOnProperty(name = "spring.cloud.refresh.enabled", matchIfMissing = true)
@NullMarked
public class LessConfigSpringCloudAutoConfiguration {

    /**
     * Creates the optional Spring Cloud integration.
     */
    public LessConfigSpringCloudAutoConfiguration() {
    }

    /**
     * Connects successful LessConfig updates to Spring Cloud's bean refresh mechanisms.
     *
     * @param runtime      runtime owning this application's shared client
     * @param context      application receiving configuration change events
     * @param refreshScope existing Spring Cloud scope whose targets should be recreated
     * @return listener for this application's imported configuration
     */
    @Bean
    @ConditionalOnMissingBean
    LessConfigSpringCloudRefreshListener lessConfigSpringCloudRefreshListener(
            LessConfigRuntime runtime, ConfigurableApplicationContext context, RefreshScope refreshScope) {
        return new LessConfigSpringCloudRefreshListener(runtime.getClient(), context, refreshScope);
    }
}
