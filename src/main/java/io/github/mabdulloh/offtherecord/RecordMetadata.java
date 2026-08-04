package io.github.mabdulloh.offtherecord;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;

/**
 * What we need to know about one Record type in order to mask it: how to read each component, what to
 * do with it, and how to build a new instance.
 *
 * <p>Resolved once per Record class and cached in a {@link ClassValue}. A {@code ClassValue} rather
 * than a map keyed by {@code Class}, because such a map keeps a strong reference to the class and
 * therefore to its classloader, which leaks when an application is redeployed.
 */
final class RecordMetadata {

    private static final ClassValue<RecordMetadata> CACHE = new ClassValue<>() {
        @Override
        protected RecordMetadata computeValue(Class<?> type) {
            return new RecordMetadata(type);
        }
    };

    private final Class<?> recordType;
    private final Component[] components;

    /** Canonical constructor, adapted to {@code (Object[]) -> Object}. */
    private final MethodHandle constructor;

    private RecordMetadata(Class<?> recordType) {
        this.recordType = recordType;

        RecordComponent[] recordComponents = recordType.getRecordComponents();
        MethodHandles.Lookup lookup = lookupFor(recordType);
        Class<?>[] parameterTypes = new Class<?>[recordComponents.length];
        Component[] resolved = new Component[recordComponents.length];

        for (int i = 0; i < recordComponents.length; i++) {
            parameterTypes[i] = recordComponents[i].getType();
            resolved[i] = toComponent(recordType, recordComponents[i], lookup);
        }

        this.components = resolved;
        this.constructor = canonicalConstructor(recordType, parameterTypes, lookup, recordComponents.length);
    }

    static RecordMetadata of(Class<?> recordType) {
        return CACHE.get(recordType);
    }

    Component[] components() {
        return components;
    }

    /**
     * Builds a new instance from already-masked component values.
     *
     * <p>A canonical constructor that validates one of the masked components will reject them. That
     * surfaces as a {@link MaskingException} rather than a fallback to the original object, because
     * returning the original would hand back exactly the values the caller asked to hide.
     */
    Object construct(Object[] args) {
        try {
            return constructor.invoke(args);
        } catch (Throwable t) {
            throw new MaskingException(
                    "The canonical constructor of " + recordType.getName() + " rejected the masked values. "
                            + "A compact constructor that validates a masked component (length, format, checksum) "
                            + "cannot accept a redacted value, and a component declared as a concrete collection "
                            + "type cannot hold the unmodifiable copy that masking produces. Move the validation off "
                            + "the masked component, declare collections as interfaces, or choose a mask that "
                            + "preserves the format.",
                    t);
        }
    }

    private static Component toComponent(Class<?> recordType, RecordComponent component,
                                         MethodHandles.Lookup lookup) {
        MethodHandle accessor;
        try {
            accessor = lookup.unreflect(component.getAccessor())
                    .asType(MethodType.methodType(Object.class, Object.class));
        } catch (IllegalAccessException e) {
            throw new MaskingException(inaccessible(recordType) + " Component: " + component.getName() + ".", e);
        }

        Mask spec = component.getAnnotation(Mask.class);
        if (spec == null) {
            return new Component(accessor, null, 0, 0, '*');
        }

        validate(recordType, component, spec);
        return new Component(accessor, spec.value(), spec.preserveStart(), spec.preserveEnd(), spec.maskChar());
    }

