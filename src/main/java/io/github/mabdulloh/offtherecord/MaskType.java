package io.github.mabdulloh.offtherecord;

/**
 * The redaction strategies that ship with the library.
 *
 * <p>These are {@link MaskStrategy} implementations like any other, which is the point: if the SPI could
 * not express the built-ins, it would not be expressive enough for yours either.
 *
 * <p>All of them are idempotent. None of them is ever handed a {@code null}; see {@link MaskStrategy}.
 */
public enum MaskType implements MaskStrategy {

    /**
     * Replaces the value with a fixed-length mask, so the output reveals nothing about the input, not
     * even its length. This is the default and the safest choice.
     *
     * <p>{@code "hunter2-super-secret"} becomes {@code "********"}.
     *
     * <p>Applies to {@code String} properties only.
     */
    REDACT_ALL {
        @Override
        public Object mask(Object value, MaskContext context) {
            return fullRedaction(context.maskChar());
        }
    },

    /**
     * Preserves {@link Mask#preserveStart()} leading characters and {@link Mask#preserveEnd()} trailing
     * characters and masks everything in between. The output has the same length as the input, which
     * means it reveals that length.
     *
     * <p>With {@code preserveEnd = 4}, {@code "4532111122228888"} becomes {@code "************8888"}.
     *
     * <p>If the preserved windows cover the whole value, the entire value is masked instead. Applies to
     * {@code String} properties only.
     */
    PARTIAL {
        @Override
        public Object mask(Object value, MaskContext context) {
            String text = (String) value;
            if (text.isEmpty()) {
                return text;
            }
            int length = text.length();
            int start = context.preserveStart();
            int end = context.preserveEnd();
            if (start + end >= length) {
                return repeat(context.maskChar(), length);
            }
            return text.substring(0, start)
                    + repeat(context.maskChar(), length - start - end)
                    + text.substring(length - end);
        }
    },

    /**
     * Preserves the first character of the local part and the whole domain.
     *
     * <p>{@code "john.doe@domain.com"} becomes {@code "j*******@domain.com"}.
     *
     * <p>Two safety cases: a value with no usable {@code @} is masked in full rather than passed through,
     * and a single-character local part is masked rather than "preserved", since preserving it would
     * reveal all of it. Applies to {@code String} properties only.
     */
    EMAIL {
        @Override
        public Object mask(Object value, MaskContext context) {
            String text = (String) value;
            if (text.isEmpty()) {
                return text;
            }
            char maskChar = context.maskChar();
            int at = text.lastIndexOf('@');
            if (at <= 0) {
                return repeat(maskChar, text.length());
            }
            String domain = text.substring(at);
            return at == 1
                    ? maskChar + domain
                    : text.charAt(0) + repeat(maskChar, at - 1) + domain;
        }
    },

    /**
     * Replaces the value with {@code null}.
     *
     * <p>Applies to any property whose type is not primitive.
     */
    NULLIFY {
        @Override
        public Object mask(Object value, MaskContext context) {
            return null;
        }
    };

    /**
     * Output length of {@link #REDACT_ALL}, and of every fail-closed redaction. Fixed on purpose: a
     * constant length means the output says nothing about the original, not even how long it was.
     */
    private static final int REDACT_ALL_LENGTH = 8;

    /** The value used for {@link #REDACT_ALL} and whenever something goes wrong. */
    static String fullRedaction(char maskChar) {
        return repeat(maskChar, REDACT_ALL_LENGTH);
    }

    private static String repeat(char maskChar, int count) {
        return String.valueOf(maskChar).repeat(count);
    }
}
