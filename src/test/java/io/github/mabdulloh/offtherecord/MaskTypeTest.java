package io.github.mabdulloh.offtherecord;

import static io.github.mabdulloh.offtherecord.MaskType.EMAIL;
import static io.github.mabdulloh.offtherecord.MaskType.NULLIFY;
import static io.github.mabdulloh.offtherecord.MaskType.PARTIAL;
import static io.github.mabdulloh.offtherecord.MaskType.REDACT_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Behaviour of each mask type, including the edge cases the requirements pin down. */
class MaskTypeTest {

    record Redacted(@Mask(REDACT_ALL) String value) {}

    record Partial(@Mask(value = PARTIAL, preserveStart = 2, preserveEnd = 4) String value) {}

    record PartialTail(@Mask(value = PARTIAL, preserveEnd = 4) String value) {}

    record PartialNone(@Mask(PARTIAL) String value) {}

    record Email(@Mask(EMAIL) String value) {}

    record Hashed(@Mask(value = PARTIAL, preserveEnd = 4, maskChar = '#') String value) {}

    record Nullified(@Mask(NULLIFY) String value) {}

    record NullifiedBox(@Mask(NULLIFY) Integer value) {}

    @Nested
    @DisplayName("REDACT_ALL")
    class RedactAll {

        @ParameterizedTest
        @ValueSource(strings = {"a", "", "short", "a much longer secret value indeed"})
        void alwaysProducesTheSameFixedLengthOutput(String input) {
            assertEquals("********", Masker.mask(new Redacted(input)).value());
        }

        @Test
        void keepsNullAsNull() {
            assertNull(Masker.mask(new Redacted(null)).value());
        }

        @Test
        void revealsNothingAboutTheInputLength() {
            String shortOne = Masker.mask(new Redacted("x")).value();
            String longOne = Masker.mask(new Redacted("xxxxxxxxxxxxxxxxxxxxxxxx")).value();
            assertEquals(shortOne, longOne);
        }
    }

    @Nested
    @DisplayName("PARTIAL")
    class Partials {

        @Test
        void masksTheMiddleAndKeepsBothEnds() {
            assertEquals("45**********8888", Masker.mask(new Partial("4532111122228888")).value());
        }

        @Test
        void keepsOnlyTheTailWhenPreserveStartIsZero() {
            assertEquals("************8888", Masker.mask(new PartialTail("4532111122228888")).value());
        }

        @Test
        void masksEverythingWhenNothingIsPreserved() {
            assertEquals("****************", Masker.mask(new PartialNone("4532111122228888")).value());
        }

        @Test
        void preservesLength() {
            String input = "4532111122228888";
            assertEquals(input.length(), Masker.mask(new PartialTail(input)).value().length());
        }

        @Test
        void masksEverythingWhenThePreservedWindowsCoverTheWholeValue() {
            // preserveStart 2 + preserveEnd 4 is 6, which is longer than the value itself.
            String masked = Masker.mask(new Partial("12345")).value();
            assertEquals("*****", masked);
            assertNotEquals("12345", masked);
        }

        @Test
        void masksEverythingWhenThePreservedWindowsExactlyCoverTheValue() {
            assertEquals("******", Masker.mask(new Partial("123456")).value());
        }

        @Test
        void keepsAnEmptyStringEmpty() {
            assertEquals("", Masker.mask(new PartialTail("")).value());
        }

        @Test
        void keepsNullAsNull() {
            assertNull(Masker.mask(new PartialTail(null)).value());
        }

        @Test
        void honoursACustomMaskCharacter() {
            assertEquals("############8888", Masker.mask(new Hashed("4532111122228888")).value());
        }
    }

    @Nested
    @DisplayName("EMAIL")
    class Emails {

        @Test
        void keepsTheFirstCharacterOfTheLocalPartAndTheDomain() {
            assertEquals("j*******@domain.com", Masker.mask(new Email("john.doe@domain.com")).value());
        }

        @Test
        void masksASingleCharacterLocalPartEntirely() {
            // Preserving "the first character" of a one-character local part would preserve all of it.
            assertEquals("*@domain.com", Masker.mask(new Email("a@domain.com")).value());
        }

        @Test
        void masksTheWholeValueWhenThereIsNoAtSign() {
            assertEquals("**********", Masker.mask(new Email("notanemail")).value());
        }

        @Test
        void masksTheWholeValueWhenTheLocalPartIsMissing() {
            assertEquals("***********", Masker.mask(new Email("@domain.com")).value());
        }

        @Test
        void usesTheLastAtSignAsTheSeparator() {
            assertEquals("q**********@domain.com", Masker.mask(new Email("quoted\"a@b\"@domain.com")).value());
        }

        @Test
        void keepsAnEmptyStringEmpty() {
            assertEquals("", Masker.mask(new Email("")).value());
        }

        @Test
        void keepsNullAsNull() {
            assertNull(Masker.mask(new Email(null)).value());
        }
    }

    @Nested
    @DisplayName("NULLIFY")
    class Nullify {

        @Test
        void dropsTheValue() {
            assertNull(Masker.mask(new Nullified("anything at all")).value());
        }

        @Test
        void worksOnAnyBoxedType() {
            assertNull(Masker.mask(new NullifiedBox(42)).value());
        }

        @Test
        void returnsTheSameInstanceWhenTheValueIsAlreadyNull() {
            Nullified already = new Nullified(null);
            assertSame(already, Masker.mask(already));
        }
    }
}
