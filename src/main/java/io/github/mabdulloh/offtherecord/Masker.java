package io.github.mabdulloh.offtherecord;

/**
 * Produces a redacted copy of a record or a bean.
 *
 * <pre>{@code
 * UserDto safe = Masker.mask(user);
 * }</pre>
 *
 * <p>The argument is never modified. Properties annotated with {@link Mask} are redacted, nested records
 * and beans are masked recursively, and anything held in a {@code List}, {@code Set}, {@code Map} value,
 * {@code Optional}, or object array is traversed too.
 *
 * <p>An object reachable from two places yields one masked copy, so the shape of the graph survives.
 * Reference cycles terminate, and are masked outright when the class can be built through a no-argument
 * constructor and setters.
 *
 * <p>This class is thread safe and holds no state. Per-class metadata is cached, so the first call for a
 * given type is the expensive one.
 */
public final class Masker {

    private Masker() {
    }

    /**
     * Returns a redacted copy of {@code value}.
     *
     * <p>Accepts a record, a bean, or a container of either. Values with nothing to walk into, such as a
     * {@code String} or anything from the JDK, are returned as they are. If nothing actually changed, the
     * original instance is returned rather than an identical copy.
     *
     * @param value the object to mask, may be {@code null}
     * @param <T>   the type of the object, preserved in the result
     * @return the masked copy, or {@code null} if {@code value} was {@code null}
     * @throws MaskingException if a {@link Mask} annotation is used on an unsupported property, if a
     *                          masked copy cannot be constructed, or if a reference cycle runs through a
     *                          type that cannot be built blank and populated afterwards
     */
    @SuppressWarnings("unchecked")
    public static <T> T mask(T value) {
        return (T) new MaskingSession().mask(value);
    }
}
