package io.github.mabdulloh.offtherecord;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a property as sensitive, so {@link Masker#mask(Object)} redacts it.
 *
 * <p>On a record, annotate the component:
 *
 * <pre>{@code
 * public record UserDto(
 *         String id,
 *         @Mask(MaskType.EMAIL) String email,
 *         @Mask(value = MaskType.PARTIAL, preserveEnd = 4) String creditCard,
 *         @Mask String password) {}
 * }</pre>
 *
 * <p>On a class, annotate either the field or its getter, but not both:
 *
 * <pre>{@code
 * public class UserBean {
 *     @Mask(MaskType.EMAIL) private String email;
 *     public String getEmail() { return email; }
 *     public void setEmail(String email) { this.email = email; }
 * }
 * }</pre>
 *
 * <p>Properties without this annotation are copied over untouched, except that nested records, beans, and
 * containers of them are still traversed.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Mask {

    /**
     * One of the built-in strategies. Defaults to {@link MaskType#REDACT_ALL}, which leaks the least.
     *
     * <p>Mutually exclusive with {@link #with()}.
     *
     * @return the built-in strategy to apply to this property
     */
    MaskType value() default MaskType.REDACT_ALL;

    /**
     * A custom strategy, instead of one of the built-ins. The class needs a public no-argument
     * constructor; one instance is created per strategy class and shared.
     *
     * <p>The default, {@code MaskStrategy.class} itself, means "no custom strategy" and is never
     * instantiated.
     *
     * @return the strategy implementation to apply to this property
     */
    Class<? extends MaskStrategy> with() default MaskStrategy.class;

    /**
     * How many leading characters {@link MaskType#PARTIAL} preserves. Must not be negative. Also passed
     * through to a custom strategy on {@link MaskContext}.
     *
     * @return the number of leading characters to keep
     */
    int preserveStart() default 0;

    /**
     * How many trailing characters {@link MaskType#PARTIAL} preserves. Must not be negative. Also passed
     * through to a custom strategy on {@link MaskContext}.
     *
     * @return the number of trailing characters to keep
     */
    int preserveEnd() default 0;

    /**
     * The character written in place of a redacted character, and the one used for fail-closed
     * redactions. Honoured by every built-in except {@link MaskType#NULLIFY}, and passed through to a
     * custom strategy on {@link MaskContext}.
     *
     * @return the replacement character
     */
    char maskChar() default '*';
}
