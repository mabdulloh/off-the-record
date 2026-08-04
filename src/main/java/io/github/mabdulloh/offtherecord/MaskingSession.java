package io.github.mabdulloh.offtherecord;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
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
 * One traversal of one object graph. Created per {@link Masker#mask(Object)} call, thrown away after.
 *
 * <p>It keeps an identity map of original to masked copy, which does two things. Reference cycles
 * terminate, and an object reachable from two places yields one copy rather than two, so a diamond in the
 * input stays a diamond in the output instead of quietly becoming a tree.
 *
 * <p>Not thread safe, and does not need to be: an instance never escapes the call that created it.
 */
final class MaskingSession {

    private final Map<Object, Object> copies = new IdentityHashMap<>();
    private final Set<Object> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, Object> shells = new IdentityHashMap<>();

    /** Where we are, for cycle messages. Cheap to maintain, and the only way to report a cycle usefully. */
    private final Deque<String> path = new ArrayDeque<>();

    Object mask(Object value) {
        if (value == null) {
            return null;
        }
        Object alreadyMasked = copies.get(value);
        if (alreadyMasked != null) {
            return alreadyMasked;
        }
        if (inProgress.contains(value)) {
            return reenter(value);
        }
        if (isPassThrough(value)) {
            return value;
        }

        inProgress.add(value);
        try {
            Object masked = dispatch(value);
            copies.put(value, masked);
            return masked;
        } finally {
            inProgress.remove(value);
        }
    }

    /** Masks a nested value, recording where it came from so a cycle can name the path it took. */
    Object maskChild(String label, Object value) {
        path.addLast(label);
        try {
            return mask(value);
        } finally {
            path.removeLast();
        }
    }

    /**
     * The blank copy registered for {@code original} when a cycle came back around to it, or {@code null}
     * if there was no cycle. A masker that finds one must populate and return it rather than building a
     * fresh instance, because other parts of the graph already point at it.
     */
    Object pendingShell(Object original) {
        return shells.get(original);
    }

    /**
     * Reached when the graph loops back to something still being masked. Resolvable only if the class can
     * be instantiated blank and filled in afterwards.
     */
    private Object reenter(Object value) {
        Object shell = shells.get(value);
        if (shell != null) {
            return shell;
        }
        if (!isContainer(value) && !isPassThrough(value)) {
            shell = TypeMasker.of(value.getClass()).createShell();
        }
        if (shell == null) {
            throw new MaskingException("Cannot mask a reference cycle: "
                    + String.join(" -> ", path) + " -> " + value.getClass().getSimpleName()
                    + ". A copy of " + value.getClass().getSimpleName() + " cannot exist before its own "
                    + "contents, because it is built through a constructor or a builder rather than "
                    + "through setters. Break the cycle, or give the class a no-argument constructor and "
                    + "setters.");
        }
        shells.put(value, shell);
        copies.put(value, shell);
        return shell;
    }

    private Object dispatch(Object value) {
        if (value instanceof List<?> list) {
            return maskList(list);
        }
        if (value instanceof Set<?> set) {
            return maskSet(set);
        }
        if (value instanceof Map<?, ?> map) {
            return maskMap(map);
        }
        if (value instanceof Optional<?> optional) {
            return maskOptional(optional);
        }
        if (value.getClass().isArray()) {
            return maskArray(value);
        }
        return TypeMasker.of(value.getClass()).mask(value, this);
    }

    private Object maskList(List<?> list) {
        List<Object> masked = new ArrayList<>(list.size());
        boolean changed = false;
        int index = 0;
        for (Object element : list) {
            Object result = maskChild("[" + index++ + "]", element);
            changed |= result != element;
            masked.add(result);
        }
        return changed ? Collections.unmodifiableList(masked) : list;
    }

    @SuppressWarnings("unchecked")
    private Object maskSet(Set<?> set) {
        // Reuse the comparator so a SortedSet comes back sorted, otherwise a property declared as
        // SortedSet cannot hold the result. Masking can reorder, and can collapse elements that become
        // equal once redacted.
        Set<Object> masked = set instanceof SortedSet<?> sorted
                ? new TreeSet<>((Comparator<Object>) sorted.comparator())
                : new LinkedHashSet<>();
        boolean changed = false;
        int index = 0;
        for (Object element : set) {
            Object result = maskChild("[" + index++ + "]", element);
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
     * Masks values only. Keys are copied as they are, because masking a key can make two keys equal, which
     * would silently drop an entry.
     */
    @SuppressWarnings("unchecked")
    private Object maskMap(Map<?, ?> map) {
        Map<Object, Object> masked = map instanceof SortedMap<?, ?> sorted
                ? new TreeMap<>((Comparator<Object>) sorted.comparator())
                : new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object result = maskChild("{" + entry.getKey() + "}", entry.getValue());
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

    private Object maskOptional(Optional<?> optional) {
        if (optional.isEmpty()) {
            return optional;
        }
        Object present = optional.get();
        Object result = maskChild("[present]", present);
        // mask() only returns null for a null input, so the result is still present.
        return result == present ? optional : Optional.of(result);
    }

    private Object maskArray(Object array) {
        int length = Array.getLength(array);
        Object masked = Array.newInstance(array.getClass().getComponentType(), length);
        boolean changed = false;
        for (int i = 0; i < length; i++) {
            Object element = Array.get(array, i);
            Object result = maskChild("[" + i + "]", element);
            changed |= result != element;
            Array.set(masked, i, result);
        }
        return changed ? masked : array;
    }

    private static boolean isContainer(Object value) {
        if (value.getClass().isArray()) {
            return !value.getClass().getComponentType().isPrimitive();
        }
        return value instanceof List || value instanceof Set || value instanceof Map
                || value instanceof Optional;
    }

    /**
     * Values with no properties worth walking into. Everything from the JDK, and every enum constant, is
     * left alone: a primitive array cannot hold a record, and {@code String}, {@code LocalDate} and their
     * kind are values rather than structures.
     *
     * <p>Anything else is traversed. If it turns out to hold nothing maskable, it comes back unchanged and
     * is never rebuilt, so traversing it costs a few getter calls and nothing else.
     */
    private static boolean isPassThrough(Object value) {
        Class<?> type = value.getClass();
        if (isContainer(value)) {
            return false;
        }
        if (type.isArray()) {
            return true;
        }
        if (type.isEnum() || value instanceof Enum<?>) {
            return true;
        }
        String packageName = type.getPackageName();
        return packageName.startsWith("java.")
                || packageName.startsWith("javax.")
                || packageName.startsWith("jdk.")
                || packageName.startsWith("sun.")
                || packageName.startsWith("com.sun.");
    }
}
