package io.github.mabdulloh.offtherecord;

/**
 * A redaction rule. Implement this to mask something the built-in {@link MaskType} values cannot, then
 * point at it with {@code @Mask(with = MyStrategy.class)}.
 *
 * <pre>{@code
 * public final class NikStrategy implements MaskStrategy {
 *     @Override
 *     public Object mask(Object value, MaskContext context) {
 *         String nik = (String) value;
 *         return nik.substring(0, 2) + "**********" + nik.substring(12);
 *     }
 * }
 * }</pre>
 *
 * <h2>What the engine guarantees</h2>
 * You do not have to defend against these, and you should not try:
 * <ul>
 *   <li>{@code value} is never {@code null}. A null property stays null without consulting a strategy.</li>
 *   <li>A return value that cannot be assigned to the property is replaced by a full redaction.</li>
 *   <li>Anything thrown, including a {@code ClassCastException} from a careless cast, becomes a full
 *       redaction. It never reaches the caller and never leaves the original value in place.</li>
 * </ul>
 *
 * <h2>What you must guarantee</h2>
 * <ul>
 *   <li><b>Stateless and thread safe.</b> One instance per strategy class is created and shared across
 *       every thread and every call. The engine does not synchronise on it.</li>
 *   <li><b>Idempotent.</b> {@code mask(mask(x))} must equal {@code mask(x)}. Values get masked twice more
 *       often than you would think.</li>
 *   <li><b>A public no-argument constructor</b>, so the engine can instantiate you.</li>
 * </ul>
 *
 * <p>The engine cannot check that you actually redact anything, and deliberately does not try: a correct
 * strategy returns its input unchanged when the input is already masked, so "output equals input" is not
 * evidence of a bug.
 */
public interface MaskStrategy {

    /**
     * Returns the redacted form of {@code value}.
     *
     * @param value   the value to redact, never {@code null}
     * @param context which property is being masked, and the settings from its {@link Mask} annotation
     * @return the redacted value, which must be assignable to {@link MaskContext#propertyType()}
     */
    Object mask(Object value, MaskContext context);
}
