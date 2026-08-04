package io.github.mabdulloh.offtherecord;

/**
 * Thrown when a masked copy cannot be produced.
 *
 * <p>This is deliberately an exception rather than a silent fallback: returning the original object
 * when masking fails would leak the values the caller asked to redact. Messages describe the record
 * and component at fault and never contain the sensitive value itself.
 */
public class MaskingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what went wrong, naming the record and component where relevant
     */
    public MaskingException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong, naming the record and component where relevant
     * @param cause   the underlying failure
     */
    public MaskingException(String message, Throwable cause) {
        super(message, cause);
    }
}
