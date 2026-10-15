package io.github.mabdulloh.offtherecord;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Masks a class by reading its properties through getters and writing a copy through the class's own
 * public API. Never touches a field directly, and never calls {@code setAccessible(true)}: that is the
 * practice this library exists to avoid, and it would be no more acceptable here than in a record.
 *
 * <p>Properties are discovered from instance fields, walking up to but not including {@code Object}, and
 * read through {@code getX()} or {@code isX()}. A property with no getter is simply not traversed, which
 * is what lets an object that happens to be in the graph pass through untouched.
 *
 * <p>Copying is attempted through one of three mechanisms, chosen once per class:
 *
 * <ol>
 *   <li><b>Setters.</b> A no-arg constructor plus a getter and setter for every property. Preferred,
 *       because it is the only mechanism where a blank instance can be registered before it is populated,
 *       which is what makes reference cycles resolvable.</li>
 *   <li><b>Builder.</b> A {@code toBuilder()} that pre-populates, a setter on the builder per property,
 *       and a {@code build()}.</li>
 *   <li><b>All-args constructor, matched by parameter name.</b> Never by position:
 *       {@code Class.getDeclaredFields()} is documented as returning fields in no particular order, so
 *       positional matching would silently swap two same-typed properties on a different JDK. Requires
 *       the class to have been compiled with {@code -parameters}.</li>
 * </ol>
 *
 * <p>If none applies, reading still works, so an object that needs no masking passes through. Only an
 * object that actually changed and cannot be rebuilt raises {@link MaskingException}.
 */
final class BeanMasker implements TypeMasker {

    private enum Mechanism { SETTERS, BUILDER, ALL_ARGS, NONE }

    private final Class<?> beanType;
    private final Property[] properties;
    private final Mechanism mechanism;
    private final String unsupportedReason;

    private final MethodHandle noArgConstructor;
    private final MethodHandle toBuilder;
    private final MethodHandle build;
    private final MethodHandle allArgsConstructor;
    private final int[] allArgsOrder;

    BeanMasker(Class<?> beanType) {
        this.beanType = beanType;
        MethodHandles.Lookup lookup = TypeMasker.lookupFor(beanType);

        Builder builder = Builder.resolve(beanType, lookup);
        this.properties = discoverProperties(beanType, lookup, builder);
        this.noArgConstructor = noArgConstructor(beanType, lookup);

        List<String> names = new ArrayList<>(properties.length);
        for (Property property : properties) {
            names.add(property.name());
        }
        AllArgs allArgs = AllArgs.resolve(beanType, names, lookup);

        boolean allReadable = allMatch(Property::getter);
        boolean allWritable = allMatch(Property::setter);
        boolean allBuilderWritable = builder != null && allMatch(Property::builderSetter);

        if (allReadable && allWritable && noArgConstructor != null) {
            this.mechanism = Mechanism.SETTERS;
        } else if (allReadable && builder != null && allBuilderWritable) {
            this.mechanism = Mechanism.BUILDER;
        } else if (allReadable && allArgs != null) {
            this.mechanism = Mechanism.ALL_ARGS;
        } else {
            this.mechanism = Mechanism.NONE;
        }

        this.toBuilder = builder == null ? null : builder.toBuilder();
        this.build = builder == null ? null : builder.build();
        this.allArgsConstructor = allArgs == null ? null : allArgs.constructor();
        this.allArgsOrder = allArgs == null ? null : allArgs.order();
        this.unsupportedReason = mechanism == Mechanism.NONE
                ? describeWhatIsMissing(allReadable, allWritable, builder != null, allBuilderWritable)
                : null;
    }

