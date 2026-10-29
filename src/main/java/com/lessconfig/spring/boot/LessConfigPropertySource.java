package com.lessconfig.spring.boot;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.EnumerablePropertySource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A remote file exposed as an atomically replaceable property snapshot.
 * Readers observe complete maps; removed properties can fall back to lower-priority sources.
 */
@NullMarked
final class LessConfigPropertySource extends EnumerablePropertySource<AtomicReference<Map<String, Object>>> {
    /**
     * Creates an initially empty source.
     *
     * @param name stable import location
     */
    LessConfigPropertySource(String name) {
        super(name, new AtomicReference<>(Map.of()));
    }

    /**
     * Looks up a property in the latest successful snapshot.
     *
     * @param name flattened property name
     * @return its value, or null when absent
     */
    @Override
    public @Nullable Object getProperty(String name) {
        return source.get().get(name);
    }

    /**
     * Returns a fresh array of names in the current snapshot.
     *
     * @return a fresh array of names in the current snapshot
     */
    @Override
    public String[] getPropertyNames() {
        return source.get().keySet().toArray(String[]::new);
    }

    /**
     * Replaces the entire snapshot after parsing has succeeded.
     *
     * @param values complete new property map
     * @return names whose values were added, removed or changed in this source
     */
    Set<String> replace(Map<String, Object> values) {
        Map<String, Object> next = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        Map<String, Object> previous = source.getAndSet(next);
        Set<String> changed = new LinkedHashSet<>(previous.keySet());
        changed.addAll(next.keySet());
        changed.removeIf(key -> Objects.equals(previous.get(key), next.get(key)));
        return changed;
    }
}
