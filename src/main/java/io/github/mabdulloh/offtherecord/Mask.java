package io.github.mabdulloh.offtherecord;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Record component as sensitive, so {@link Masker#mask(Object)} redacts it.
 *
 * <pre>{@code
 * public record UserDto(
 *         String id,
 *         @Mask(MaskType.EMAIL) String email,
 *         @Mask(value = MaskType.PARTIAL, preserveEnd = 4) String creditCard,
 *         @Mask String password) {}
 * }</pre>
 *
 * <p>Components without this annotation are copied over untouched, except that nested Records and
 * containers of Records are still traversed.
 *
 * <p>In 0.1.0 this annotation is only honoured on Record components. Regular classes are not
 * supported.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Mask {

    /**
     * The redaction strategy. Defaults to {@link MaskType#REDACT_ALL}, which leaks the least.
     *
     * @return the strategy to apply to this component
     */
    MaskType value() default MaskType.REDACT_ALL;

    /**
     * How many leading characters {@link MaskType#PARTIAL} preserves. Must not be negative.
     *
     * @return the number of leading characters to keep
     */
    int preserveStart() default 0;

    /**
     * How many trailing characters {@link MaskType#PARTIAL} preserves. Must not be negative.
     *
     * @return the number of trailing characters to keep
     */
    int preserveEnd() default 0;

    /**
     * The character written in place of a redacted character. Honoured by {@link MaskType#PARTIAL},
     * {@link MaskType#EMAIL}, and {@link MaskType#REDACT_ALL}.
     *
     * @return the replacement character
     */
    char maskChar() default '*';
}
