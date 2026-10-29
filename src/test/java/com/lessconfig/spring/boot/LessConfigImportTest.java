package com.lessconfig.spring.boot;

import com.lessconfig.Config;
import com.lessconfig.LessConfigClient;
import com.lessconfig.LessConfigException;
import com.lessconfig.WatchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Starts real Spring applications against a loopback server to verify the complete import lifecycle.
 */
class LessConfigImportTest {
    /**
     * Temporary local application and bootstrap configuration files.
     */
    @TempDir
    Path directory;

    /**
     * Imports run before value injection and configuration-properties binding.
     */
    @Test
    void loadsYamlAndPropertiesBeforeBeansAndExposesSharedClient() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "sample:\n  title: 中文\n  timeout: 2s\n  servers: [one, two]\n", "yaml", "updated");
            server.put("extra.properties", "sample.title=properties title\nsample.extra=更多\n", "properties", "created");
            List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
            try (ConfigurableApplicationContext context = application(server, events, BindingApplication.class).run(
                    imports("application.yml", "extra.properties"))) {
                assertThat(context.getBean("title", String.class)).isEqualTo("properties title");
                SampleProperties bound = context.getBean(SampleProperties.class);
                assertThat(bound.title()).isEqualTo("properties title");
                assertThat(bound.timeout()).isEqualTo(Duration.ofSeconds(2));
                assertThat(bound.servers()).containsExactly("one", "two");
                assertThat(context.getEnvironment().getProperty("sample.extra")).isEqualTo("更多");
                LessConfigClient client = context.getBean(LessConfigClient.class);
                assertThat(client).isSameAs(context.getBean(LessConfigRuntime.class).getClient());
                assertThat(context.getBean(LessConfigProperties.class).endpoint()).isEqualTo(server.endpoint());
                assertThat(server.requests).extracting(request -> request.path).contains(
                        "/api/v1/watch",
                        "/api/v1/projects/payment/environments/prod/configurations/application.yml",
                        "/api/v1/projects/payment/environments/prod/configurations/extra.properties");
                int reads = server.reads.get();
                assertThat(client.getConfig("payment", "prod", "extra.properties"))
                        .isNotNull().extracting(Config::getValue).asString().contains("更多");
                assertThat(server.reads.get()).isEqualTo(reads);
                assertThat(events).isEmpty();
            }
        }
    }

    /**
     * Later imports override earlier imports while command-line arguments retain higher priority.
     */
    @Test
    void preservesImportAndCommandLinePrecedence() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("first.yml", "value: first\ncommand: remote\n", "yaml", "created");
            server.put("second.properties", "value=second\ncommand=remote-second\n", "properties", "created");
            Path local = directory.resolve("application.yml");
            Files.writeString(local, "value: local\nspring.config.import:\n"
                    + "  - lessconfig:payment/prod/first.yml\n  - lessconfig:payment/prod/second.properties\n");
            try (ConfigurableApplicationContext context = application(server, new ArrayList<>(), EmptyApplication.class).run(
                    "--spring.config.location=" + local.toUri(), "--command=cli")) {
                assertThat(context.getEnvironment().getProperty("value")).isEqualTo("second");
                assertThat(context.getEnvironment().getProperty("command")).isEqualTo("cli");
            }
        }
    }

    /**
     * Explicitly importing bootstrap.yml makes its connection settings available to the resolver.
     */
    @Test
    void readsConnectionAndImportsFromExplicitBootstrapFile() throws Exception {
        try (TestServer server = new TestServer()) {
            Files.writeString(directory.resolve("bootstrap.yml"), "lessconfig:\n  endpoint: " + server.endpoint()
                    + "\n  token: test-token\nspring.config.import: lessconfig:payment/prod/application.yml\n");
            Path local = directory.resolve("application.yml");
            Files.writeString(local, "spring.config.import: bootstrap.yml\n");
            SpringApplication application = application(null, new ArrayList<>(), EmptyApplication.class);
            try (ConfigurableApplicationContext context = application.run("--spring.config.location=" + local.toUri())) {
                assertThat(context.getEnvironment().getProperty("port", Integer.class)).isEqualTo(8080);
            }
        }
    }

    /**
     * Importing the same path twice must not create another subscription or property source.
     */
    @Test
    void deduplicatesImportsAndClosesTheClient() throws Exception {
        try (TestServer server = new TestServer()) {
            LessConfigClient client;
            try (ConfigurableApplicationContext context = application(server, new ArrayList<>(), EmptyApplication.class)
                    .run(imports("application.yml", "application.yml"))) {
                client = context.getBean(LessConfigClient.class);
                assertThat(server.initializations.get()).isEqualTo(1);
                assertThat(server.reads.get()).isEqualTo(1);
                assertThat(context.getEnvironment().getPropertySources().stream()
                        .filter(LessConfigPropertySource.class::isInstance).count()).isEqualTo(1);
            }
            assertThat(client.getStatus()).isEqualTo(WatchStatus.CLOSED);
        }
    }

    /**
     * The starter remains inactive when no LessConfig location has been imported.
     */
    @Test
    void doesNotRequireCredentialsWithoutImports() {
        try (ConfigurableApplicationContext context = application(null, new ArrayList<>(), EmptyApplication.class).run()) {
            assertThat(context.getBeansOfType(LessConfigClient.class)).isEmpty();
            assertThat(context.getBeansOfType(LessConfigProperties.class)).isEmpty();
        }
    }

    /**
     * Every successful refresh replaces removed keys and publishes after Environment is updated.
     */
    @Test
    void refreshesSnapshotsAndPublishesOriginalChangesAfterReplacement() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "value: before\nremoved: old\n", "yaml", "updated");
            List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
            SpringApplication app = application(server, events, EmptyApplication.class);
            AtomicReference<Environment> environment = new AtomicReference<>();
            List<String> observed = new CopyOnWriteArrayList<>();
            app.addListeners(event -> {
                if (event instanceof LessConfigChangeEvent) {
                    observed.add(requireNonNull(environment.get(), "Environment is not available")
                            .getRequiredProperty("value"));
                }
            });
            try (ConfigurableApplicationContext context = app.run(imports("application.yml"), "--removed=local")) {
                environment.set(context.getEnvironment());
                server.put("application.yml", "value: after\nadded: new\n", "yaml", "updated");
                TestServer.await(() -> !observed.isEmpty());
                assertThat(observed).containsExactly("after");
                assertThat(context.getEnvironment().getProperty("removed")).isEqualTo("local");
                assertThat(events.get(0).getPropertyNames()).containsExactlyInAnyOrder("value", "removed", "added");
                assertThat(events.get(0).getChanges()).hasSize(1);
                assertThat(events.get(0).getChanges().get(0).getType()).isEqualTo("updated");
                assertThatThrownBy(() -> events.get(0).getPropertyNames().clear()).isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(() -> events.get(0).getChanges().clear()).isInstanceOf(UnsupportedOperationException.class);
                assertThat(context.getBean(LessConfigClient.class).getConfig("payment", "prod", "application.yml"))
                        .isNotNull().extracting(Config::getValue).asString().contains("after");
            }
        }
    }

    /**
     * Deleting and recreating a file clears stale values and retains its watch subscription.
     */
    @Test
    void followsDeletionAndRecreation() throws Exception {
        try (TestServer server = new TestServer();
             ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>(), EmptyApplication.class)
                     .run(imports("application.yml"))) {
            server.delete("application.yml");
            TestServer.await(() -> context.getEnvironment().getProperty("port") == null);
            server.put("application.yml", "port: 9090\n", "yaml", "created");
            TestServer.await(() -> "9090".equals(context.getEnvironment().getProperty("port")));
        }
    }

    /**
     * Optional absent keys keep an empty source that becomes populated on creation.
     */
    @Test
    void watchesOptionalMissingFiles() throws Exception {
        try (TestServer server = new TestServer();
             ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>(), EmptyApplication.class)
                     .run("--spring.config.import=optional:lessconfig:payment/prod/later.properties")) {
            assertThat(context.getEnvironment().getProperty("later")).isNull();
            server.put("later.properties", "later=ready", "properties", "created");
            TestServer.await(() -> "ready".equals(context.getEnvironment().getProperty("later")));
        }
    }

    /**
     * A required occurrence of a previously optional missing import still fails startup.
     */
    @Test
    void requiredImportIsNotSatisfiedByAnOptionalMissingSource() throws Exception {
        try (TestServer server = new TestServer()) {
            assertThatThrownBy(() -> application(server, new ArrayList<>(), EmptyApplication.class).run(
                    "--spring.config.import=optional:lessconfig:payment/prod/missing.yml,lessconfig:payment/prod/missing.yml"))
                    .hasStackTraceContaining("ConfigDataResourceNotFoundException");
        }
    }

    /**
     * Optional imports cannot conceal authentication or malformed-content failures.
     */
    @Test
    void optionalOnlySuppressesMissingResources() throws Exception {
        try (TestServer server = new TestServer()) {
            server.fixedResponse = TestServer.error(401, "unauthorized");
            assertThatExceptionOfType(LessConfigException.class).isThrownBy(() -> application(server, new ArrayList<>(), EmptyApplication.class)
                            .run("--spring.config.import=optional:lessconfig:payment/prod/application.yml"))
                    .satisfies(error -> assertThat(error.getStatusCode()).isEqualTo(401));
            server.fixedResponse = null;
            server.put("application.yml", "broken: [", "yaml", "updated");
            assertThatThrownBy(() -> application(server, new ArrayList<>(), EmptyApplication.class)
                    .run("--spring.config.import=optional:lessconfig:payment/prod/application.yml"))
                    .isInstanceOf(org.yaml.snakeyaml.error.YAMLException.class);
        }
    }

    /**
     * Invalid imports fail validation before opening a connection.
     *
     * @param path malformed or unsupported remote location
     */
    @ParameterizedTest
    @ValueSource(strings = {"payment/prod", "payment//app.yml", "payment/prod/../app.yml", "payment/prod/file.json"})
    void rejectsInvalidLocations(String path) throws Exception {
        try (TestServer server = new TestServer()) {
            assertThatThrownBy(() -> application(server, new ArrayList<>(), EmptyApplication.class)
                    .run("--spring.config.import=lessconfig:" + path)).isInstanceOf(IllegalArgumentException.class);
            assertThat(server.requests).isEmpty();
        }
    }

    /**
     * A rejected update retains the last parsed snapshot and a subsequent valid update recovers.
     */
    @Test
    void retainsValidSnapshotAfterMalformedUpdate() throws Exception {
        try (TestServer server = new TestServer()) {
            List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
            try (ConfigurableApplicationContext context = application(server, events, EmptyApplication.class)
                    .run(imports("application.yml"))) {
                LessConfigClient client = context.getBean(LessConfigClient.class);
                CountDownLatch handled = new CountDownLatch(1);
                client.addConfigListener(changes -> handled.countDown());
                server.put("application.yml", "broken: [", "yaml", "updated");
                assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(context.getEnvironment().getProperty("port")).isEqualTo("8080");
                assertThat(events).isEmpty();
                server.put("application.yml", "port: 9999", "yaml", "updated");
                TestServer.await(() -> "9999".equals(context.getEnvironment().getProperty("port")));
            }
        }
    }

    /**
     * Network failures preserve cached values while the SDK retries and later resumes refresh.
     */
    @Test
    void recoversAfterBackgroundNetworkFailure() throws Exception {
        try (TestServer server = new TestServer();
             ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>(), EmptyApplication.class)
                     .run(imports("application.yml"))) {
            server.watchFailure = TestServer.error(503, "temporarily_unavailable");
            LessConfigClient client = context.getBean(LessConfigClient.class);
            TestServer.await(() -> client.getStatus() == WatchStatus.RETRYING);
            assertThat(context.getEnvironment().getProperty("port")).isEqualTo("8080");
            server.put("application.yml", "port: 9998", "yaml", "updated");
            server.watchFailure = null;
            TestServer.await(() -> "9998".equals(context.getEnvironment().getProperty("port")));
        }
    }

    /**
     * Refresh uses the same expanded profile set as startup, including newly added documents.
     */
    @Test
    void selectsProfileDocumentsOnStartupAndRefresh() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", """
                    value: base
                    ---
                    spring.config.activate.on-profile: prod
                    value: production
                    obsolete: old
                    ---
                    spring.config.activate.on-profile: dev
                    value: development
                    """, "yaml", "updated");
            try (ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>(), EmptyApplication.class)
                    .run(imports("application.yml"), "--spring.profiles.active=release", "--spring.profiles.group.release=prod")) {
                assertThat(context.getEnvironment().getProperty("value")).isEqualTo("production");
                server.put("application.yml", """
                        value: base
                        ---
                        spring.config.activate.on-profile: "prod & !dev"
                        value: production-new
                        ---
                        spring.config.activate.on-profile: dev
                        value: wrong
                        ---
                        extra: appended
                        """, "yaml", "updated");
                TestServer.await(() -> "production-new".equals(context.getEnvironment().getProperty("value")));
                assertThat(context.getEnvironment().getProperty("obsolete")).isNull();
                assertThat(context.getEnvironment().getProperty("extra")).isEqualTo("appended");
            }
        }
    }

    /**
     * Local profile activation controls whether a remote import is resolved at all.
     */
    @Test
    void ignoresImportsInInactiveLocalDocuments() throws Exception {
        try (TestServer server = new TestServer()) {
            Path local = directory.resolve("application.yml");
            Files.writeString(local, "spring.config.activate.on-profile: dev\n"
                    + "spring.config.import: lessconfig:payment/prod/application.yml\n");
            try (ConfigurableApplicationContext context = application(server, new ArrayList<>(), EmptyApplication.class)
                    .run("--spring.config.location=" + local.toUri(), "--spring.profiles.active=prod")) {
                assertThat(context.getBeansOfType(LessConfigClient.class)).isEmpty();
                assertThat(server.requests).isEmpty();
            }
        }
    }

    /**
     * Resources are closed even when configuration loading fails before a context exists.
     */
    @Test
    void closesClientWhenEnvironmentPreparationFails() throws Exception {
        try (TestServer server = new TestServer()) {
            SpringApplication application = application(server, new ArrayList<>(), EmptyApplication.class);
            AtomicReference<org.springframework.boot.bootstrap.ConfigurableBootstrapContext> bootstrap = new AtomicReference<>();
            application.addBootstrapRegistryInitializer(registry -> bootstrap.set(
                    (org.springframework.boot.bootstrap.ConfigurableBootstrapContext) registry));
            assertThatThrownBy(() -> application.run(imports("missing.yml")))
                    .hasStackTraceContaining("ConfigDataResourceNotFoundException");
            assertThat(LessConfigRuntime.getRequired(requireNonNull(bootstrap.get(), "Bootstrap context was not initialized"))
                    .getClient().getStatus()).isEqualTo(WatchStatus.CLOSED);
        }
    }

    /**
     * Bean creation failures also release the client transferred from the bootstrap registry.
     */
    @Test
    void closesClientWhenBeanCreationFails() throws Exception {
        try (TestServer server = new TestServer()) {
            SpringApplication application = application(server, new ArrayList<>(), FailingApplication.class);
            AtomicReference<org.springframework.boot.bootstrap.ConfigurableBootstrapContext> bootstrap = new AtomicReference<>();
            application.addBootstrapRegistryInitializer(registry -> bootstrap.set(
                    (org.springframework.boot.bootstrap.ConfigurableBootstrapContext) registry));
            assertThatThrownBy(() -> application.run(imports("application.yml")))
                    .hasStackTraceContaining("Deliberate bean failure");
            assertThat(LessConfigRuntime.getRequired(requireNonNull(bootstrap.get(), "Bootstrap context was not initialized"))
                    .getClient().getStatus()).isEqualTo(WatchStatus.CLOSED);
        }
    }

    /**
     * A registered null supplier fails with a diagnostic error before any remote requests are made.
     */
    @Test
    void rejectsNullRuntimeRegistrationWithoutMaskingTheStartupFailure() throws Exception {
        try (TestServer server = new TestServer()) {
            SpringApplication application = application(server, new ArrayList<>(), EmptyApplication.class);
            application.addBootstrapRegistryInitializer(registry -> registry.register(LessConfigRuntime.class, ignored -> null));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> application.run(imports("application.yml")))
                    .withMessage("The registered LessConfig runtime supplier returned null");
            assertThat(server.requests).isEmpty();
        }
    }

    /**
     * Failed-startup cleanup tolerates an uninitialized registry, missing registration and null supplier.
     */
    @Test
    void toleratesAbsentRuntimeDuringFailureCleanup() {
        LessConfigBootstrapListener listener = new LessConfigBootstrapListener(new SpringApplication(), new String[0]);
        IllegalStateException failure = new IllegalStateException("Original startup failure");
        listener.failed(null, failure);
        DefaultBootstrapContext bootstrap = new DefaultBootstrapContext();
        listener.starting(bootstrap);
        listener.failed(null, failure);
        bootstrap.register(LessConfigRuntime.class, ignored -> null);
        listener.failed(null, failure);
    }

    /**
     * Application instances do not share clients, subscriptions or mutable property sources.
     */
    @Test
    void isolatesTwoApplications() throws Exception {
        try (TestServer first = new TestServer(); TestServer second = new TestServer();
             ConfigurableApplicationContext one = application(first, new CopyOnWriteArrayList<>(), EmptyApplication.class).run(imports("application.yml"));
             ConfigurableApplicationContext two = application(second, new CopyOnWriteArrayList<>(), EmptyApplication.class).run(imports("application.yml"))) {
            assertThat(one.getBean(LessConfigClient.class)).isNotSameAs(two.getBean(LessConfigClient.class));
            first.put("application.yml", "port: 10001", "yaml", "updated");
            TestServer.await(() -> "10001".equals(one.getEnvironment().getProperty("port")));
            assertThat(two.getEnvironment().getProperty("port")).isEqualTo("8080");
        }
    }

    /**
     * Changes received between initial loading and context refresh are replayed without losing updates.
     */
    @Test
    void preservesChangesReceivedBeforeContextRefresh() throws Exception {
        try (TestServer server = new TestServer()) {
            List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
            SpringApplication application = application(server, events, EmptyApplication.class);
            AtomicReference<org.springframework.boot.bootstrap.ConfigurableBootstrapContext> bootstrap = new AtomicReference<>();
            application.addBootstrapRegistryInitializer(registry -> bootstrap.set(
                    (org.springframework.boot.bootstrap.ConfigurableBootstrapContext) registry));
            application.addInitializers(context -> {
                CountDownLatch delivered = new CountDownLatch(1);
                LessConfigRuntime.getRequired(requireNonNull(bootstrap.get(), "Bootstrap context was not initialized"))
                        .getClient().addConfigListener(changes -> delivered.countDown());
                server.put("application.yml", "port: 12345", "yaml", "updated");
                try {
                    assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                assertThat(events).isEmpty();
            });
            try (ConfigurableApplicationContext context = application.run(imports("application.yml"))) {
                assertThat(context.getEnvironment().getProperty("port")).isEqualTo("12345");
                assertThat(events).hasSize(1);
                assertThat(events.get(0).getPropertyNames()).contains("port");
                assertThat(events.get(0).getChanges()).hasSize(1);
                assertThat(context.getBean(ChangeObserver.class).events).hasSize(1);
            }
        }
    }

    /**
     * Recovering an expired watch checkpoint reconciles caches even without individual server records.
     */
    @Test
    void reconcilesSnapshotsAfterCheckpointRecovery() throws Exception {
        try (TestServer server = new TestServer()) {
            List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
            try (ConfigurableApplicationContext context = application(server, events, EmptyApplication.class)
                    .run(imports("application.yml"))) {
                LessConfigClient client = context.getBean(LessConfigClient.class);
                server.watchFailure = TestServer.error(410, "revision_expired");
                TestServer.await(() -> client.getStatus() == WatchStatus.RETRYING);
                server.put("application.yml", "port: 12346", "yaml", "updated");
                server.watchFailure = null;
                TestServer.await(() -> !events.isEmpty());
                assertThat(context.getEnvironment().getProperty("port")).isEqualTo("12346");
                assertThat(events.get(0).getChanges()).isEmpty();
                assertThat(events.get(0).getPropertyNames()).containsExactly("port");
            }
        }
    }

    /**
     * Environment deletion removes every imported property in the affected scope.
     */
    @Test
    void removesPropertiesAfterAncestorDeletion() throws Exception {
        try (TestServer server = new TestServer();
             ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>(), EmptyApplication.class)
                     .run(imports("application.yml"))) {
            server.deleteAncestor(false);
            TestServer.await(() -> context.getEnvironment().getProperty("port") == null);
        }
    }

    /**
     * A failing Spring observer cannot stop the watcher or prevent later property replacements.
     */
    @Test
    void listenerFailureDoesNotStopLaterUpdates() throws Exception {
        try (TestServer server = new TestServer()) {
            List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
            SpringApplication application = application(server, events, EmptyApplication.class);
            application.addListeners(event -> {
                if (event instanceof LessConfigChangeEvent) {
                    throw new IllegalStateException("Deliberate observer failure");
                }
            });
            try (ConfigurableApplicationContext context = application.run(imports("application.yml"))) {
                server.put("application.yml", "port: 23456", "yaml", "updated");
                TestServer.await(() -> events.size() == 1);
                server.put("application.yml", "port: 23457", "yaml", "updated");
                TestServer.await(() -> events.size() == 2);
                assertThat(context.getEnvironment().getProperty("port")).isEqualTo("23457");
                assertThat(context.getBean(LessConfigClient.class).getStatus()).isEqualTo(WatchStatus.WATCHING);
            }
        }
    }

    /**
     * Refresh changes Environment reads without silently rebinding already constructed beans.
     */
    @Test
    void leavesExistingValueAndConfigurationPropertiesBeansUnchanged() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "sample.title: before", "yaml", "updated");
            try (ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>(), BindingApplication.class)
                    .run(imports("application.yml"))) {
                server.put("application.yml", "sample.title: after", "yaml", "updated");
                TestServer.await(() -> "after".equals(context.getEnvironment().getProperty("sample.title")));
                assertThat(context.getBean("title", String.class)).isEqualTo("before");
                assertThat(context.getBean(SampleProperties.class).title()).isEqualTo("before");
            }
        }
    }

    /**
     * Two simultaneously changed files are refreshed before a batch event is published.
     */
    @Test
    void refreshesEveryImportedFileInOneServerBatch() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("other.properties", "other=before", "properties", "created");
            List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
            try (ConfigurableApplicationContext context = application(server, events, EmptyApplication.class)
                    .run(imports("application.yml", "other.properties"))) {
                synchronized (server.monitor) {
                    server.put("application.yml", "port: 7777", "yaml", "updated");
                    server.put("other.properties", "other=after", "properties", "updated");
                }
                TestServer.await(() -> !events.isEmpty());
                assertThat(events.get(0).getChanges()).hasSize(2);
                assertThat(events.get(0).getPropertyNames()).containsExactlyInAnyOrder("port", "other");
                assertThat(context.getEnvironment().getProperty("other")).isEqualTo("after");
                assertThat(context.getEnvironment().getProperty("port")).isEqualTo("7777");
            }
        }
    }

    /**
     * A background authorization failure stops watching while retaining previously usable values.
     */
    @Test
    void preservesSnapshotWhenAuthorizationStopsWatching() throws Exception {
        try (TestServer server = new TestServer();
             ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>(), EmptyApplication.class)
                     .run(imports("application.yml"))) {
            server.watchFailure = TestServer.error(403, "forbidden");
            LessConfigClient client = context.getBean(LessConfigClient.class);
            TestServer.await(() -> client.getStatus() == WatchStatus.STOPPED);
            assertThat(context.getEnvironment().getProperty("port")).isEqualTo("8080");
            assertThat(client.getWatchError()).isNotNull().extracting(LessConfigException::getStatusCode).isEqualTo(403);
        }
    }

    /**
     * A local profile document can override values imported by the base application document.
     */
    @Test
    void preservesLocalProfileFilePrecedence() throws Exception {
        try (TestServer server = new TestServer()) {
            Path local = directory.resolve("application.yml");
            Files.writeString(local, "spring.config.import: lessconfig:payment/prod/application.yml\n");
            Files.writeString(directory.resolve("application-prod.yml"), "port: 9191\n");
            try (ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>(), EmptyApplication.class)
                    .run("--spring.config.location=" + local.toUri(), "--spring.profiles.active=prod")) {
                assertThat(context.getEnvironment().getProperty("port")).isEqualTo("9191");
            }
        }
    }

    /**
     * Spring Binder must enumerate newly added names and discard deleted names after refresh.
     */
    @Test
    void bindsCurrentPropertyNamesAfterRefresh() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "sample.old: before", "yaml", "updated");
            try (ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>(), EmptyApplication.class)
                    .run(imports("application.yml"))) {
                Binder binder = Binder.get(context.getEnvironment());
                assertThat(binder.bind("sample", Bindable.mapOf(String.class, String.class)).get())
                        .containsOnlyKeys("old");
                server.put("application.yml", "sample.new: after", "yaml", "updated");
                TestServer.await(() -> "after".equals(context.getEnvironment().getProperty("sample.new")));
                assertThat(binder.bind("sample", Bindable.mapOf(String.class, String.class)).get())
                        .containsExactlyEntriesOf(Map.of("new", "after"));
            }
        }
    }

    /**
     * Connection settings are mandatory for imports and token values are redacted from diagnostics.
     */
    @Test
    void validatesConnectionSettingsWithoutDefaults() {
        assertThatThrownBy(() -> application(null, new ArrayList<>(), EmptyApplication.class)
                .run(imports("application.yml"))).hasMessageContaining("lessconfig.endpoint and lessconfig.token");
        assertThatThrownBy(() -> new LessConfigProperties(" ", "token")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LessConfigProperties("https://example.com/api", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LessConfigProperties("https://example.com/api", "secret-token-value").toString())
                .doesNotContain("secret-token-value");
    }

    /**
     * Creates a quiet non-web application with no external service dependencies.
     *
     * @param server fixture supplying connection defaults, or null to require local configuration
     * @param events destination for successful refresh events
     * @param source application configuration class
     * @return application ready to run
     */
    private SpringApplication application(TestServer server, List<LessConfigChangeEvent> events, Class<?> source) {
        SpringApplication application = new SpringApplication(source);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        Map<String, Object> defaults = new java.util.LinkedHashMap<>();
        defaults.put("spring.main.banner-mode", "off");
        defaults.put("logging.level.root", "OFF");
        defaults.put("spring.config.location", "optional:classpath:/absent-test-application.properties");
        if (server != null) {
            defaults.put("lessconfig.endpoint", server.endpoint());
            defaults.put("lessconfig.token", "test-token");
        }
        application.setDefaultProperties(defaults);
        application.addListeners(event -> {
            if (event instanceof LessConfigChangeEvent change) {
                events.add(change);
            }
        });
        return application;
    }

    /**
     * Builds an explicit import argument for fixture configurations.
     *
     * @param files configuration keys in increasing precedence order
     * @return command-line import property
     */
    private String imports(String... files) {
        return "--spring.config.import=" + String.join(",", java.util.Arrays.stream(files)
                .map(file -> "lessconfig:payment/prod/" + file).toList());
    }

    /**
     * Minimal application that discovers the starter through its published metadata.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class EmptyApplication {
        /**
         * Returns bean-based observer registered before startup changes are replayed.
         *
         * @return bean-based observer registered before startup changes are replayed
         */
        @Bean
        ChangeObserver observer() {
            return new ChangeObserver();
        }
    }

    /**
     * Observes the same events through Spring's bean-level listener annotation.
     */
    static class ChangeObserver {
        /**
         * Successful notifications received after this bean has been initialized.
         */
        final List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();

        /**
         * Collects successful property-source refresh notifications.
         *
         * @param event event published after properties have been replaced
         */
        @EventListener
        void changed(LessConfigChangeEvent event) {
            events.add(event);
        }
    }

    /**
     * Application proving imports are available during normal bean construction.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(SampleProperties.class)
    static class BindingApplication {
        /**
         * Captures a value during bean creation.
         *
         * @param title remotely supplied title
         * @return injected title
         */
        @Bean
        String title(@Value("${sample.title}") String title) {
            return title;
        }
    }

    /**
     * Application with a deliberate failure after client injection.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class FailingApplication {
        /**
         * Fails initialization after requiring the SDK bean.
         *
         * @param client client proving runtime ownership has transferred
         * @return bean that fails on initialization
         */
        @Bean
        InitializingBean failure(LessConfigClient client) {
            return () -> {
                throw new IllegalStateException("Deliberate bean failure");
            };
        }
    }

    /**
     * Properties bound from two remote formats.
     *
     * @param title   display title
     * @param timeout duration parsed by Spring's conversion service
     * @param servers flattened YAML list
     */
    @ConfigurationProperties("sample")
    public record SampleProperties(String title, Duration timeout, List<String> servers) {
    }
}
