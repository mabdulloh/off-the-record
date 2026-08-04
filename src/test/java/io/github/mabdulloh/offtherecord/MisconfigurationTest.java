package io.github.mabdulloh.offtherecord;

import static io.github.mabdulloh.offtherecord.MaskType.EMAIL;
import static io.github.mabdulloh.offtherecord.MaskType.NULLIFY;
import static io.github.mabdulloh.offtherecord.MaskType.PARTIAL;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Annotation usage the engine cannot honour fails loudly, at first use, naming the component.
 */
class MisconfigurationTest {

    record PartialOnInt(@Mask(PARTIAL) int amount) {}

    record EmailOnList(@Mask(EMAIL) List<String> recipients) {}

    record NullifyOnPrimitive(@Mask(NULLIFY) long balance) {}

    record NegativePreserveStart(@Mask(value = PARTIAL, preserveStart = -1) String value) {}

    record NegativePreserveEnd(@Mask(value = PARTIAL, preserveEnd = -3) String value) {}

    /** Individually valid, but the sum overflows int and would slip past the "window covers it all" guard. */
    record OverflowingPreserveWindow(
            @Mask(value = PARTIAL, preserveStart = Integer.MAX_VALUE, preserveEnd = Integer.MAX_VALUE)
            String value) {}

    @Test
    void aStringStrategyOnAPrimitiveComponentIsRejected() {
        MaskingException thrown = assertThrows(MaskingException.class, () -> Masker.mask(new PartialOnInt(10)));

        assertTrue(thrown.getMessage().contains("PartialOnInt.amount"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("PARTIAL"), thrown.getMessage());
    }

    @Test
    void aStringStrategyOnAnObjectComponentIsRejected() {
        MaskingException thrown =
                assertThrows(MaskingException.class, () -> Masker.mask(new EmailOnList(List.of("a@b.com"))));

        assertTrue(thrown.getMessage().contains("EmailOnList.recipients"), thrown.getMessage());
    }

    @Test
    void nullifyOnAPrimitiveComponentIsRejected() {
        MaskingException thrown =
                assertThrows(MaskingException.class, () -> Masker.mask(new NullifyOnPrimitive(5L)));

        assertTrue(thrown.getMessage().contains("NullifyOnPrimitive.balance"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("primitive"), thrown.getMessage());
    }

    @Test
    void aNegativePreserveStartIsRejected() {
        MaskingException thrown =
                assertThrows(MaskingException.class, () -> Masker.mask(new NegativePreserveStart("value")));

        assertTrue(thrown.getMessage().contains("NegativePreserveStart.value"), thrown.getMessage());
    }

    @Test
    void aNegativePreserveEndIsRejected() {
        MaskingException thrown =
                assertThrows(MaskingException.class, () -> Masker.mask(new NegativePreserveEnd("value")));

        assertTrue(thrown.getMessage().contains("NegativePreserveEnd.value"), thrown.getMessage());
    }

    @Test
    void aPreserveWindowWhoseSumOverflowsIsRejected() {
        MaskingException thrown = assertThrows(MaskingException.class,
                () -> Masker.mask(new OverflowingPreserveWindow("4532111122228888")));

        assertTrue(thrown.getMessage().contains("OverflowingPreserveWindow.value"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("overflow"), thrown.getMessage());
    }

    @Test
    void rejectingAMisconfigurationNeverEchoesTheValue() {
        String secret = "4532111122228888";

        MaskingException thrown = assertThrows(MaskingException.class,
                () -> Masker.mask(new OverflowingPreserveWindow(secret)));

        assertFalse(thrown.getMessage().contains(secret), "the exception message leaked the value");
    }
}
