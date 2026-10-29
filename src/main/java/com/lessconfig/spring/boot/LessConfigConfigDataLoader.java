package com.lessconfig.spring.boot;

import org.apache.commons.logging.Log;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.logging.DeferredLogFactory;

import java.io.IOException;
import java.util.List;

/**
 * Loads remote files as mutable Spring property sources backed by the shared SDK cache.
 */
@NullMarked
public final class LessConfigConfigDataLoader implements ConfigDataLoader<LessConfigConfigDataResource> {
    /**
     * Bootstrap log replayed after Spring Boot applies the application's logging configuration.
     */
    private final Log log;

    /**
     * Creates a loader for Spring Boot's Config Data factories.
     *
     * @param logFactory factory deferring startup logs until logging has been initialized
     */
    public LessConfigConfigDataLoader(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(LessConfigConfigDataLoader.class);
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
        log.info("Loading LessConfig configuration " + resource + " (optional=" + resource.isOptionalImport() + ")");
        try {
            LessConfigPropertySource source = runtime.load(resource);
            log.debug("Loaded LessConfig property source " + resource + " (" + source.getPropertyNames().length
                    + " properties)");
            return new ConfigData(List.of(source), ConfigData.Option.IGNORE_IMPORTS, ConfigData.Option.IGNORE_PROFILES);
        } catch (IOException | RuntimeException exception) {
            log.warn("Cannot load LessConfig configuration " + resource + " ("
                    + exception.getClass().getSimpleName() + ")");
            throw exception;
        }
    }
}
