package io.github.mabdulloh.offtherecord;

import java.lang.reflect.Array;

/**
 * The resolved masking rule for one property: which strategy, and the context handed to it.
 *
 * <p>This is also where the engine's guarantees live, rather than in a base class that user code could
 * subclass or bypass. A strategy is never handed a {@code null}, its return value is checked against the
 * property type, and anything it throws becomes a full redaction.
 */
record Rule(MaskStrategy strategy, MaskContext context) {

    private static final ClassValue<MaskStrategy> STRATEGIES = new ClassValue<>() {
        @Override
        protected MaskStrategy computeValue(Class<?> type) {
            try {
                return (MaskStrategy) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new MaskingException("Could not instantiate the MaskStrategy " + type.getName()
                        + ". It needs a public no-argument constructor.", e);
            }
        }
    };

    /**
     * Validates one {@link Mask} annotation and resolves it, failing loudly with a message that names the
     * property and never contains its value.
     */
    static Rule of(Mask spec, String propertyName, Class<?> declaringType, Class<?> propertyType) {
        String where = declaringType.getSimpleName() + "." + propertyName;
        boolean custom = spec.with() != MaskStrategy.class;

        // Both non-negative and their sum non-negative: once the first two hold, a negative sum can only
        // mean int overflow, which would slip past the "window covers the whole value" guard in PARTIAL.
        if (spec.preserveStart() < 0 || spec.preserveEnd() < 0
                || spec.preserveStart() + spec.preserveEnd() < 0) {
            throw new MaskingException("@Mask on " + where + " has an unusable preserve window: preserveStart="
                    + spec.preserveStart() + ", preserveEnd=" + spec.preserveEnd()
                    + ". Both must be >= 0 and their sum must not overflow int.");
        }

        // An annotation cannot report whether value() was written or defaulted, so this catches the case
        // where value() was set to something other than its default alongside with().
        if (custom && spec.value() != MaskType.REDACT_ALL) {
            throw new MaskingException("@Mask on " + where + " sets both value() and with(). Pick one: a "
                    + "built-in MaskType, or a custom MaskStrategy.");
        }

        MaskContext context = new MaskContext(propertyName, declaringType, propertyType,
                spec.preserveStart(), spec.preserveEnd(), spec.maskChar());

        if (custom) {
            return new Rule(STRATEGIES.get(spec.with()), context);
        }

        MaskType type = spec.value();
        if (type == MaskType.NULLIFY) {
            if (propertyType.isPrimitive()) {
                throw new MaskingException("@Mask(NULLIFY) on " + where + " is not supported, because "
                        + propertyType.getName() + " is primitive and cannot hold null.");
            }
        } else if (propertyType != String.class) {
            throw new MaskingException("@Mask(" + type + ") on " + where + " is not supported, because " + type
                    + " only applies to String properties and " + propertyName + " is "
                    + propertyType.getSimpleName() + ". Use @Mask(NULLIFY), or @Mask(with = ...) with your "
                    + "own MaskStrategy, or drop the annotation.");
        }
        return new Rule(type, context);
    }

    /** Applies the rule. Null in, null out, and fail closed on anything unexpected. */
    Object apply(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Object masked = strategy.mask(value, context);
            return fits(context.propertyType(), masked)
                    ? masked
                    : failClosed(context.propertyType(), context.maskChar());
        } catch (RuntimeException e) {
            return failClosed(context.propertyType(), context.maskChar());
        }
    }

    /**
     * The value used when a strategy misbehaves, or when a property cannot even be read. Reveals nothing,
     * including length, and is always something the property can actually hold.
     */
    static Object failClosed(Class<?> propertyType, char maskChar) {
        if (propertyType == String.class || propertyType == CharSequence.class
                || propertyType == Object.class) {
            return MaskType.fullRedaction(maskChar);
        }
        if (propertyType.isPrimitive()) {
            // The zero value for this primitive, without a switch over all eight of them.
            return Array.get(Array.newInstance(propertyType, 1), 0);
        }
        return null;
    }

    private static boolean fits(Class<?> target, Object value) {
        if (value == null) {
            return !target.isPrimitive();
        }
        if (!target.isPrimitive()) {
            return target.isInstance(value);
        }
        Class<?> actual = value.getClass();
        return (target == int.class && actual == Integer.class)
                || (target == long.class && actual == Long.class)
                || (target == boolean.class && actual == Boolean.class)
                || (target == double.class && actual == Double.class)
                || (target == float.class && actual == Float.class)
                || (target == short.class && actual == Short.class)
                || (target == byte.class && actual == Byte.class)
                || (target == char.class && actual == Character.class);
    }
}
