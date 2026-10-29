package com.lessconfig.spring.boot;

import com.lessconfig.LessConfigClient;
import com.lessconfig.WatchStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises optional Spring Cloud integration through real configuration imports and SDK watch updates.
 */
class LessConfigSpringCloudTest {

    /**
     * Setter-based configuration beans rebind before ordinary change listeners observe the update.
     *
     * @throws Exception if the loopback server cannot be started
     */
    @Test
    void rebindsExistingComponentBeforeNotifyingApplicationListeners() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "asd.username: before\nasd.password: first", "yaml", "updated");
            List<String> observed = new CopyOnWriteArrayList<>();
            SpringApplication app = application(server, BasicApplication.class);
            app.addInitializers(context -> context.addApplicationListener(event -> {
                if (event instanceof LessConfigChangeEvent) {
                    observed.add(context.getBean(UserSettings.class).getUsername());
                }
            }));
            try (ConfigurableApplicationContext context = app.run()) {
                UserSettings settings = context.getBean(UserSettings.class);
                LessConfigClient client = context.getBean(LessConfigClient.class);
                assertThat(settings.getUsername()).isEqualTo("before");
                assertThat(context.getBeansOfType(LessConfigSpringCloudRefreshListener.class)).hasSize(1);

                server.put("application.yml", "asd.username: after\nasd.password: second", "yaml", "updated");
                TestServer.await(() -> !observed.isEmpty());
                assertThat(observed).containsExactly("after");
                assertThat(settings.getPassword()).isEqualTo("second");
                assertThat(context.getBean(UserSettings.class)).isSameAs(settings);
                assertThat(context.getBean(LessConfigClient.class)).isSameAs(client);
                assertThat(server.initializations.get()).isEqualTo(1);
                assertThat(server.reads.get()).isEqualTo(2);
            }
        }
    }

    /**
     * Refresh-scoped consumers are recreated through their existing proxy; plain value consumers remain unchanged.
     *
     * @throws Exception if the loopback server cannot be started
     */
    @Test
    void recreatesRefreshScopedValuesWithoutReplacingTheirProxy() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "asd.username: before", "yaml", "updated");
            List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
            SpringApplication app = application(server, ScopedApplication.class);
            app.addListeners(event -> {
                if (event instanceof LessConfigChangeEvent change) {
                    events.add(change);
                }
            });
            try (ConfigurableApplicationContext context = app.run()) {
                ValueConsumer scoped = context.getBean("scopedValue", ValueConsumer.class);
                ValueConsumer plain = context.getBean("plainValue", ValueConsumer.class);
                Object originalInstance = scoped.getInstance();
                assertThat(scoped.getUsername()).isEqualTo("before");

                server.put("application.yml", "asd.username: after", "yaml", "updated");
                TestServer.await(() -> !events.isEmpty());
                assertThat(scoped.getUsername()).isEqualTo("after");
                assertThat(scoped.getInstance()).isNotSameAs(originalInstance);
                assertThat(context.getBean("scopedValue")).isSameAs(scoped);
                assertThat(plain.getUsername()).isEqualTo("before");
            }
        }
    }

    /**
     * Applications can disable bean refresh while keeping remote property sources and notifications active.
     *
     * @throws Exception if the loopback server cannot be started
     */
    @Test
    void honorsSpringCloudRefreshDisabled() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "asd.username: before", "yaml", "updated");
            try (ConfigurableApplicationContext context = application(server, BasicApplication.class)
                    .run("--spring.cloud.refresh.enabled=false")) {
                assertThat(context.getBeansOfType(LessConfigSpringCloudRefreshListener.class)).isEmpty();
                server.put("application.yml", "asd.username: after", "yaml", "updated");
                TestServer.await(() -> "after".equals(context.getEnvironment().getProperty("asd.username")));
                assertThat(context.getBean(UserSettings.class).getUsername()).isEqualTo("before");
            }
        }
    }

    /**
     * Optional auto-configuration can be inspected without loading any of its Spring Cloud method signatures.
     *
     * @throws Exception if the fixture or isolated class loader cannot be created or closed
     */
    @Test
    void skipsAutoConfigurationWhenSpringCloudClassesAreAbsent() throws Exception {
        try (TestServer server = new TestServer();
             FilteredClassLoader loader = new FilteredClassLoader("org.springframework.cloud")) {
            new ApplicationContextRunner()
                    .withClassLoader(loader)
                    .withConfiguration(AutoConfigurations.of(LessConfigSpringCloudAutoConfiguration.class))
                    .withBean(LessConfigRuntime.class, () -> new LessConfigRuntime(
                            new LessConfigProperties(server.endpoint(), "test-token"), List.of("default")))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean("lessConfigSpringCloudRefreshListener");
                        assertThat(server.requests).isEmpty();
                    });
        }
    }

    /**
     * Spring Cloud alone must not activate LessConfig without an imported remote configuration.
     *
     * @throws Exception if the loopback server cannot be started
     */
    @Test
    void remainsInactiveWithoutLessConfigImports() throws Exception {
        try (TestServer server = new TestServer();
             ConfigurableApplicationContext context = application(server, BasicApplication.class)
                     .run("--spring.config.import=")) {
            assertThat(context.getBeansOfType(LessConfigClient.class)).isEmpty();
            assertThat(context.getBeansOfType(LessConfigSpringCloudRefreshListener.class)).isEmpty();
            assertThat(server.requests).isEmpty();
        }
    }

    /**
     * Batches without changed properties and events from another runtime do not rebind or invalidate beans.
     *
     * @throws Exception if the loopback server cannot be started
     */
    @Test
    void ignoresUnchangedPropertiesAndForeignRuntimeEvents() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "asd.username: before", "yaml", "updated");
            List<Object> cloudEvents = new CopyOnWriteArrayList<>();
            SpringApplication app = application(server, ScopedApplication.class);
            app.addListeners(event -> {
                if (event instanceof EnvironmentChangeEvent || event instanceof RefreshScopeRefreshedEvent) {
                    cloudEvents.add(event);
                }
            });
            try (ConfigurableApplicationContext context = app.run()) {
                cloudEvents.clear();
                ValueConsumer scoped = context.getBean("scopedValue", ValueConsumer.class);
                Object originalInstance = scoped.getInstance();
                context.publishEvent(new LessConfigChangeEvent(context.getBean(LessConfigClient.class), List.of(), Set.of()));
                context.publishEvent(new LessConfigChangeEvent(new Object(), List.of(), Set.of("asd.username")));
                assertThat(cloudEvents).isEmpty();
                assertThat(scoped.getInstance()).isSameAs(originalInstance);
            }
        }
    }

    /**
     * A conversion failure is reported by Spring Cloud and does not prevent a subsequent valid watch update.
     *
     * @throws Exception if the loopback server cannot be started
     */
    @Test
    void recoversFromConfigurationBeanBindingFailure() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "asd.username: before\nasd.attempts: 1", "yaml", "updated");
            try (ConfigurableApplicationContext context = application(server, BasicApplication.class).run()) {
                ConfigurationPropertiesRebinder rebinder = context.getBean(ConfigurationPropertiesRebinder.class);
                server.put("application.yml", "asd.attempts: invalid", "yaml", "updated");
                TestServer.await(() -> !rebinder.getErrors().isEmpty());

                List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
                context.addApplicationListener(event -> {
                    if (event instanceof LessConfigChangeEvent change) {
                        events.add(change);
                    }
                });
                server.put("application.yml", "asd.username: recovered\nasd.attempts: 2", "yaml", "updated");
                TestServer.await(() -> !events.isEmpty());
                assertThat(context.getBean(UserSettings.class).getUsername()).isEqualTo("recovered");
                assertThat(context.getBean(UserSettings.class).getAttempts()).isEqualTo(2);
                assertThat(rebinder.getErrors()).isEmpty();
                assertThat(context.getBean(LessConfigClient.class).getStatus()).isEqualTo(WatchStatus.WATCHING);
            }
        }
    }

    /**
     * Rebinding observes command-line precedence and fallback values when a remote property is removed.
     *
     * @throws Exception if the loopback server cannot be started
     */
    @Test
    void rebindsFromTheEffectiveEnvironmentIncludingFallbacks() throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "asd.username: remote\nasd.password: remote-password", "yaml", "updated");
            List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
            SpringApplication app = application(server, BasicApplication.class);
            app.addListeners(event -> {
                if (event instanceof LessConfigChangeEvent change) {
                    events.add(change);
                }
            });
            try (ConfigurableApplicationContext context = app.run("--asd.username=command-line")) {
                UserSettings settings = context.getBean(UserSettings.class);
                assertThat(settings.getPassword()).isEqualTo("remote-password");
                server.put("application.yml", "asd.username: updated-remote", "yaml", "updated");
                TestServer.await(() -> !events.isEmpty());
                assertThat(settings.getUsername()).isEqualTo("command-line");
                assertThat(settings.getPassword()).isEqualTo("local-password");
            }
        }
    }

    /**
     * Configures a non-web application using only the fixture's remote file and explicit local defaults.
     *
     * @param server loopback configuration service
     * @param source application configuration class
     * @return application ready to run
     */
    private SpringApplication application(TestServer server, Class<?> source) {
        SpringApplication app = new SpringApplication(source);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of(
                "spring.main.banner-mode", "off",
                "logging.level.root", "OFF",
                "spring.config.location", "optional:classpath:/absent-test-application.properties",
                "spring.config.import", "lessconfig:payment/prod/application.yml",
                "lessconfig.endpoint", server.endpoint(),
                "lessconfig.token", "test-token",
                "asd.password", "local-password"));
        return app;
    }

    /**
     * Application requiring only Boot and the LessConfig starter.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(UserSettings.class)
    static class BasicApplication {
    }

    /**
     * Application also exercising Spring Cloud's refresh scope.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(BasicApplication.class)
    static class ScopedApplication {
        /**
         * Creates a value consumer whose target can be replaced during refresh.
         *
         * @param username effective value at target creation
         * @return new refresh-scoped target
         */
        @Bean
        @RefreshScope
        ValueConsumer scopedValue(@Value("${asd.username}") String username) {
            return new ValueConsumer(username);
        }

        /**
         * Creates an ordinary singleton whose injected value remains fixed.
         *
         * @param username effective value at startup
         * @return ordinary singleton consumer
         */
        @Bean
        ValueConsumer plainValue(@Value("${asd.username}") String username) {
            return new ValueConsumer(username);
        }
    }

    /**
     * Mutable application settings using the same annotations as a normal component-scanned bean.
     */
    @Component
    @ConfigurationProperties("asd")
    public static class UserSettings {
        /**
         * Configured user name.
         */
        private String username;
        /**
         * Configured password, with a lower-priority local fallback in these tests.
         */
        private String password;
        /**
         * Numeric property used to exercise Spring's conversion failures.
         */
        private int attempts;

        /**
         * Creates the mutable settings bean before Spring binds its values.
         */
        public UserSettings() {
        }

        /**
         * Returns the configured user name.
         *
         * @return current user name
         */
        public String getUsername() {
            return username;
        }

        /**
         * Assigns the user name during binding.
         *
         * @param username configured user name
         */
        public void setUsername(String username) {
            this.username = username;
        }

        /**
         * Returns the configured password.
         *
         * @return current password
         */
        public String getPassword() {
            return password;
        }

        /**
         * Assigns the password during binding.
         *
         * @param password configured password
         */
        public void setPassword(String password) {
            this.password = password;
        }

        /**
         * Returns the configured attempt count.
         *
         * @return current attempt count
         */
        public int getAttempts() {
            return attempts;
        }

        /**
         * Assigns the attempt count during binding.
         *
         * @param attempts converted attempt count
         */
        public void setAttempts(int attempts) {
            this.attempts = attempts;
        }
    }

    /**
     * A proxyable value consumer that exposes target identity for lifecycle assertions.
     */
    public static class ValueConsumer {
        /**
         * Value captured when this target was created.
         */
        private final String username;
        /**
         * Identity belonging to the target rather than its stable scoped proxy.
         */
        private final Object instance = new Object();

        /**
         * Captures the value injected by Spring.
         *
         * @param username effective user name
         */
        public ValueConsumer(String username) {
            this.username = username;
        }

        /**
         * Returns the injected value.
         *
         * @return user name captured by this target
         */
        public String getUsername() {
            return username;
        }

        /**
         * Returns this target's identity.
         *
         * @return identity used to detect target recreation
         */
        public Object getInstance() {
            return instance;
        }
    }
}
