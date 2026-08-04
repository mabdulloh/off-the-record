package io.github.mabdulloh.offtherecord;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;

/**
 * Masks a record by reading its components and rebuilding through the canonical constructor.
 *
 * <p>Records cannot take part in a reference cycle's resolution: a masked instance cannot be created
 * before the values it is constructed from, so {@link #createShell()} stays {@code null}.
 */
final class RecordMasker implements TypeMasker {

    private final Class<?> recordType;
    private final Component[] components;

    /** Canonical constructor, adapted to {@code (Object[]) -> Object}. */
    private final MethodHandle constructor;

    RecordMasker(Class<?> recordType) {
        this.recordType = recordType;

        RecordComponent[] recordComponents = recordType.getRecordComponents();
        MethodHandles.Lookup lookup = TypeMasker.lookupFor(recordType);
        Class<?>[] parameterTypes = new Class<?>[recordComponents.length];
        Component[] resolved = new Component[recordComponents.length];

        for (int i = 0; i < recordComponents.length; i++) {
            parameterTypes[i] = recordComponents[i].getType();
            resolved[i] = toComponent(recordType, recordComponents[i], lookup);
        }

        this.components = resolved;
        this.constructor = canonicalConstructor(recordType, parameterTypes, lookup, recordComponents.length);
    }

    @Override
    public Object mask(Object original, MaskingSession session) {
        Object[] args = new Object[components.length];
        boolean changed = false;

        for (int i = 0; i < components.length; i++) {
            Component component = components[i];
            Object value;
            try {
                value = component.accessor.invoke(original);
            } catch (Throwable t) {
                // An accessor can be overridden with code that throws. Fail closed rather than propagate.
                args[i] = Rule.failClosed(component.type, component.maskChar());
                changed = true;
                continue;
            }
            Object masked = component.rule != null
                    ? component.rule.apply(value)
                    : session.maskChild(label(component.name), value);
            args[i] = masked;
            changed |= masked != value;
        }

        return changed ? construct(args) : original;
    }

    private String label(String componentName) {
        return recordType.getSimpleName() + "." + componentName;
    }

    /**
     * A canonical constructor that validates one of the masked components will reject them. That surfaces
     * as a {@link MaskingException} rather than a fallback to the original object, because returning the
     * original would hand back exactly the values the caller asked to hide.
     */
    private Object construct(Object[] args) {
        try {
            return constructor.invoke(args);
        } catch (Throwable t) {
            throw new MaskingException(
                    "The canonical constructor of " + recordType.getName() + " rejected the masked values. "
                            + "A compact constructor that validates a masked component (length, format, checksum) "
                            + "cannot accept a redacted value, and a component declared as a concrete collection "
                            + "type cannot hold the unmodifiable copy that masking produces. Move the validation "
                            + "off the masked component, declare collections as interfaces, or choose a mask that "
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
            throw new MaskingException(
                    TypeMasker.inaccessible(recordType) + " Component: " + component.getName() + ".", e);
        }

        Mask spec = component.getAnnotation(Mask.class);
        Rule rule = spec == null
                ? null
                : Rule.of(spec, component.getName(), recordType, component.getType());
        return new Component(component.getName(), component.getType(), accessor, rule);
    }

    private static MethodHandle canonicalConstructor(Class<?> recordType, Class<?>[] parameterTypes,
                                                    MethodHandles.Lookup lookup, int arity) {
        try {
            Constructor<?> canonical = recordType.getDeclaredConstructor(parameterTypes);
            return lookup.unreflectConstructor(canonical)
                    .asType(MethodType.genericMethodType(arity))
                    .asSpreader(Object[].class, arity);
        } catch (NoSuchMethodException e) {
            throw new MaskingException(
                    "Could not find the canonical constructor of " + recordType.getName() + ".", e);
        } catch (IllegalAccessException e) {
            throw new MaskingException(TypeMasker.inaccessible(recordType), e);
        }
    }

    /**
     * One record component. {@code rule} is {@code null} for components without {@link Mask}; those are
     * traversed rather than redacted.
     */
    private record Component(String name, Class<?> type, MethodHandle accessor, Rule rule) {

        char maskChar() {
            return rule == null ? '*' : rule.context().maskChar();
        }
    }
}
