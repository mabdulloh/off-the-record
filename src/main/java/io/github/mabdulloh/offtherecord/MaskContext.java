package io.github.mabdulloh.offtherecord;

/**
 * What a {@link MaskStrategy} is told about the property it is masking.
 *
 * <p>It does not carry the object being masked, on purpose. A strategy for one property has no business
 * reading its siblings.
 *
 * @param propertyName  the record component, field, or bean property name
 * @param declaringType the record or class the property belongs to
 * @param propertyType  the declared type of the property, and the type the return value must fit
 * @param preserveStart {@link Mask#preserveStart()} as written on the annotation
 * @param preserveEnd   {@link Mask#preserveEnd()} as written on the annotation
 * @param maskChar      {@link Mask#maskChar()} as written on the annotation
 */
public record MaskContext(
        String propertyName,
        Class<?> declaringType,
        Class<?> propertyType,
        int preserveStart,
        int preserveEnd,
        char maskChar) {

    /** Describes the property as {@code Type.name}, for error messages. */
    @Override
    public String toString() {
        return declaringType.getSimpleName() + "." + propertyName;
    }
}
