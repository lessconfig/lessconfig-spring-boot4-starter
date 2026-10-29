package com.lessconfig.spring.boot;

import com.lessconfig.LessConfigClient;
import com.lessconfig.WatchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies operational log levels, bootstrap replay, outage noise and exclusion of configuration values.
 */
@ExtendWith(OutputCaptureExtension.class)
class LessConfigLoggingTest {
    /**
     * Value deliberately used in configuration and exception messages to detect accidental disclosure.
     */
    private static final String SECRET = "sensitive-test-value-must-not-appear";

    /**
     * Bootstrap logs describe imports and runtime logs confirm successfully applied updates and bean refresh.
     *
     * @param output console output captured across startup and background watch callbacks
     * @throws Exception if the loopback server or bounded wait fails
     */
    @Test
    void reportsLoadingWatchingAndSuccessfulRefresh(CapturedOutput output) throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "asd.username: before\nasd.password: " + SECRET, "yaml", "updated");
            try (ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>()).run(
                    "--spring.config.import=lessconfig:payment/prod/application.yml,optional:lessconfig:payment/prod/missing.yml")) {
                assertThat(output.getAll()).contains(
                        "Loading LessConfig configuration lessconfig:payment/prod/application.yml (optional=false)",
                        "Loaded LessConfig property source lessconfig:payment/prod/application.yml (2 properties)",
                        "LessConfig runtime ready: 2 imports, watcher status=WATCHING",
                        "Optional LessConfig configuration lessconfig:payment/prod/missing.yml is absent; watching for creation",
                        "Spring Cloud bean refresh enabled for LessConfig");

                server.put("application.yml", "asd.username: after\nasd.password: " + SECRET, "yaml", "updated");
                TestServer.await(() -> output.getAll().contains("Spring Cloud bean refresh completed for 1 changed LessConfig properties"));
                assertThat(output.getAll()).contains(
                        "Refreshed LessConfig configuration lessconfig:payment/prod/application.yml: 1 properties changed, present=true",
                        "Publishing LessConfig change notification: 1 server records, 1 changed properties");
                assertThat(context.getBean(LessConfigSpringCloudTest.UserSettings.class).getUsername()).isEqualTo("after");
            }
            assertThat(output.getAll()).contains("LessConfig runtime closed; shared client and watcher released");
            assertThat(output.getAll()).doesNotContain(SECRET, "test-token");
        }
    }

    /**
     * A logging level loaded from the remote document also governs logs recorded before that document was parsed.
     *
     * @param output console output including deferred bootstrap messages
     * @throws Exception if the loopback server cannot be started
     */
    @Test
    void replaysBootstrapMessagesUsingTheFinalLoggingConfiguration(CapturedOutput output) throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "logging.level.com.lessconfig.spring.boot: 'OFF'\nasd.password: " + SECRET,
                    "yaml", "updated");
            try (ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>()).run()) {
                assertThat(context.getEnvironment().getProperty("asd.password")).isEqualTo(SECRET);
            }
            assertThat(output.getAll()).doesNotContain("Loading LessConfig configuration", "Loaded LessConfig property source",
                    "LessConfig runtime ready", "Spring Cloud bean refresh enabled", SECRET, "test-token");
        }
    }

    /**
     * Each outage emits one warning, repeated attempts use DEBUG, recovery uses INFO and permanent failure uses ERROR.
     *
     * @param output output captured while real watch requests fail and recover
     * @throws Exception if fixture setup or bounded waiting fails
     */
    @Test
    void reportsOutagesWithoutRepeatedWarnings(CapturedOutput output) throws Exception {
        try (TestServer server = new TestServer();
             ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>()).run()) {
            TestServer.Response unavailable = new TestServer.Response(503,
                    "{\"code\":\"" + SECRET + "\",\"message\":\"" + SECRET + "\"}");
            server.pollErrors.add(unavailable);
            server.pollErrors.add(unavailable);
            TestServer.await(() -> output.getAll().contains("LessConfig watcher recovered: status=WATCHING"));
            assertThat(output.getAll().lines().filter(line -> line.contains("LessConfig watcher is retrying")).toList())
                    .singleElement().asString().contains("WARN", "HTTP 503", "using cached configuration");
            assertThat(output.getAll().lines().filter(line -> line.contains("LessConfig watch retry failed")).toList())
                    .singleElement().asString().contains("DEBUG", "HTTP 503");
            assertThat(output.getAll().lines().filter(line -> line.contains("LessConfig watcher recovered")).toList())
                    .singleElement().asString().contains("INFO");

            server.pollErrors.add(unavailable);
            TestServer.await(() -> output.getAll().lines().filter(line -> line.contains("LessConfig watcher recovered")).count() == 2);
            assertThat(output.getAll().lines().filter(line -> line.contains("LessConfig watcher is retrying")).count()).isEqualTo(2);

            server.watchFailure = new TestServer.Response(403, "{\"code\":\"forbidden\",\"message\":\"" + SECRET + "\"}");
            TestServer.await(() -> output.getAll().contains("LessConfig watcher stopped"));
            assertThat(output.getAll().lines().filter(line -> line.contains("LessConfig watcher stopped")).toList())
                    .singleElement().asString().contains("ERROR", "HTTP 403", "automatic updates are disabled");
            assertThat(context.getBean(LessConfigClient.class).getStatus()).isEqualTo(WatchStatus.STOPPED);
            assertThat(output.getAll()).doesNotContain(SECRET, "test-token");
        }
    }

    /**
     * Parse and bean-binding failures retain useful diagnostics without printing source excerpts or rejected values.
     *
     * @param output output captured during rejected refresh attempts
     * @throws Exception if fixture setup or bounded waiting fails
     */
    @Test
    void excludesConfigurationValuesFromFailureLogs(CapturedOutput output) throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "asd.username: before\nasd.attempts: 1", "yaml", "updated");
            try (ConfigurableApplicationContext context = application(server, new CopyOnWriteArrayList<>()).run()) {
                server.put("application.yml", "asd.username: " + SECRET + "\nbroken: [", "yaml", "updated");
                TestServer.await(() -> output.getAll().contains("retaining the last successful properties"));
                assertThat(context.getEnvironment().getProperty("asd.username")).isEqualTo("before");
                assertThat(output.getAll()).contains("Cannot refresh lessconfig:payment/prod/application.yml");

                server.put("application.yml", "asd.username: after\nasd.attempts: " + SECRET, "yaml", "updated");
                TestServer.await(() -> output.getAll().contains("LessConfig Spring change listener failed"));
                assertThat(output.getAll()).contains("ConfigurationPropertiesBindException", "imported property sources remain updated");
                assertThat(output.getAll()).doesNotContain(SECRET, "test-token");
            }
        }
    }

    /**
     * A raw server change with identical parsed properties does not emit an INFO refresh message.
     *
     * @param output console output captured at INFO level
     * @throws Exception if fixture setup or bounded waiting fails
     */
    @Test
    void keepsUnchangedPropertiesQuietAtInfoLevel(CapturedOutput output) throws Exception {
        try (TestServer server = new TestServer()) {
            server.put("application.yml", "asd.username: same", "yaml", "updated");
            List<LessConfigChangeEvent> events = new CopyOnWriteArrayList<>();
            try (ConfigurableApplicationContext context = application(server, events)
                    .run("--logging.level.com.lessconfig.spring.boot=INFO")) {
                int before = output.getAll().length();
                server.put("application.yml", "# only a comment changed\nasd.username: same", "yaml", "updated");
                TestServer.await(() -> !events.isEmpty());
                assertThat(output.getAll().substring(before)).doesNotContain("Refreshed LessConfig configuration",
                        "Spring Cloud bean refresh completed", "Received LessConfig change batch");
                assertThat(context.getEnvironment().getProperty("asd.username")).isEqualTo("same");
            }
        }
    }

    /**
     * Creates a real application with logging limited to the starter and no external configuration files.
     *
     * @param server loopback configuration service
     * @param events thread-safe destination for successful change notifications
     * @return application ready for command-line overrides
     */
    private SpringApplication application(TestServer server, List<LessConfigChangeEvent> events) {
        SpringApplication app = new SpringApplication(LessConfigSpringCloudTest.BasicApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of(
                "spring.main.banner-mode", "off",
                "logging.level.root", "OFF",
                "logging.level.com.lessconfig.spring.boot", "DEBUG",
                "spring.config.location", "optional:classpath:/absent-test-application.properties",
                "spring.config.import", "lessconfig:payment/prod/application.yml",
                "lessconfig.endpoint", server.endpoint(),
                "lessconfig.token", "test-token"));
        app.addListeners(event -> {
            if (event instanceof LessConfigChangeEvent change) {
                events.add(change);
            }
        });
        return app;
    }
}
