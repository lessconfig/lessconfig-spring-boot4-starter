package com.lessconfig.spring.boot;

import com.lessconfig.Config;
import com.lessconfig.ConfigChange;
import com.lessconfig.LessConfigClient;
import com.lessconfig.LessConfigException;
import com.lessconfig.WatchStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.bootstrap.BootstrapContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the shared SDK client, imported snapshots and refresh notifications for one application.
 */
@NullMarked
final class LessConfigRuntime implements AutoCloseable {
    /**
     * Logger for import lifecycle, applied updates and watcher health; configuration values are never included.
     */
    private static final Logger LOG = LoggerFactory.getLogger(LessConfigRuntime.class);
    /**
     * Internal bean name used to transfer ownership to Spring.
     */
    private static final String BEAN_NAME = LessConfigRuntime.class.getName();
    /**
     * Connection settings captured before any remote properties are loaded.
     */
    private final LessConfigProperties properties;
    /**
     * Single cache and watcher shared by every imported file.
     */
    private final LessConfigClient client;
    /**
     * Parser bound to the application's startup profiles.
     */
    private final LessConfigDocumentParser parser;
    /**
     * Protects subscriptions, startup notification ordering and lifecycle state.
     */
    private final Object monitor = new Object();
    /**
     * Successful imports indexed by location, independent of the optional prefix.
     */
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /**
     * Pending batches; an empty batch requests reconciliation after checkpoint recovery.
     */
    private final Deque<List<ConfigChange>> pending = new ArrayDeque<>();
    /**
     * Context receiving Spring events after its refresh completes.
     */
    private @Nullable ConfigurableApplicationContext context;
    /**
     * Whether notification delivery is enabled.
     */
    private boolean active;
    /**
     * Whether one thread is already draining notifications.
     */
    private boolean dispatching;
    /**
     * Whether this runtime has released its client.
     */
    private boolean closed;
    /**
     * Whether checkpoint recovery can replace SDK snapshots without individual change records.
     */
    private boolean resynchronizing;
    /**
     * Last observed watcher state, used to avoid repeated warnings during the same outage.
     */
    private WatchStatus observedStatus = WatchStatus.IDLE;

    /**
     * Creates a client and registers the bridge before any reads can start watching.
     *
     * @param properties validated connection settings
     * @param profiles   startup profiles accepted by Boot
     */
    LessConfigRuntime(LessConfigProperties properties, List<String> profiles) {
        this(properties, profiles, LessConfigClient.builder()
                .endpoint(properties.endpoint()).token(properties.token()).build());
    }

    /**
     * Creates a runtime around an independently owned client.
     *
     * @param properties validated connection settings
     * @param profiles   startup profiles accepted by Boot
     * @param client     client whose lifecycle is transferred to this runtime
     */
    LessConfigRuntime(LessConfigProperties properties, List<String> profiles, LessConfigClient client) {
        this.properties = properties;
        this.client = client;
        this.parser = new LessConfigDocumentParser(profiles);
        client.addConfigListener(this::onChange);
        client.addStatusListener(this::onStatus);
    }

    /**
     * Returns shared SDK client owned by this runtime.
     *
     * @return shared SDK client owned by this runtime
     */
    public LessConfigClient getClient() {
        return client;
    }

    /**
     * Obtains the runtime required by configuration loading and ownership transfer.
     * A registered supplier is allowed to return null by the BootstrapContext contract.
     *
     * @param bootstrap registry containing the runtime registration
     * @return initialized, non-null runtime
     * @throws IllegalStateException if the runtime is unregistered or its supplier returns null
     */
    static LessConfigRuntime getRequired(BootstrapContext bootstrap) {
        LessConfigRuntime runtime = bootstrap.get(LessConfigRuntime.class);
        if (runtime == null) {
            throw new IllegalStateException("The registered LessConfig runtime supplier returned null");
        }
        return runtime;
    }

    /**
     * Prevents imports discovered later from silently changing an established connection.
     *
     * @param candidate settings visible when resolving another import
     * @throws IllegalStateException if a different endpoint or token is requested
     */
    void verifyConnection(LessConfigProperties candidate) {
        if (!properties.equals(candidate)) {
            throw new IllegalStateException("All LessConfig imports in an application must use the same connection settings");
        }
    }

