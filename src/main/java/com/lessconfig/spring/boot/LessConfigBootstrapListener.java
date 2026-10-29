package com.lessconfig.spring.boot;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Closes early LessConfig resources if startup fails before Spring can own their lifecycle.
 */
@NullMarked
public final class LessConfigBootstrapListener implements SpringApplicationRunListener {
    /**
     * Bootstrap registry for this application run, available after starting is called.
     */
    private @Nullable ConfigurableBootstrapContext bootstrapContext;

    /**
     * Creates a lifecycle guard for one application run.
     *
     * @param application application being started
     * @param args        application arguments
     */
    public LessConfigBootstrapListener(SpringApplication application, String[] args) {
    }

    /**
     * Retains the registry without creating a client.
     *
     * @param bootstrapContext registry belonging to this run
     */
    @Override
    public void starting(ConfigurableBootstrapContext bootstrapContext) {
        this.bootstrapContext = bootstrapContext;
    }

    /**
     * Releases a runtime created during any stage of a failed startup.
     *
     * @param context   application context, or null if creation has not occurred
     * @param exception startup failure
     */
    @Override
    public void failed(@Nullable ConfigurableApplicationContext context, Throwable exception) {
        ConfigurableBootstrapContext bootstrap = bootstrapContext;
        if (bootstrap != null) {
            LessConfigRuntime runtime = bootstrap.getOrElse(LessConfigRuntime.class, null);
            if (runtime != null) {
                runtime.close();
            }
        }
    }
}
