package com.lessconfig.spring.boot;

import com.lessconfig.LessConfigClient;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

/**
 * Rebinds configuration properties and invalidates refresh-scoped targets after a LessConfig update.
 * Delegates binding, validation and bean lifecycle behavior to Spring Cloud Context.
 * Existing property sources are already current, so this listener does not reload Config Data.
 */
@NullMarked
final class LessConfigSpringCloudRefreshListener implements ApplicationListener<LessConfigChangeEvent>, Ordered {
    /**
     * Logger for bean refresh lifecycle; binding values and exception messages are excluded.
     */
    private static final Logger LOG = LoggerFactory.getLogger(LessConfigSpringCloudRefreshListener.class);
    /**
     * Client identifying events owned by this application context.
     */
    private final LessConfigClient client;
    /**
     * Context whose property sources were updated before notification.
     */
    private final ConfigurableApplicationContext context;
    /**
     * Existing scope that recreates targets on their next access.
     */
    private final RefreshScope refreshScope;

    /**
     * Creates a bridge using existing application resources.
     *
     * @param client       shared client identifying this runtime's events
     * @param context      context receiving Spring Cloud environment change events
     * @param refreshScope scope to invalidate after configuration properties are rebound
     */
    LessConfigSpringCloudRefreshListener(LessConfigClient client, ConfigurableApplicationContext context,
                                         RefreshScope refreshScope) {
        this.client = client;
        this.context = context;
        this.refreshScope = refreshScope;
        LOG.info("Spring Cloud bean refresh enabled for LessConfig");
    }

    /**
     * Publishes the changed property names and refreshes scoped beans for this application's updates.
     * Events from another runtime and batches without property changes are ignored.
     *
     * @param event successful property-source update
     */
    @Override
    public void onApplicationEvent(LessConfigChangeEvent event) {
        if (event.getSource() != client || event.getPropertyNames().isEmpty()) {
            return;
        }
        LOG.debug("Refreshing Spring Cloud configuration beans after {} imported property changes", event.getPropertyNames().size());
        context.publishEvent(new EnvironmentChangeEvent(context, event.getPropertyNames()));
        refreshScope.refreshAll();
        LOG.info("Spring Cloud bean refresh completed for {} changed LessConfig properties", event.getPropertyNames().size());
    }

    /**
     * Runs before ordinary application listeners for LessConfig changes.
     *
     * @return the highest listener precedence
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Keeps refresh dispatch on the publishing thread when a multicaster has an executor.
     *
     * @return false because the bridge must not be deferred past other change listeners
     */
    @Override
    public boolean supportsAsyncExecution() {
        return false;
    }
}