    /**
     * Loads or reuses one source while keeping listener registration and initialization ordered.
     * An optional missing key retains an empty source so a future creation can become visible.
     *
     * @param resource file to import
     * @return stable property source for the file
     * @throws IOException                         if parsing fails
     * @throws ConfigDataResourceNotFoundException if a required configuration is missing
     */
    LessConfigPropertySource load(LessConfigConfigDataResource resource) throws IOException {
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("LessConfig runtime is closed");
            }
            Entry existing = entries.get(resource.toString());
            if (existing != null) {
                if (!existing.present && !resource.isOptionalImport()) {
                    throw new ConfigDataResourceNotFoundException(resource);
                }
                return existing.source;
            }
            Config config = client.getConfig(resource.getProject(), resource.getEnvironment(), resource.getKey());
            if (config == null && !resource.isOptionalImport()) {
                throw new ConfigDataResourceNotFoundException(resource);
            }
            LessConfigPropertySource source = new LessConfigPropertySource(resource.toString());
            source.replace(config == null ? Map.of() : parser.parse(resource, config.getValue()));
            entries.put(resource.toString(), new Entry(resource, source, config != null));
            return source;
        }
    }

    /**
     * Transfers the runtime to the application and installs its destruction callback.
     *
     * @param applicationContext prepared but not yet refreshed application context
     */
    void attach(ConfigurableApplicationContext applicationContext) {
        RootBeanDefinition definition = new RootBeanDefinition(LessConfigRuntime.class, () -> this);
        definition.setDestroyMethodName("close");
        ((BeanDefinitionRegistry) applicationContext.getBeanFactory()).registerBeanDefinition(BEAN_NAME, definition);
        applicationContext.addApplicationListener(event -> {
            if (event instanceof ContextRefreshedEvent refreshed && refreshed.getApplicationContext() == applicationContext) {
                activate(applicationContext);
            }
        });
    }

    /**
     * Enables publication after application listeners and configuration beans have been created.
     *
     * @param applicationContext context receiving change events
     */
    void activate(ConfigurableApplicationContext applicationContext) {
        synchronized (monitor) {
            if (closed || active) {
                return;
            }
            context = applicationContext;
            active = true;
            LOG.info("LessConfig runtime ready: {} imports, watcher status={}", entries.size(), observedStatus);
            for (Entry entry : entries.values()) {
                if (!entry.present) {
                    LOG.info("Optional LessConfig configuration {} is absent; watching for creation", entry.resource);
                }
            }
            if (observedStatus == WatchStatus.RETRYING || observedStatus == WatchStatus.STOPPED) {
                logWatchStatus(WatchStatus.IDLE, observedStatus, client.getWatchError());
            }
        }
        drain();
    }

    /**
     * Queues original server records after the SDK has refreshed its own cache.
     *
     * @param changes original server batch
     */
    private void onChange(List<ConfigChange> changes) {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            pending.addLast(List.copyOf(changes));
            if (active) {
                LOG.debug("Received LessConfig change batch: {} server records", changes.size());
            }
        }
        drain();
    }

    /**
     * Reconciles snapshots after SDK checkpoint recovery or removal of missing ancestors.
     * A reset can refresh the SDK cache without retaining individual server change records.
     *
     * @param status current SDK watcher state
     * @param error  watcher failure, or null for successful transitions
     */
    private void onStatus(WatchStatus status, @Nullable LessConfigException error) {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            WatchStatus previous = observedStatus;
            observedStatus = status;
            if (active) {
                logWatchStatus(previous, status, error);
            }
            if (error != null && ("revision_expired".equals(error.getCode())
                    || "revision_ahead".equals(error.getCode()) || "project_not_found".equals(error.getCode())
                    || "environment_not_found".equals(error.getCode()))) {
                resynchronizing = true;
            }
            if (!resynchronizing || (status != WatchStatus.WATCHING && status != WatchStatus.IDLE)) {
                return;
            }
            resynchronizing = false;
            pending.addLast(List.of());
        }
        drain();
    }

    /**
     * Reports watcher state transitions without logging server messages, credentials or response bodies.
     * The caller holds the runtime monitor; repeated retries are visible only at DEBUG level.
     *
     * @param previous last observed watcher state
     * @param status   current watcher state
     * @param error    transport or protocol failure, or null for a healthy transition
     */
    private void logWatchStatus(WatchStatus previous, WatchStatus status, @Nullable LessConfigException error) {
        int httpStatus = error == null ? 0 : error.getStatusCode();
        Throwable cause = error == null ? null : error.getCause();
        String failure = cause != null ? cause.getClass().getSimpleName()
                : error != null ? error.getClass().getSimpleName() : "unknown";
        if (status == WatchStatus.RETRYING) {
            if (previous != status) {
                LOG.warn("LessConfig watcher is retrying (HTTP {}, failure={}); using cached configuration", httpStatus, failure);
            } else {
                LOG.debug("LessConfig watch retry failed (HTTP {}, failure={})", httpStatus, failure);
            }
        } else if (status == WatchStatus.STOPPED && previous != status) {
            LOG.error("LessConfig watcher stopped (HTTP {}, failure={}); automatic updates are disabled", httpStatus, failure);
        } else if (previous == WatchStatus.RETRYING && (status == WatchStatus.WATCHING || status == WatchStatus.IDLE)) {
            LOG.info("LessConfig watcher recovered: status={}", status);
        } else if (previous != status) {
            LOG.debug("LessConfig watcher status changed: {} -> {}", previous, status);
        }
    }

    /**
     * Serializes parsing and publication, including changes received during startup.
     */
    private void drain() {
        synchronized (monitor) {
            if (!active || closed || dispatching) {
                return;
            }
            dispatching = true;
        }
        while (true) {
            LessConfigChangeEvent event;
            ConfigurableApplicationContext publisher;
            synchronized (monitor) {
                List<ConfigChange> changes = pending.pollFirst();
                if (closed || changes == null) {
                    dispatching = false;
                    return;
                }
                event = refresh(changes);
                publisher = context;
            }
            if (event != null && publisher != null) {
                try {
                    LOG.debug("Publishing LessConfig change notification: {} server records, {} changed properties",
                            event.getChanges().size(), event.getPropertyNames().size());
                    publisher.publishEvent(event);
                } catch (RuntimeException exception) {
                    LOG.warn("LessConfig Spring change listener failed ({}); imported property sources remain updated",
                            exception.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Replaces successfully parsed snapshots before constructing their notification.
     * The caller holds the runtime monitor; malformed updates leave the prior snapshot intact.
     *
     * @param changes original SDK batch
     * @return event for successfully refreshed imports, or null if none could be refreshed
     */
    private @Nullable LessConfigChangeEvent refresh(List<ConfigChange> changes) {
        if (changes.isEmpty()) {
            LOG.debug("Reconciling {} LessConfig imports after watcher recovery", entries.size());
        }
        Set<String> names = new LinkedHashSet<>();
        Set<ConfigChange> accepted = new LinkedHashSet<>();
        for (Entry entry : entries.values()) {
            List<ConfigChange> matching = changes.stream().filter(entry.resource::isAffectedBy).toList();
            if (!changes.isEmpty() && matching.isEmpty()) {
                continue;
            }
            try {
                Config config = client.getConfig(entry.resource.getProject(), entry.resource.getEnvironment(), entry.resource.getKey());
                Map<String, Object> values = config == null ? Map.of() : parser.parse(entry.resource, config.getValue());
                Set<String> changed = entry.source.replace(values);
                names.addAll(changed);
                if (!changed.isEmpty() || entry.present != (config != null)) {
                    LOG.info("Refreshed LessConfig configuration {}: {} properties changed, present={}",
                            entry.resource, changed.size(), config != null);
                } else {
                    LOG.debug("Imported LessConfig properties unchanged for {}", entry.resource);
                }
                entry.present = config != null;
                accepted.addAll(matching);
            } catch (IOException | RuntimeException exception) {
                LOG.warn("Cannot refresh {} ({}); retaining the last successful properties",
                        entry.resource, exception.getClass().getSimpleName());
            }
        }
        List<ConfigChange> applied = new ArrayList<>();
        changes.stream().filter(accepted::contains).forEach(applied::add);
        return applied.isEmpty() && names.isEmpty() ? null : new LessConfigChangeEvent(client, applied, names);
    }

    /**
     * Stops watching and releases resources. Repeated calls are harmless.
     */
    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            pending.clear();
            context = null;
        }
        client.close();
        LOG.debug("LessConfig runtime closed; shared client and watcher released");
    }

    /**
     * An imported source and its current existence state, guarded by the runtime monitor.
     */
    private static final class Entry {
        /**
         * File identity.
         */
        private final LessConfigConfigDataResource resource;
        /**
         * Source retained by the Spring Environment.
         */
        private final LessConfigPropertySource source;
        /**
         * Whether the most recent successful SDK snapshot contained this file.
         */
        private boolean present;

        /**
         * Captures a successfully initialized import.
         *
         * @param resource remote file identity
         * @param source   stable property source
         * @param present  whether the initial configuration exists
         */
        private Entry(LessConfigConfigDataResource resource, LessConfigPropertySource source, boolean present) {
            this.resource = resource;
            this.source = source;
            this.present = present;
        }
    }
}
