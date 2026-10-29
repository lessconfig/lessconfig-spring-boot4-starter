package com.lessconfig.spring.boot;

import org.jspecify.annotations.NullMarked;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;

import java.io.IOException;
import java.util.List;

/**
 * Loads remote files as mutable Spring property sources backed by the shared SDK cache.
 */
@NullMarked
public final class LessConfigConfigDataLoader implements ConfigDataLoader<LessConfigConfigDataResource> {
    /**
     * Creates a loader for Spring Boot's Config Data factories.
     */
    public LessConfigConfigDataLoader() {
    }

    /**
     * Loads a resource and preserves an empty source for optional missing files.
     * Document profile selectors are evaluated by the parser. Profile activation and further
     * imports are configured locally, so remote data cannot alter the bootstrap graph.
     * Optional imports only tolerate missing resources, not authentication, transport or parsing failures.
     *
     * @param context  context containing the shared LessConfig runtime
     * @param resource configuration to load
     * @return configuration data backed by a refreshable property source
     * @throws IOException if the document cannot be parsed
     */
    @Override
    public ConfigData load(ConfigDataLoaderContext context, LessConfigConfigDataResource resource) throws IOException {
        LessConfigRuntime runtime = LessConfigRuntime.getRequired(context.getBootstrapContext());
        return new ConfigData(List.of(runtime.load(resource)), ConfigData.Option.IGNORE_IMPORTS,
                ConfigData.Option.IGNORE_PROFILES);
    }
}