    @Override
    public Object createShell() {
        if (mechanism != Mechanism.SETTERS) {
            return null;
        }
        try {
            return noArgConstructor.invoke();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Object mask(Object original, MaskingSession session) {
        // originals[] is only read in the BUILDER branch, so skip allocating it otherwise.
        Object[] originals = mechanism == Mechanism.BUILDER ? new Object[properties.length] : null;
        Object[] masked = new Object[properties.length];
        boolean changed = false;

        for (int i = 0; i < properties.length; i++) {
            Property property = properties[i];
            if (property.getter() == null) {
                // Not readable, so not traversable. Only the NONE mechanism tolerates this, and NONE
                // never rebuilds, so nothing is lost. BUILDER rebuild skips this slot (FR-3.1).
                if (originals != null) {
                    originals[i] = null;
                }
                masked[i] = null;
                continue;
            }
            Object value;
            try {
                value = property.getter().invoke(original);
            } catch (Throwable t) {
                // A getter that throws must not fail the whole mask. This is also what makes an
                // uninitialised JPA association harmless without the core knowing what Hibernate is.
                masked[i] = Rule.failClosed(property.type(), property.maskChar());
                changed = true;
                continue;
            }
            if (originals != null) {
                originals[i] = value;
            }
            masked[i] = property.rule() != null
                    ? property.rule().apply(value)
                    : session.maskChild(beanType.getSimpleName() + "." + property.name(), value);
            changed |= masked[i] != value;
        }

        // A cycle came back around to this object, so a blank copy is already registered and other parts
        // of the graph point at it. Fill that one in rather than building a fresh instance.
        Object shell = session.pendingShell(original);
        if (shell != null) {
            populate(shell, masked);
            return shell;
        }
        return changed ? rebuild(original, originals, masked) : original;
    }

    private Object rebuild(Object original, Object[] originals, Object[] masked) {
        if (mechanism == Mechanism.NONE) {
            throw new MaskingException(unsupportedReason);
        }
        try {
            switch (mechanism) {
                case SETTERS -> {
                    Object copy = noArgConstructor.invoke();
                    populate(copy, masked);
                    return copy;
                }
                case BUILDER -> {
                    Object partial = toBuilder.invoke(original);
                    for (int i = 0; i < properties.length; i++) {
                        if (properties[i].getter() == null) {
                            // Unreadable in the original, no value to copy through (FR-3.1).
                            continue;
                        }
                        if (masked[i] != originals[i]) {
                            properties[i].builderSetter().invoke(partial, masked[i]);
                        }
                    }
                    return build.invoke(partial);
                }
                default -> {
                    Object[] args = new Object[allArgsOrder.length];
                    for (int i = 0; i < args.length; i++) {
                        args[i] = masked[allArgsOrder[i]];
                    }
                    return allArgsConstructor.invoke(args);
                }
            }
        } catch (Throwable t) {
            throw new MaskingException("Could not build a masked copy of " + beanType.getName()
                    + ". A setter, builder, or constructor that validates a masked property cannot accept a "
                    + "redacted value, and a property declared as a concrete collection type cannot hold the "
                    + "unmodifiable copy that masking produces. Move the validation off the masked property, "
                    + "declare collections as interfaces, or choose a mask that preserves the format.", t);
        }
    }

    private void populate(Object target, Object[] masked) {
        try {
            for (int i = 0; i < properties.length; i++) {
                properties[i].setter().invoke(target, masked[i]);
            }
        } catch (Throwable t) {
            throw new MaskingException("Could not populate a masked copy of " + beanType.getName()
                    + ". A setter that validates a masked property cannot accept a redacted value.", t);
        }
    }

    private boolean allMatch(java.util.function.Function<Property, MethodHandle> part) {
        for (Property property : properties) {
            if (part.apply(property) == null) {
                return false;
            }
        }
        return true;
    }

    private String describeWhatIsMissing(boolean allReadable, boolean allWritable,
                                         boolean hasBuilder, boolean allBuilderWritable) {
        StringBuilder message = new StringBuilder("Cannot build a masked copy of ")
                .append(beanType.getName()).append(". ");
        if (!allReadable) {
            message.append("Some properties have no getter, so they cannot be read back: ")
                    .append(missing(Property::getter)).append(". ");
        }
        if (allReadable && !allWritable && noArgConstructor != null) {
            message.append("There is a no-argument constructor but no setter for: ")
                    .append(missing(Property::setter)).append(". ");
        }
        if (allReadable && allWritable && noArgConstructor == null) {
            message.append("There are setters but no no-argument constructor. ");
        }
        if (hasBuilder && !allBuilderWritable) {
            message.append("toBuilder() exists but the builder has no setter for: ")
                    .append(missing(Property::builderSetter)).append(". ");
        }
        return message.append("Masking a class needs one of: a no-argument constructor plus setters, a ")
                .append("toBuilder() plus build(), or a constructor whose parameter names match the ")
                .append("properties, which also requires compiling with -parameters. Fields are never ")
                .append("written directly.")
                .toString();
    }

    private String missing(java.util.function.Function<Property, MethodHandle> part) {
        List<String> names = new ArrayList<>();
        for (Property property : properties) {
            if (part.apply(property) == null) {
                names.add(property.name());
            }
        }
        return String.join(", ", names);
    }

    // --- resolution -------------------------------------------------------------------------------

    private static Property[] discoverProperties(Class<?> beanType, MethodHandles.Lookup lookup,
                                                 Builder builder) {
        // Subclass declarations win over a shadowed field of the same name in a superclass.
        LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
        for (Class<?> type = beanType; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.putIfAbsent(field.getName(), field);
                }
            }
        }

        List<Property> properties = new ArrayList<>(fields.size());
        for (Field field : fields.values()) {
            String name = field.getName();
            Class<?> type = field.getType();
            Mask spec = findSpec(beanType, name, type);
            Rule rule = spec == null ? null : Rule.of(spec, name, beanType, type);

            MethodHandle getter = unreflect(lookup, getter(beanType, name, type),
                    MethodType.methodType(Object.class, Object.class));
            MethodHandle setter = unreflect(lookup, setter(beanType, name, type),
                    MethodType.methodType(void.class, Object.class, Object.class));
            MethodHandle builderSetter = builder == null ? null : builder.setterFor(name, type);

            if (rule != null && getter == null) {
                throw new MaskingException("@Mask on " + beanType.getSimpleName() + "." + name
                        + " cannot be honoured, because the property has no getter. Masking reads through "
                        + "the class's public API and never reads fields directly.");
            }
            properties.add(new Property(name, type, getter, setter, builderSetter, rule));
        }
        return properties.toArray(new Property[0]);
    }

