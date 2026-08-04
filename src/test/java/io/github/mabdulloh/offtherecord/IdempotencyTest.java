package io.github.mabdulloh.offtherecord;

import static io.github.mabdulloh.offtherecord.MaskType.EMAIL;
import static io.github.mabdulloh.offtherecord.MaskType.NULLIFY;
import static io.github.mabdulloh.offtherecord.MaskType.PARTIAL;
import static io.github.mabdulloh.offtherecord.MaskType.REDACT_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** mask(mask(x)) must equal mask(x), for every type and every awkward input. */
class IdempotencyTest {

    record AllTypes(
            @Mask(REDACT_ALL) String secret,
            @Mask(value = PARTIAL, preserveStart = 2, preserveEnd = 4) String card,
            @Mask(EMAIL) String email,
            @Mask(NULLIFY) String dropped,
            String untouched) {}

    record Card(@Mask(value = PARTIAL, preserveEnd = 4) String number) {}

    record Holder(List<Card> cards, Map<String, Card> vault) {}

    @ParameterizedTest
    @ValueSource(strings = {
            "4532111122228888",
            "a",
            "ab",
            "",
            "1234567",
            "already************masked",
            "*****",
    })
    void maskingTwiceEqualsMaskingOnce(String input) {
        AllTypes once = Masker.mask(new AllTypes(input, input, input, input, input));
        AllTypes twice = Masker.mask(once);

        assertEquals(once, twice);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "john.doe@domain.com",
            "a@domain.com",
            "ab@domain.com",
            "@domain.com",
            "noatsign",
            "j*******@domain.com",
            "*@domain.com",
            "weird@a@domain.com",
    })
    void emailMaskingIsIdempotent(String input) {
        record Email(@Mask(EMAIL) String value) {}

        Email once = Masker.mask(new Email(input));
        assertEquals(once, Masker.mask(once));
    }

    @Test
    void maskingTwiceThroughContainersEqualsMaskingOnce() {
        Holder once = Masker.mask(new Holder(
                List.of(new Card("4532111122228888"), new Card("5500005555555559")),
                Map.of("primary", new Card("4532111122228888"))));
        Holder twice = Masker.mask(once);

        assertEquals(once.cards(), twice.cards());
        assertEquals(once.vault(), twice.vault());
    }

    @Test
    void maskingAnAlreadyMaskedRecordChangesNothing() {
        // Equal, not identical: PARTIAL rebuilds the string, so a second pass still allocates a copy.
        Card once = Masker.mask(new Card("4532111122228888"));
        assertEquals(once, Masker.mask(once));
    }

    @Test
    void untouchedComponentsStaySettledAcrossRepeatedMasking() {
        AllTypes once = Masker.mask(new AllTypes("s", "4532111122228888", "a@b.com", "x", "keep me"));
        assertEquals("keep me", Masker.mask(once).untouched());
    }
}
