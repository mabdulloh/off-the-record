package io.github.mabdulloh.offtherecord;

import java.lang.invoke.MethodHandles;

/**
 * A masking plan for one class: how to read its properties, what to do with them, and how to build a
 * masked copy. Resolved once per class and cached in a {@link ClassValue}.
 *
 * <p>A {@code ClassValue} rather than a map keyed by {@code Class}, because such a map keeps a strong
 * reference to the class and therefore to its classloader, which leaks when an application is redeployed.
 */
interface TypeMasker {

    ClassValue<TypeMasker> CACHE = new ClassValue<>() {
        @Override
        protected TypeMasker computeValue(Class<?> type) {
            return type.isRecord() ? new RecordMasker(type) : new BeanMasker(type);
        }
    };

    static TypeMasker of(Class<?> type) {
        return CACHE.get(type);
    }

    /** Returns a masked copy of {@code original}, or {@code original} itself if nothing changed. */
    Object mask(Object original, MaskingSession session);

    /**
     * Creates a blank instance that can be registered as the copy before it is populated, which is what
     * makes reference cycles resolvable. Returns {@code null} when the class cannot be built that way,
     * because a copy produced by a canonical constructor, a builder, or an all-args constructor cannot
     * exist before its own contents do.
     */
    default Object createShell() {
        return null;
    }

    /**
     * Prefers a private lookup so package-private types work, and degrades to the public lookup when the
     * type lives in a named module that exports its package without opening it.
     */
    static MethodHandles.Lookup lookupFor(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException | SecurityException e) {
            return MethodHandles.publicLookup();
        }
    }

    static String inaccessible(Class<?> type) {
        return "Cannot access " + type.getName() + " to mask it. If it lives in a named module, that module "
                + "must open its package, for example: opens " + type.getPackageName()
                + " to io.github.mabdulloh.offtherecord;";
    }
}