    /**
     * Finds the single {@link Mask} for a property, anywhere in the hierarchy. More than one is a
     * misconfiguration rather than a precedence rule nobody will remember: it covers annotating both the
     * field and the getter, and re-annotating an inherited property in a subclass.
     */
    private static Mask findSpec(Class<?> beanType, String propertyName, Class<?> propertyType) {
        List<Mask> found = new ArrayList<>(1);
        List<String> where = new ArrayList<>(1);

        for (Class<?> type = beanType; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Mask spec = field.getAnnotation(Mask.class);
                if (spec != null && field.getName().equals(propertyName)
                        && !Modifier.isStatic(field.getModifiers())) {
                    found.add(spec);
                    where.add("field " + type.getSimpleName() + "." + propertyName);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                Mask spec = method.getAnnotation(Mask.class);
                if (spec != null && method.getParameterCount() == 0
                        && isGetterFor(method.getName(), propertyName, propertyType)) {
                    found.add(spec);
                    where.add("method " + type.getSimpleName() + "." + method.getName() + "()");
                }
            }
        }

        if (found.size() > 1) {
            throw new MaskingException("Property " + beanType.getSimpleName() + "." + propertyName
                    + " is annotated with @Mask in more than one place: " + String.join(", ", where)
                    + ". Annotate it once, either on the field or on the getter.");
        }
        return found.isEmpty() ? null : found.get(0);
    }

    private static boolean isGetterFor(String methodName, String propertyName, Class<?> propertyType) {
        String capitalised = capitalise(propertyName);
        if (methodName.equals("get" + capitalised)) {
            return true;
        }
        return methodName.equals("is" + capitalised)
                && (propertyType == boolean.class || propertyType == Boolean.class);
    }

    private static Method getter(Class<?> beanType, String propertyName, Class<?> propertyType) {
        String capitalised = capitalise(propertyName);
        Method method = publicMethod(beanType, "get" + capitalised);
        if (method == null && (propertyType == boolean.class || propertyType == Boolean.class)) {
            method = publicMethod(beanType, "is" + capitalised);
        }
        return method != null && method.getReturnType() != void.class ? method : null;
    }

    private static Method setter(Class<?> beanType, String propertyName, Class<?> propertyType) {
        return publicMethod(beanType, "set" + capitalise(propertyName), propertyType);
    }

