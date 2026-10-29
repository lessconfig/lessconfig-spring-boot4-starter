package com.lessconfig.spring.boot;

import com.lessconfig.DefaultLessConfigClient;
import com.lessconfig.LessConfigClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process protocol fixture. Requests use real HttpURLConnection sockets, without external services.
 */
final class TestServer implements AutoCloseable {
    /**
     * Loopback HTTP listener.
     */
    final HttpServer server;
    /**
     * Daemon workers serving concurrent configuration reads and long polls.
     */
    final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "lessconfig-test-http");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * Serializes fixture mutations and wakes pending polls.
     */
    final Object monitor = new Object();
    /**
     * Current server configurations in insertion order.
     */
    final Map<String, JSONObject> configs = new LinkedHashMap<>();
    /**
     * Ordered global watch journal.
     */
    final List<JSONObject> events = new ArrayList<>();
    /**
     * Captured requests, excluding credentials.
     */
    final List<Request> requests = new CopyOnWriteArrayList<>();
    /**
     * One-shot failures injected into revision-based polls.
     */
    final Queue<Response> pollErrors = new ConcurrentLinkedQueue<>();
    /**
     * Remaining configuration GET failures to inject.
     */
    final AtomicInteger readFailures = new AtomicInteger();
    /**
     * Number of configuration GET requests.
     */
    final AtomicInteger reads = new AtomicInteger();
    /**
     * Number of watch initialization requests.
     */
    final AtomicInteger initializations = new AtomicInteger();
    /**
     * Signals the first revision-based poll.
     */
    final CountDownLatch pollEntered = new CountDownLatch(1);
    /**
     * Optional mutation after capturing an initialization watermark.
     */
    volatile Runnable afterInitialize;
    /**
     * Optional action before serving a configuration read.
     */
    volatile Runnable beforeRead;
    /**
     * Optional response overriding every route.
     */
    volatile Response fixedResponse;
    /**
     * Optional persistent failure overriding watch requests.
     */
    volatile Response watchFailure;
    /**
     * Maximum fixture poll wait in milliseconds, independent of SDK settings.
     */
    volatile long pollWaitMillis = 100;
    /**
     * Whether environment metadata has been removed.
     */
    volatile boolean deletedEnvironment;
    /**
     * Whether project metadata has been removed.
     */
    volatile boolean deletedProject;
    /**
     * Whether the fixture is accepting long-poll waits.
     */
    volatile boolean running = true;
    /**
     * Current watch journal revision, guarded by the monitor.
     */
    long revision;

    /**
     * Starts a loopback HTTP fixture with one initial configuration.
     *
     * @throws IOException if a local listening socket cannot be created
     */
    TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1", this::handle);
        server.setExecutor(executor);
        put("application.yml", "# 原文\nport: 8080\n", "yaml", "created");
        server.start();
    }

    /**
     * Returns the fixture API root URL without a version.
     *
     * @return local API root URL; clients must append the operation's version
     */
    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    /**
     * Creates a client with bounded test retry times.
     *
     * @return independently owned test client
     */
    DefaultLessConfigClient client() {
        return LessConfigClient.builder().endpoint(endpoint()).token("test-token")
                .watchTimeout(Duration.ofSeconds(1)).watchReadTimeout(Duration.ofMillis(3000)).retryDelay(Duration.ofMillis(30), Duration.ofMillis(100)).build();
    }

    /**
     * Applies a configuration mutation and wakes active polls.
     *
     * @param key       configuration key
     * @param value     raw configuration value
     * @param type      server type name
     * @param operation server event operation
     */
    void put(String key, String value, String type, String operation) {
        synchronized (monitor) {
            revision++;
            configs.put(key, new JSONObject().put("key", key).put("value", value).put("type", type)
                    .put("description", "说明").put("revision", revision));
            events.add(event(key, "config", operation));
            monitor.notifyAll();
        }
    }

    /**
     * Deletes a key and emits an invalidation.
     *
     * @param key configuration key
     */
    void delete(String key) {
        synchronized (monitor) {
            revision++;
            configs.remove(key);
            events.add(event(key, "config", "deleted"));
            monitor.notifyAll();
        }
    }

    /**
     * Removes ancestor state and emits a nullable-key invalidation.
     *
     * @param project true for project deletion
     */
    void deleteAncestor(boolean project) {
        synchronized (monitor) {
            revision++;
            deletedEnvironment = true;
            deletedProject = project;
            configs.clear();
            events.add(event(null, project ? "project" : "environment", "deleted"));
            monitor.notifyAll();
        }
    }

    /**
     * Constructs a raw server change record at the current global revision.
     *
     * @param key      configuration key, or null for ancestor deletion
     * @param resource resource name
     * @param type     operation name
     * @return independent JSON event
     */
    JSONObject event(String key, String resource, String type) {
        return new JSONObject().put("revision", revision).put("project", "payment")
                .put("environment", resource.equals("project") ? JSONObject.NULL : "prod")
                .put("key", key == null ? JSONObject.NULL : key).put("resource", resource).put("type", type);
    }

    /**
     * Routes one real HTTP request through authorization and the controllable fixture responses.
     *
     * @param exchange incoming HTTP exchange
     * @throws IOException if the client disconnects while a response is written
     */
    private void handle(HttpExchange exchange) throws IOException {
        Request request = new Request(exchange);
        requests.add(request);
        try {
            Response response;
            if (fixedResponse != null) {
                response = fixedResponse;
            } else if (!"GET".equals(exchange.getRequestMethod())) {
                response = error(405, "method_not_allowed");
            } else if (!"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                response = error(401, "unauthorized");
            } else if (request.path.equals("/api/v1/watch")) {
                response = watch(request);
            } else {
                response = read(request.path);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (response.status == 302) {
                exchange.getResponseHeaders().set("Location", endpoint() + "/v1/redirected");
            }
            byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    /**
     * Implements initialization and bounded long-poll behavior with optional injected failures.
     *
     * @param request parsed scopes and optional global revision
     * @return watch JSON or the injected error
     * @throws InterruptedException if fixture shutdown interrupts a suspended poll
     */
    private Response watch(Request request) throws InterruptedException {
        if (watchFailure != null) {
            return watchFailure;
        }
        synchronized (monitor) {
            if (deletedEnvironment && request.scopes.stream().anyMatch(scope -> scope.startsWith("payment/"))) {
                return error(404, deletedProject ? "project_not_found" : "environment_not_found");
            }
        }
        if (request.revision == null) {
            initializations.incrementAndGet();
            long initial;
            synchronized (monitor) {
                initial = revision;
            }
            Runnable action = afterInitialize;
            afterInitialize = null;
            if (action != null) {
                action.run();
            }
            return json(new JSONObject().put("revision", initial).put("changes", new JSONArray()));
        }
        pollEntered.countDown();
        Response failure = pollErrors.poll();
        if (failure != null) {
            return failure;
        }
        synchronized (monitor) {
            JSONArray changes = changes(request);
            if (changes.length() == 0 && running) {
                monitor.wait(pollWaitMillis);
                changes = changes(request);
            }
            return json(new JSONObject().put("revision", revision).put("changes", changes));
        }
    }

    /**
     * Selects events newer than the request watermark and intersecting its scopes.
     *
     * @param request parsed watch request
     * @return ordered matching events
     */
    private JSONArray changes(Request request) {
        JSONArray result = new JSONArray();
        for (JSONObject event : events) {
            if (event.getLong("revision") <= request.revision) {
                continue;
            }
            String location = "payment/prod" + (event.isNull("key") ? "" : "/" + event.getString("key"));
            for (String scope : request.scopes) {
                if (location.equals(scope) || location.startsWith(scope + "/") || scope.startsWith(location + "/")) {
                    result.put(event);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Serves metadata/configurations, including injected refresh failures and resource deletion.
     *
     * @param path decoded request path
     * @return fixture HTTP response
     */
    private Response read(String path) {
        if (path.contains("/configurations")) {
            reads.incrementAndGet();
            Runnable action = beforeRead;
            if (action != null) {
                action.run();
            }
            if (readFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                return error(503, "temporarily_unavailable");
            }
        }
        synchronized (monitor) {
            if (path.equals("/api/v1/projects/shared/environments/prod/configurations/key")) {
                return json(new JSONObject().put("key", "key").put("value", "shared-value").put("type", "text")
                        .put("description", "").put("revision", revision));
            }
            if (path.equals("/api/v1/projects")) {
                return json(new JSONObject().put("items", deletedProject ? new JSONArray() : new JSONArray().put(project())).put("total", deletedProject ? 0 : 1));
            }
            if (deletedProject || path.startsWith("/api/v1/projects/missing")) {
                return error(404, "project_not_found");
            }
            if (path.equals("/api/v1/projects/payment")) {
                return json(project());
            }
            if (path.equals("/api/v1/projects/payment/environments")) {
                return json(new JSONObject().put("items", deletedEnvironment ? new JSONArray() : new JSONArray().put(environment())).put("total", deletedEnvironment ? 0 : 1));
            }
            if (deletedEnvironment || path.equals("/api/v1/projects/payment/environments/missing")) {
                return error(404, "environment_not_found");
            }
            if (path.equals("/api/v1/projects/payment/environments/prod")) {
                return json(environment());
            }
            String base = "/api/v1/projects/payment/environments/prod/configurations";
            if (path.equals(base)) {
                return json(new JSONObject().put("items", new JSONArray(configs.values())).put("total", configs.size()));
            }
            if (path.startsWith(base + "/")) {
                JSONObject config = configs.get(path.substring(base.length() + 1));
                return config == null ? error(404, "config_not_found") : json(config);
            }
            return error(404, "unknown_route");
        }
    }

    /**
     * Builds project metadata.
     *
     * @return project JSON
     */
    private JSONObject project() {
        return new JSONObject().put("id", "p1").put("slug", "payment").put("name", "支付")
                .put("icon", "folder-simple").put("description", "");
    }

    /**
     * Builds environment metadata at the current revision.
     *
     * @return environment JSON
     */
    private JSONObject environment() {
        return new JSONObject().put("id", "e1").put("project_id", "p1").put("slug", "prod")
                .put("description", "生产").put("revision", revision);
    }

    /**
     * Creates a successful response.
     *
     * @param value JSON payload
     * @return HTTP 200 response
     */
    static Response json(JSONObject value) {
        return new Response(200, value.toString());
    }

    /**
     * Creates a server error using the public error envelope.
     *
     * @param status HTTP status
     * @param code   stable business code
     * @return JSON error response
     */
    static Response error(int status, String code) {
        return new Response(status, new JSONObject().put("code", code).put("message", "Request failed").toString());
    }

    /**
     * Waits for an observable asynchronous condition with a bounded deadline.
     *
     * @param condition condition that must become true
     * @throws InterruptedException if the test thread is interrupted
     */
    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "Condition did not become true within 5 seconds");
    }

    /**
     * Wakes pending server polls and releases all fixture resources.
     */
    @Override
    public void close() {
        running = false;
        synchronized (monitor) {
            monitor.notifyAll();
        }
        server.stop(0);
        executor.shutdownNow();
    }

    /**
     * Immutable HTTP response used for both real and injected fixture behavior.
     */
    static final class Response {
        /**
         * HTTP response status.
         */
        final int status;
        /**
         * Serialized response payload.
         */
        final String body;

        /**
         * Stores a fixture response.
         *
         * @param status HTTP status
         * @param body   raw response body
         */
        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /**
     * Captured public HTTP request, used to assert scope and checkpoint sequencing.
     */
    static final class Request {
        /**
         * Decoded request path.
         */
        final String path;
        /**
         * Requested watch scopes.
         */
        final List<String> scopes = new ArrayList<>();
        /**
         * Requested watermark, or null for initialization.
         */
        Long revision;

        /**
         * Captures public request parameters without retaining credentials.
         *
         * @param exchange incoming HTTP request
         * @throws IOException if query decoding fails
         */
        Request(HttpExchange exchange) throws IOException {
            path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] parts = pair.split("=", 2);
                    String value = URLDecoder.decode(parts[1], "UTF-8");
                    if (parts[0].equals("scopes")) {
                        scopes.add(value);
                    }
                    if (parts[0].equals("revision")) {
                        revision = Long.valueOf(value);
                    }
                }
            }
        }
    }
}
