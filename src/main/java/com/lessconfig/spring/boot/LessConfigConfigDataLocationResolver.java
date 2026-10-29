package com.lessconfig.spring.boot;

import org.jspecify.annotations.NullMarked;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.Profiles;

import java.util.List;

/**
 * Resolves {@code lessconfig:project/environment/file.yml} and properties imports.
 * Remote loading occurs after local profiles have been determined, before application beans exist.
 * No implicit file names, environments or profile-specific file variants are generated.
 */
@NullMarked
public final class LessConfigConfigDataLocationResolver
        implements ConfigDataLocationResolver<LessConfigConfigDataResource> {
    /**
     * Scheme identifying LessConfig resources.
     */
    private static final String PREFIX = "lessconfig:";

    /**
     * Creates a resolver for Spring Boot's Config Data factories.
     */
    public LessConfigConfigDataLocationResolver() {
    }

    /**
     * Determines whether this resolver owns a location.
     *
     * @param context  current resolution context
     * @param location candidate import, with any optional prefix already recognized by Boot
     * @return whether the location uses the LessConfig scheme
     */
    @Override
    public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return location.hasPrefix(PREFIX);
    }

    /**
     * Validates syntax while deferring network access until profiles are known.
     *
     * @param context  current resolution context
     * @param location import location
     * @return an empty list during the initial resolution phase
     */
    @Override
    public List<LessConfigConfigDataResource> resolve(ConfigDataLocationResolverContext context,
                                                      ConfigDataLocation location) {
        resource(location);
        return List.of();
    }

    /**
     * Creates one shared runtime and resolves the explicitly named file.
     *
     * @param context  context providing connection properties and the bootstrap registry
     * @param location import location
     * @param profiles profiles selected by Spring Boot, including expanded profile groups
     * @return the single requested resource
     * @throws IllegalStateException if connection settings are missing or inconsistent
     */
    @Override
    public List<LessConfigConfigDataResource> resolveProfileSpecific(ConfigDataLocationResolverContext context,
                                                                     ConfigDataLocation location, Profiles profiles) {
        LessConfigConfigDataResource resource = resource(location);
        LessConfigProperties properties = context.getBinder().bind("lessconfig", LessConfigProperties.class)
                .orElseThrow(() -> new IllegalStateException("lessconfig.endpoint and lessconfig.token are required"));
        ConfigurableBootstrapContext bootstrap = context.getBootstrapContext();
        if (!bootstrap.isRegistered(LessConfigRuntime.class)) {
            bootstrap.register(LessConfigRuntime.class, ignored -> new LessConfigRuntime(properties, profiles.getAccepted()));
            bootstrap.addCloseListener(event -> LessConfigRuntime.getRequired(event.getBootstrapContext())
                    .attach(event.getApplicationContext()));
        }
        LessConfigRuntime.getRequired(bootstrap).verifyConnection(properties);
        return List.of(resource);
    }

    /**
     * Parses and validates an import without opening a connection.
     *
     * @param location location using the LessConfig scheme
     * @return validated remote resource
     */
    private LessConfigConfigDataResource resource(ConfigDataLocation location) {
        return new LessConfigConfigDataResource(location.getNonPrefixedValue(PREFIX), location.isOptional());
    }
}
