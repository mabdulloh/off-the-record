package io.github.mabdulloh.offtherecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The public {@link MaskStrategy} SPI, and the invariants the engine enforces around it. */
class MaskStrategyTest {

    // --- strategies -------------------------------------------------------------------------------

    /** Well behaved, and counts how often the engine instantiates it. */
    public static final class TagStrategy implements MaskStrategy {
        static final AtomicInteger INSTANCES = new AtomicInteger();

        public TagStrategy() {
            INSTANCES.incrementAndGet();
        }

        @Override
        public Object mask(Object value, MaskContext context) {
            return "<" + context.propertyName() + ">";
        }
    }

    /** Throws, which must never reach the caller. */
    public static final class BoomStrategy implements MaskStrategy {
        @Override
        public Object mask(Object value, MaskContext context) {
            throw new IllegalStateException("boom");
        }
    }

    /** Returns something the property cannot hold. */
    public static final class WrongTypeStrategy implements MaskStrategy {
        @Override
        public Object mask(Object value, MaskContext context) {
            return 42;
        }
    }

    /** Casts carelessly, which is the same failure mode as a bad type. */
    public static final class CarelessCastStrategy implements MaskStrategy {
        @Override
        public Object mask(Object value, MaskContext context) {
            return ((StringBuilder) value).reverse().toString();
        }
    }

    /** Proves the SPI reaches types the built-ins refuse. */
    public static final class YearOnlyStrategy implements MaskStrategy {
        @Override
        public Object mask(Object value, MaskContext context) {
            return ((LocalDate) value).withDayOfYear(1);
        }
    }

    /** Would fail on null, which the engine promises never to hand over. */
    public static final class NullHostileStrategy implements MaskStrategy {
        @Override
        public Object mask(Object value, MaskContext context) {
            return value.toString().isEmpty() ? "empty" : "present";
        }
    }

    /** Captures what it was told, so the context can be asserted on. */
    public static final class ContextCapturingStrategy implements MaskStrategy {
        static volatile MaskContext lastSeen;

        @Override
        public Object mask(Object value, MaskContext context) {
            lastSeen = context;
            return "seen";
        }
    }

    /** Cannot be instantiated by the engine. */
    public static final class NoDefaultConstructor implements MaskStrategy {
        public NoDefaultConstructor(String unused) {
        }

        @Override
        public Object mask(Object value, MaskContext context) {
            return "never";
        }
    }

    // --- holders ----------------------------------------------------------------------------------

    record Tagged(@Mask(with = TagStrategy.class) String value) {}

    record Exploding(@Mask(with = BoomStrategy.class) String value) {}

    record WrongType(@Mask(with = WrongTypeStrategy.class) String value) {}

    record Careless(@Mask(with = CarelessCastStrategy.class) String value) {}

    record ExplodingNumber(@Mask(with = BoomStrategy.class) int amount) {}

    record ExplodingBoxed(@Mask(with = BoomStrategy.class) Integer amount) {}

    record Birthday(@Mask(with = YearOnlyStrategy.class) LocalDate dateOfBirth) {}

    record NullHostile(@Mask(with = NullHostileStrategy.class) String value) {}

    record Described(@Mask(with = ContextCapturingStrategy.class, preserveStart = 3, preserveEnd = 2,
            maskChar = '#') String nationalId) {}

    record Uninstantiable(@Mask(with = NoDefaultConstructor.class) String value) {}

    record Conflicted(@Mask(value = MaskType.EMAIL, with = TagStrategy.class) String value) {}

    static class TaggedBean {
        @Mask(with = TagStrategy.class) private String value;

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    // --- tests ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("A custom strategy is used")
    class Selection {

        @Test
        void onARecordComponent() {
            assertEquals("<value>", Masker.mask(new Tagged("secret")).value());
        }

        @Test
        void onABeanProperty() {
            TaggedBean bean = new TaggedBean();
            bean.setValue("secret");

            assertEquals("<value>", Masker.mask(bean).getValue());
        }

        @Test
        void onATypeTheBuiltInsRefuse() {
            Birthday masked = Masker.mask(new Birthday(LocalDate.of(1993, 7, 24)));

            assertEquals(LocalDate.of(1993, 1, 1), masked.dateOfBirth());
        }

