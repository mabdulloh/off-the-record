package io.github.mabdulloh.offtherecord;

/**
 * The redaction strategies available in {@code off-the-record} 0.1.0.
 *
 * <p>All of them are idempotent: masking an already masked value produces the same value.
 */
public enum MaskType {

    /**
     * Replaces the value with a fixed-length mask, so the output reveals nothing about the input,
     * not even its length. This is the default and the safest choice.
     *
     * <p>{@code "hunter2-super-secret"} becomes {@code "********"}.
     *
     * <p>Applies to {@code String} components only.
     */
    REDACT_ALL,

    /**
     * Preserves {@link Mask#preserveStart()} leading characters and {@link Mask#preserveEnd()}
     * trailing characters and masks everything in between. The output has the same length as the
     * input, which means it reveals that length.
     *
     * <p>With {@code preserveEnd = 4}, {@code "4532111122228888"} becomes
     * {@code "************8888"}.
     *
     * <p>If the preserved windows cover the whole value, the entire value is masked instead. Applies
     * to {@code String} components only.
     */
    PARTIAL,

    /**
     * Preserves the first character of the local part and the whole domain.
     *
     * <p>{@code "john.doe@domain.com"} becomes {@code "j*******@domain.com"}.
     *
     * <p>A single-character local part is masked entirely rather than preserved, and a value with no
     * {@code @} is masked entirely. Applies to {@code String} components only.
     */
    EMAIL,

    /**
     * Replaces the value with {@code null}.
     *
     * <p>Applies to any component whose type is not primitive.
     */
    NULLIFY
}
