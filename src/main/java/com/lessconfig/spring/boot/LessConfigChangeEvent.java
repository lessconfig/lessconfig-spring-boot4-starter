package com.lessconfig.spring.boot;

import com.lessconfig.ConfigChange;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Published after remote property sources have been successfully refreshed.
 * Includes original server records and changed property names, without configuration values.
 * Names describe changes within imported sources, even if a higher-priority source masks them.
 * This event does not rebind existing configuration beans or injected values.
 */
@NullMarked
public final class LessConfigChangeEvent extends ApplicationEvent {
    /**
     * Serialization version.
     */
    private static final long serialVersionUID = 1L;
    /**
     * Original records affecting successfully refreshed imports.
     */
    private final List<ConfigChange> changes;
    /**
     * Names added, changed or removed from the imported property snapshots.
     */
    private final Set<String> propertyNames;

    /**
     * Creates an immutable notification.
     *
     * @param source        object publishing the event
     * @param changes       original server changes affecting successfully refreshed files
     * @param propertyNames changed flattened property names
     */
    public LessConfigChangeEvent(Object source, List<ConfigChange> changes, Set<String> propertyNames) {
        super(source);
        this.changes = List.copyOf(changes);
        this.propertyNames = Collections.unmodifiableSet(new LinkedHashSet<>(propertyNames));
    }

    /**
     * Returns the original server records when available. Checkpoint recovery may reconcile
     * the cache without individual records, in which case only property names are available.
     *
     * @return immutable original records, or an empty list after checkpoint reconciliation
     */
    public List<ConfigChange> getChanges() {
        return changes;
    }

    /**
     * Returns the names of changed properties, including removed properties.
     *
     * @return immutable names of changed properties, including removed properties
     */
    public Set<String> getPropertyNames() {
        return propertyNames;
    }
}
