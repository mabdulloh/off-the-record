package io.github.mabdulloh.offtherecord;

import static io.github.mabdulloh.offtherecord.MaskType.EMAIL;
import static io.github.mabdulloh.offtherecord.MaskType.PARTIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The central safety property: no failure path hands back the value the caller asked to redact.
 */
class FailClosedTest {

    private static final String SECRET_CARD = "4532111122228888";

    /** A compact constructor that cannot accept a masked value. */
    record DigitsOnlyCard(@Mask(value = PARTIAL, preserveEnd = 4) String number) {
        DigitsOnlyCard {
            if (number != null && !number.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("card number must be digits only");
            }
        }
    }

    record Profile(
            @Mask(EMAIL) String email,
            @Mask(value = PARTIAL, preserveEnd = 4) String card,
            @Mask String password) {}

    @Test
    void aValidatingConstructorCausesAFailureRatherThanReturningTheOriginal() {
        DigitsOnlyCard original = new DigitsOnlyCard(SECRET_CARD);

        MaskingException thrown = assertThrows(MaskingException.class, () -> Masker.mask(original));

        assertTrue(thrown.getMessage().contains("DigitsOnlyCard"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("canonical constructor"), thrown.getMessage());
        assertEquals(IllegalArgumentException.class, thrown.getCause().getClass());
    }

    @Test
    void aFailureMessageNeverContainsTheSensitiveValue() {
        MaskingException thrown =
                assertThrows(MaskingException.class, () -> Masker.mask(new DigitsOnlyCard(SECRET_CARD)));

        assertFalse(thrown.getMessage().contains(SECRET_CARD), "the exception message leaked the value");
    }

    @Test
    void noOriginalValueSurvivesInTheMaskedObject() {
        Profile original = new Profile("developer@company.com", SECRET_CARD, "s3cr3t-p4ssw0rd");

        String rendered = Masker.mask(original).toString();

        for (String sensitive : List.of("developer", SECRET_CARD, "s3cr3t-p4ssw0rd")) {
            assertFalse(rendered.contains(sensitive), "masked output still contains " + sensitive);
        }
    }
}