        @Test
        void andIsInstantiatedOnlyOnce() {
            Masker.mask(new Tagged("a"));
            Masker.mask(new Tagged("b"));
            TaggedBean bean = new TaggedBean();
            bean.setValue("c");
            Masker.mask(bean);

            assertEquals(1, TagStrategy.INSTANCES.get());
        }
    }

    @Nested
    @DisplayName("The engine holds the invariants")
    class Invariants {

        @Test
        void nullIsNeverHandedToAStrategy() {
            // NullHostileStrategy would throw on null. Reaching it at all is the failure.
            assertNull(Masker.mask(new NullHostile(null)).value());
        }

        @Test
        void aStrategyThatThrowsBecomesAFullRedaction() {
            assertEquals("********", Masker.mask(new Exploding("4532111122228888")).value());
        }

        @Test
        void aReturnValueOfTheWrongTypeBecomesAFullRedaction() {
            assertEquals("********", Masker.mask(new WrongType("4532111122228888")).value());
        }

        @Test
        void aCarelessCastBecomesAFullRedaction() {
            assertEquals("********", Masker.mask(new Careless("4532111122228888")).value());
        }

        @Test
        void failingClosedOnAPrimitiveUsesItsZeroValue() {
            // "********" cannot be written to an int, so the redaction has to be type-aware.
            assertEquals(0, Masker.mask(new ExplodingNumber(50_000)).amount());
        }

        @Test
        void failingClosedOnABoxedTypeUsesNull() {
            assertNull(Masker.mask(new ExplodingBoxed(50_000)).amount());
        }

        @Test
        void noneOfThoseFailuresLeakTheOriginal() {
            assertTrue(!Masker.mask(new Exploding("4532111122228888")).value().contains("4532"));
            assertTrue(!Masker.mask(new WrongType("4532111122228888")).value().contains("4532"));
        }
    }

    @Nested
    @DisplayName("MaskContext")
    class Context {

        @Test
        void describesThePropertyAndCarriesTheAnnotationSettings() {
            Masker.mask(new Described("3174010101010001"));
            MaskContext seen = ContextCapturingStrategy.lastSeen;

            assertEquals("nationalId", seen.propertyName());
            assertEquals(Described.class, seen.declaringType());
            assertEquals(String.class, seen.propertyType());
            assertEquals(3, seen.preserveStart());
            assertEquals(2, seen.preserveEnd());
            assertEquals('#', seen.maskChar());
            assertEquals("Described.nationalId", seen.toString());
        }

        @Test
        void toStringFormatsDeclaringTypeDotPropertyName() {
            MaskContext context = new MaskContext("email", String.class, String.class, 0, 0, '*');

            assertEquals("String.email", context.toString());
        }
    }

    @Nested
    @DisplayName("Misconfiguration")
    class Misconfiguration {

        @Test
        void settingBothValueAndWithIsRejected() {
            MaskingException thrown =
                    assertThrows(MaskingException.class, () -> Masker.mask(new Conflicted("x")));

            assertTrue(thrown.getMessage().contains("both value() and with()"), thrown.getMessage());
        }

        @Test
        void aStrategyWithoutANoArgumentConstructorIsRejected() {
            MaskingException thrown =
                    assertThrows(MaskingException.class, () -> Masker.mask(new Uninstantiable("x")));

            assertTrue(thrown.getMessage().contains("no-argument constructor"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("Built-ins are strategies too")
    class BuiltIns {

        @Test
        void maskTypeImplementsThePublicInterface() {
            // If the SPI could not express the built-ins, it would not be enough for anyone else's.
            assertTrue(MaskStrategy.class.isAssignableFrom(MaskType.class));
        }

        @Test
        void andCanBeCalledDirectlyThroughIt() {
            MaskStrategy strategy = MaskType.EMAIL;
            MaskContext context =
                    new MaskContext("email", String.class, String.class, 0, 0, '*');

            assertEquals("j*******@domain.com", strategy.mask("john.doe@domain.com", context));
        }
    }
}
