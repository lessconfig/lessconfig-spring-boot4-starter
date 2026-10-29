package com.lessconfig.spring.boot;

import com.lessconfig.ConfigChange;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.config.ConfigDataResource;

import java.util.Objects;

/**
 * Identifies one remote YAML or properties configuration imported by Spring Boot.
 */
@NullMarked
public final class LessConfigConfigDataResource extends ConfigDataResource {
    /**
     * Case-sensitive project slug.
     */
    private final String project;
    /**
     * Case-sensitive environment slug.
     */
    private final String environment;
    /**
     * Configuration key, including its file extension.
     */
    private final String key;
    /**
     * Whether an initially missing configuration may be skipped.
     */
    private final boolean optional;

    /**
     * Creates a resource from an import path.
     *
     * @param path     project/environment/key without the scheme
     * @param optional whether a missing resource is allowed
     * @throws IllegalArgumentException if the path or extension is unsupported
     */
    public LessConfigConfigDataResource(String path, boolean optional) {
        super(optional);
        String[] parts = path.split("/", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected lessconfig:project/environment/file.yml or file.properties");
        }
        for (String part : parts) {
            if (!part.matches("[A-Za-z0-9._-]+") || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("LessConfig import segments must be valid project, environment and key identifiers");
            }
        }
        if (!(parts[2].endsWith(".yml") || parts[2].endsWith(".yaml") || parts[2].endsWith(".properties"))) {
            throw new IllegalArgumentException("LessConfig imports support .yml, .yaml and .properties files");
        }
        this.project = parts[0];
        this.environment = parts[1];
        this.key = parts[2];
        this.optional = optional;
    }

    /**
     * Returns the case-sensitive project slug.
     *
     * @return case-sensitive project slug
     */
    public String getProject() {
        return project;
    }

    /**
     * Returns the case-sensitive environment slug.
     *
     * @return case-sensitive environment slug
     */
    public String getEnvironment() {
        return environment;
    }

    /**
     * Returns the configuration key, including its extension.
     *
     * @return configuration key, including its extension
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns whether the initial configuration is optional.
     *
     * @return whether the initial configuration is optional
     */
    public boolean isOptionalImport() {
        return optional;
    }

    /**
     * Tests whether a server change affects this file or one of its ancestors.
     *
     * @param change original SDK change record
     * @return whether this resource needs refreshing
     */
    boolean isAffectedBy(ConfigChange change) {
        return project.equals(change.getProject())
                && (change.getEnvironment() == null || environment.equals(change.getEnvironment()))
                && (change.getKey() == null || key.equals(change.getKey()));
    }

    /**
     * Compares resource identity and missing-resource policy.
     *
     * @param other object to compare
     * @return whether both resources identify the same import
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof LessConfigConfigDataResource resource
                && optional == resource.optional && project.equals(resource.project)
                && environment.equals(resource.environment) && key.equals(resource.key);
    }

    /**
     * Returns hash of the resource identity and optional flag.
     *
     * @return hash of the resource identity and optional flag
     */
    @Override
    public int hashCode() {
        return Objects.hash(project, environment, key, optional);
    }

    /**
     * Returns import location without credentials or its optional prefix.
     *
     * @return import location without credentials or its optional prefix
     */
    @Override
    public String toString() {
        return "lessconfig:" + project + "/" + environment + "/" + key;
    }
}