    /**
     * Rejects annotation usage we cannot honour, at first use of the Record type, with a message that
     * names the offending component and never contains its value.
     */
    private static void validate(Class<?> recordType, RecordComponent component, Mask spec) {
        String where = recordType.getSimpleName() + "." + component.getName();

        // Both non-negative and their sum non-negative: once the first two hold, a negative sum can only
        // mean int overflow, which would slip past the "window covers the whole value" guard in PARTIAL.
        if (spec.preserveStart() < 0 || spec.preserveEnd() < 0
                || spec.preserveStart() + spec.preserveEnd() < 0) {
            throw new MaskingException("@Mask on " + where + " has an unusable preserve window: preserveStart="
                    + spec.preserveStart() + ", preserveEnd=" + spec.preserveEnd()
                    + ". Both must be >= 0 and their sum must not overflow int.");
        }

        Class<?> type = component.getType();
        if (spec.value() == MaskType.NULLIFY) {
            if (type.isPrimitive()) {
                throw new MaskingException("@Mask(NULLIFY) on " + where + " is not supported, because "
                        + type.getName() + " is primitive and cannot hold null.");
            }
            return;
        }

        if (type != String.class) {
            throw new MaskingException("@Mask(" + spec.value() + ") on " + where + " is not supported, because "
                    + spec.value() + " only applies to String components and " + component.getName() + " is "
                    + type.getSimpleName() + ". Use @Mask(NULLIFY), or drop the annotation. Non-String strategies "
                    + "are not part of 0.1.0.");
        }
    }

    private static MethodHandle canonicalConstructor(Class<?> recordType, Class<?>[] parameterTypes,
                                                     MethodHandles.Lookup lookup, int arity) {
        try {
            Constructor<?> canonical = recordType.getDeclaredConstructor(parameterTypes);
            return lookup.unreflectConstructor(canonical)
                    .asType(MethodType.genericMethodType(arity))
                    .asSpreader(Object[].class, arity);
        } catch (NoSuchMethodException e) {
            throw new MaskingException("Could not find the canonical constructor of " + recordType.getName() + ".", e);
        } catch (IllegalAccessException e) {
            throw new MaskingException(inaccessible(recordType), e);
        }
    }

    /**
     * Prefers a private lookup so that package-private Records work, and degrades to the public lookup
     * when the Record lives in a named module that exports its package without opening it.
     */
    private static MethodHandles.Lookup lookupFor(Class<?> recordType) {
        try {
            return MethodHandles.privateLookupIn(recordType, MethodHandles.lookup());
        } catch (IllegalAccessException | SecurityException e) {
            return MethodHandles.publicLookup();
        }
    }

    private static String inaccessible(Class<?> recordType) {
        return "Cannot access " + recordType.getName() + " to mask it. If it lives in a named module, that module "
                + "must open its package, for example: opens " + recordType.getPackageName()
                + " to io.github.mabdulloh.offtherecord;";
    }

    /**
     * One Record component: how to read it, and what to do with what we read.
     *
     * <p>{@code maskType} is {@code null} for components without {@link Mask}; those are traversed
     * instead of redacted. The annotation values are unpacked here rather than kept as a {@link Mask}
     * instance, so that reading them is a field access rather than a proxy call per component per mask.
     */
    record Component(MethodHandle accessor, MaskType maskType, int preserveStart, int preserveEnd, char maskChar) {

        boolean isAnnotated() {
            return maskType != null;
        }

        Object read(Object record) {
            try {
                return accessor.invoke(record);
            } catch (Throwable t) {
                throw new MaskingException("Could not read a component of " + record.getClass().getName() + ".", t);
            }
        }

        /**
         * Applies this component's rule. Fails closed: if a strategy throws, the value collapses to a
         * full redaction rather than passing through.
         *
         * <p>No configuration reaches this catch any more, now that {@code validate} rejects unusable
         * preserve windows. It stays as the last line of defence against a bug in a strategy, and it
         * degrades rather than throws on purpose: a masked log statement should not be able to fail the
         * request that produced it.
         */
        Object redact(Object value) {
            if (maskType == MaskType.NULLIFY) {
                return null;
            }
            try {
                return MaskStrategies.apply((String) value, maskType, preserveStart, preserveEnd, maskChar);
            } catch (RuntimeException e) {
                return MaskStrategies.fullRedaction(maskChar);
            }
        }
    }
}
