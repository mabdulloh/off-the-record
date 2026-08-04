package io.github.mabdulloh.offtherecord;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Produces a redacted copy of a Record.
 *
 * <pre>{@code
 * UserDto safe = Masker.mask(user);
 * }</pre>
 *
 * <p>The argument is never modified. Components annotated with {@link Mask} are redacted, nested
 * Records are masked recursively, and Records held in a {@code List}, {@code Set}, {@code Map} value,
 * {@code Optional}, or object array are traversed too.
 *
 * <p>Thread safe, with no per-call state. Per-Record metadata is cached, so the first call for a given
 * Record type is the expensive one.
 */
public final class Masker {

    /**
     * How deep the traversal goes before giving up. Doubles as the cycle guard: a Record that reaches
     * itself through a mutable collection would otherwise recurse forever.
     *
     * <p>ponytail: a depth counter is a proxy for cycle detection, not cycle detection. Ceiling: a
     * legitimately deeper-than-32 graph is refused, and a cycle is reported as "too deep or cyclic"
     * rather than pinpointed. Upgrade path is an {@code IdentityHashMap} of in-progress objects, which
     * costs an allocation per {@code mask()} call; not worth it until someone hits the ceiling.
     */
    private static final int MAX_DEPTH = 32;

    private Masker() {
    }

    /**
     * Returns a redacted copy of {@code value}.
     *
     * <p>Accepts a Record, or a container holding Records. Anything else is returned as-is. If no
     * component actually changed, the original instance is returned rather than an identical copy.
     *
     * @param value the object to mask, may be {@code null}
     * @param <T>   the type of the object, preserved in the result
     * @return the masked copy, or {@code null} if {@code value} was {@code null}
     * @throws MaskingException if a {@link Mask} annotation is used on an unsupported component type,
     *                          if a canonical constructor rejects the masked values, or if the graph is
     *                          deeper than 32 levels, which is also how reference cycles are caught
     */
    @SuppressWarnings("unchecked")
    public static <T> T mask(T value) {
        return (T) apply(value, 0);
    }

    /**
     * Dispatches on the runtime class rather than the declared type, because generics are erased and a
     * component declared as {@code Object} may still hold a Record.
     */
    private static Object apply(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            throw new MaskingException("Gave up masking after " + MAX_DEPTH + " levels. The object graph is either "
                    + "deeper than that or it contains a reference cycle.");
        }
        if (value.getClass().isRecord()) {
            return maskRecord(value, depth);
        }
        if (value instanceof List<?> list) {
            return maskList(list, depth);
        }
        if (value instanceof Set<?> set) {
            return maskSet(set, depth);
        }
        if (value instanceof Map<?, ?> map) {
            return maskMap(map, depth);
        }
        if (value instanceof Optional<?> optional) {
            return maskOptional(optional, depth);
        }
        // A primitive array cannot hold a Record, so there is nothing to walk into.
        if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            return maskArray(value, depth);
        }
        // Anything else: 0.1.0 has no rule for it, so it is copied by reference.
        return value;
    }

    private static Object maskRecord(Object record, int depth) {
        RecordMetadata metadata = RecordMetadata.of(record.getClass());
        RecordMetadata.Component[] components = metadata.components();

        Object[] args = new Object[components.length];
        boolean changed = false;

        for (int i = 0; i < components.length; i++) {
            Object original = components[i].read(record);
            Object masked = components[i].isAnnotated()
                    ? components[i].redact(original)
                    : apply(original, depth + 1);
            args[i] = masked;
            changed |= masked != original;
        }

        return changed ? metadata.construct(args) : record;
    }

    private static Object maskList(List<?> list, int depth) {
        List<Object> masked = new ArrayList<>(list.size());
        boolean changed = false;
        for (Object element : list) {
            Object result = apply(element, depth + 1);
            changed |= result != element;
            masked.add(result);
        }
        return changed ? Collections.unmodifiableList(masked) : list;
    }

    @SuppressWarnings("unchecked")
    private static Object maskSet(Set<?> set, int depth) {
        // A SortedSet has to come back sorted, otherwise a component declared as SortedSet cannot hold
        // the result. Reusing the comparator means masking can reorder, and can collapse elements that
        // become equal once redacted.
        Set<Object> masked = set instanceof SortedSet<?> sorted
                ? new TreeSet<>((Comparator<Object>) sorted.comparator())
                : new LinkedHashSet<>();
        boolean changed = false;
        for (Object element : set) {
            Object result = apply(element, depth + 1);
            changed |= result != element;
            masked.add(result);
        }
        if (!changed) {
            return set;
        }
        return masked instanceof SortedSet<Object> sorted
                ? Collections.unmodifiableSortedSet(sorted)
                : Collections.unmodifiableSet(masked);
    }

    /**
     * Masks values only. Keys are copied as they are, because masking a key can make two keys equal,
     * which would silently drop an entry.
     */
    @SuppressWarnings("unchecked")
    private static Object maskMap(Map<?, ?> map, int depth) {
        Map<Object, Object> masked = map instanceof SortedMap<?, ?> sorted
                ? new TreeMap<>((Comparator<Object>) sorted.comparator())
                : new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object result = apply(entry.getValue(), depth + 1);
            changed |= result != entry.getValue();
            masked.put(entry.getKey(), result);
        }
        if (!changed) {
            return map;
        }
        return masked instanceof SortedMap<Object, Object> sorted
                ? Collections.unmodifiableSortedMap(sorted)
                : Collections.unmodifiableMap(masked);
    }

    private static Object maskOptional(Optional<?> optional, int depth) {
        if (optional.isEmpty()) {
            return optional;
        }
        Object present = optional.get();
        Object result = apply(present, depth + 1);
        // apply() only returns null for a null input, so the result is still present.
        return result == present ? optional : Optional.of(result);
    }

    private static Object maskArray(Object array, int depth) {
        int length = Array.getLength(array);
        Object masked = Array.newInstance(array.getClass().getComponentType(), length);
        boolean changed = false;
        for (int i = 0; i < length; i++) {
            Object element = Array.get(array, i);
            Object result = apply(element, depth + 1);
            changed |= result != element;
            Array.set(masked, i, result);
        }
        return changed ? masked : array;
    }
}
