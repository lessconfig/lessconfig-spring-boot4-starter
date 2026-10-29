# LessConfig Spring Boot 4 Starter

Import LessConfig configuration into Spring Boot and receive configuration change notifications.

## Requirements

Java 17 or later and Spring Boot 4.x.

## Maven

```xml
<dependency>
    <groupId>com.lessconfig</groupId>
    <artifactId>lessconfig-spring-boot4-starter</artifactId>
    <version>0.0.1</version>
</dependency>
```

## Quick Start

Add the following to `application.yml`. Set `LESSCONFIG_ENDPOINT` to your API root URL, such as `https://example.com/api`, and `LESSCONFIG_TOKEN` to your API token. The SDK adds the API version.

```yaml
lessconfig:
  endpoint: ${LESSCONFIG_ENDPOINT}
  token: ${LESSCONFIG_TOKEN}

spring:
  config:
    import:
      - "lessconfig:my-project/prod/application.yml"
      - "lessconfig:my-project/prod/database.properties"
```

Each location is `lessconfig:project/environment/key`. The key must end in `.yml`, `.yaml`, or `.properties`. Imported properties are available to `@Value`, `@ConfigurationProperties`, and Spring's `Environment` during startup. Later imports override earlier imports; command-line arguments retain higher priority.

Prefix a location with `optional:` to allow a missing configuration. If its project and environment exist, a missing key is watched for creation. Other loading errors still fail startup.

To keep these settings in `bootstrap.yml`, explicitly import it from `application.yml`:

```yaml
spring:
  config:
    import: "classpath:bootstrap.yml"
```

Remote YAML supports multiple documents and Spring profile expressions:

```yaml
service:
  message: Hello
---
spring.config.activate.on-profile: "prod & !test"
service:
  message: Hello production
```

Set active profiles and profile groups in local application configuration. The same profiles select remote documents during refresh. Further imports and profile activation are configured locally.

## Change Notifications

The starter updates its property sources automatically. Listen for successful changes with a Spring event listener:

```java
import com.lessconfig.spring.boot.LessConfigChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Observes changes to imported configuration. */
@Component
public class ConfigurationObserver {
    /**
     * Receives a notification after the updated properties are available.
     *
     * @param event original server changes and changed property names
     */
    @EventListener
    public void onChange(LessConfigChangeEvent event) {
        System.out.println(event.getPropertyNames());
    }
}
```

Inject `LessConfigClient` to access the shared SDK client or register its configuration and status listeners. Invalid updates retain the last successfully parsed properties.

## Automatic Bean Refresh

To refresh mutable `@ConfigurationProperties` beans automatically, add Spring Cloud Context to your application:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-context</artifactId>
    <version>5.0.0</version>
</dependency>
```

Use a Spring Cloud version compatible with your Spring Boot version; omit the version above when your application's Spring Cloud BOM manages it. The starter declares this dependency as `provided`, so applications opt in by adding it themselves. Actuator is not required for this integration.

With Spring Cloud refresh enabled, imported changes automatically rebind mutable `@ConfigurationProperties` beans and refresh `@RefreshScope` beans. For `@Value` injection, place the consuming bean in `@RefreshScope`. Plain singleton `@Value` fields and constructor-bound configuration records are not automatically refreshed. Binding, validation and removed-property behavior follow your Spring Cloud version.

Set `spring.cloud.refresh.enabled=false` to disable automatic bean refresh. Without Spring Cloud Context, property sources and change notifications still update; read current values from `Environment` or handle `LessConfigChangeEvent` yourself.

For detailed import and refresh diagnostics, set `logging.level.com.lessconfig.spring.boot=DEBUG`. Normal INFO logs report loading, applied changes and recovery; repeated retries use DEBUG. Starter logs exclude tokens, configuration values and exception messages that may contain them.

## Build and Test

Install `lessconfig-java:0.0.1` locally if it is not available from your Maven repository, then run:

```shell
mvn clean verify
```

The build produces the library, sources, and Javadoc JARs in `target/`.

## License

Licensed under the [Apache License 2.0](LICENSE).
