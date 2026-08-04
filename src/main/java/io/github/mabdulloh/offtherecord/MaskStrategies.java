package io.github.mabdulloh.offtherecord;

/**
 * The redaction rules. Pure functions on strings, no state, no knowledge of Records.
 *
 * <p>Every rule here is idempotent and never returns its input when it was asked to redact it.
 */
final class MaskStrategies {

    /**
     * Output length of {@link MaskType#REDACT_ALL}. Fixed on purpose: a constant length means the
     * output says nothing about the original, not even how long it was.
     */
    private static final int REDACT_ALL_LENGTH = 8;

    private MaskStrategies() {
    }

    /** Applies {@code type} to {@code value}. {@code null} in, {@code null} out. */
    static String apply(String value, MaskType type, int preserveStart, int preserveEnd, char maskChar) {
        if (type == MaskType.REDACT_ALL) {
            // No early return for the empty string: a redacted empty value should look like any other.
            return value == null ? null : fullRedaction(maskChar);
        }
        if (value == null || value.isEmpty()) {
            return value;
        }
        return switch (type) {
            case PARTIAL -> partial(value, preserveStart, preserveEnd, maskChar);
            case EMAIL -> email(value, maskChar);
            // Unreachable: handled above, and NULLIFY never reaches a string strategy. Listed rather
            // than defaulted so that adding a MaskType breaks the build instead of silently landing here.
            case REDACT_ALL, NULLIFY -> fullRedaction(maskChar);
        };
    }

    /** The value used when something goes wrong. Reveals nothing, including length. */
    static String fullRedaction(char maskChar) {
        return repeat(maskChar, REDACT_ALL_LENGTH);
    }

    /**
     * Keeps the first {@code preserveStart} and last {@code preserveEnd} characters, masks the rest.
     * Falls back to masking everything when the preserved windows would cover the whole value, so a
     * short value is never leaked by a generous configuration.
     */
    private static String partial(String value, int preserveStart, int preserveEnd, char maskChar) {
        int length = value.length();
        if (preserveStart + preserveEnd >= length) {
            return repeat(maskChar, length);
        }
        return value.substring(0, preserveStart)
                + repeat(maskChar, length - preserveStart - preserveEnd)
                + value.substring(length - preserveEnd);
    }

    /**
     * Keeps the first character of the local part and the whole domain.
     *
     * <p>Two safety cases: a value with no usable {@code @} is masked in full rather than passed
     * through, and a single-character local part is masked rather than "preserved", since preserving it
     * would reveal all of it.
     */
    private static String email(String value, char maskChar) {
        int at = value.lastIndexOf('@');
        if (at <= 0) {
            return repeat(maskChar, value.length());
        }
        String domain = value.substring(at);
        return at == 1
                ? maskChar + domain
                : value.charAt(0) + repeat(maskChar, at - 1) + domain;
    }

    private static String repeat(char c, int count) {
        return String.valueOf(c).repeat(count);
    }
}