    private static MethodHandle noArgConstructor(Class<?> beanType, MethodHandles.Lookup lookup) {
        if (beanType.isInterface() || Modifier.isAbstract(beanType.getModifiers())) {
            return null;
        }
        try {
            Constructor<?> constructor = beanType.getDeclaredConstructor();
            return lookup.unreflectConstructor(constructor)
                    .asType(MethodType.methodType(Object.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getMethod(name, parameterTypes);
            return Modifier.isStatic(method.getModifiers()) ? null : method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static MethodHandle unreflect(MethodHandles.Lookup lookup, Method method, MethodType as) {
        if (method == null) {
            return null;
        }
        try {
            return lookup.unreflect(method).asType(as);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static String capitalise(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** One bean property. {@code rule} is {@code null} when it carries no {@link Mask}. */
    private record Property(String name, Class<?> type, MethodHandle getter, MethodHandle setter,
                            MethodHandle builderSetter, Rule rule) {

        char maskChar() {
            return rule == null ? '*' : rule.context().maskChar();
        }
    }

    /** The {@code toBuilder()} / {@code build()} pair, when the class has one. */
    private record Builder(Class<?> builderType, MethodHandle toBuilder, MethodHandle build,
                           MethodHandles.Lookup builderLookup) {

        static Builder resolve(Class<?> beanType, MethodHandles.Lookup lookup) {
            Method toBuilderMethod = publicMethod(beanType, "toBuilder");
            if (toBuilderMethod == null) {
                return null;
            }
            Class<?> builderType = toBuilderMethod.getReturnType();
            Method buildMethod = publicMethod(builderType, "build");
            if (buildMethod == null || !beanType.isAssignableFrom(buildMethod.getReturnType())) {
                return null;
            }
            MethodHandles.Lookup builderLookup = TypeMasker.lookupFor(builderType);
            MethodHandle toBuilder = unreflect(lookup, toBuilderMethod,
                    MethodType.methodType(Object.class, Object.class));
            MethodHandle build = unreflect(builderLookup, buildMethod,
                    MethodType.methodType(Object.class, Object.class));
            if (toBuilder == null || build == null) {
                return null;
            }
            return new Builder(builderType, toBuilder, build, builderLookup);
        }

        /** Builder setters are usually {@code name(value)}, sometimes {@code setName(value)}. */
        MethodHandle setterFor(String propertyName, Class<?> propertyType) {
            Method method = publicMethod(builderType, propertyName, propertyType);
            if (method == null) {
                method = publicMethod(builderType, "set" + capitalise(propertyName), propertyType);
            }
            // The return value is ignored, so a void or fluent setter both work.
            return method == null ? null : unreflect(builderLookup, method,
                    MethodType.methodType(void.class, Object.class, Object.class));
        }
    }

    /** A constructor whose parameter names map one-to-one onto the properties. */
    private record AllArgs(MethodHandle constructor, int[] order) {

        static AllArgs resolve(Class<?> beanType, List<String> propertyNames, MethodHandles.Lookup lookup) {
            for (Constructor<?> candidate : beanType.getDeclaredConstructors()) {
                if (candidate.getParameterCount() != propertyNames.size() || propertyNames.isEmpty()) {
                    continue;
                }
                Parameter[] parameters = candidate.getParameters();
                int[] order = new int[parameters.length];
                boolean usable = true;
                for (int i = 0; i < parameters.length && usable; i++) {
                    // Without -parameters the names are arg0, arg1, ... which would be guessing.
                    int index = parameters[i].isNamePresent()
                            ? propertyNames.indexOf(parameters[i].getName())
                            : -1;
                    if (index < 0) {
                        usable = false;
                    } else {
                        order[i] = index;
                    }
                }
                if (!usable || java.util.Arrays.stream(order).distinct().count() != propertyNames.size()) {
                    continue;
                }
                try {
                    MethodHandle handle = lookup.unreflectConstructor(candidate)
                            .asType(MethodType.genericMethodType(parameters.length))
                            .asSpreader(Object[].class, parameters.length);
                    return new AllArgs(handle, order);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
            return null;
        }
    }
}
