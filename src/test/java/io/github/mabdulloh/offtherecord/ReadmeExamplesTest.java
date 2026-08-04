package io.github.mabdulloh.offtherecord;

import static io.github.mabdulloh.offtherecord.MaskType.EMAIL;
import static io.github.mabdulloh.offtherecord.MaskType.NULLIFY;
import static io.github.mabdulloh.offtherecord.MaskType.PARTIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins every example printed in README.md. If one of these fails, the README is lying.
 */
class ReadmeExamplesTest {

    record Address(
            String city,
            @Mask(value = PARTIAL, preserveStart = 2) String zipCode) {}

    record UserDto(
            String id,
            @Mask(EMAIL) String email,
            @Mask(value = PARTIAL, preserveEnd = 4) String creditCard,
            @Mask String password,
            Address address) {}

    record PaymentMethod(
            String provider,
            @Mask(value = PARTIAL, preserveEnd = 4) String cardNumber) {}

    record Account(
            String username,
            List<PaymentMethod> savedCards,
            Map<String, PaymentMethod> vault) {}

    record TableRow(
            @Mask String secret,
            @Mask(value = PARTIAL, preserveEnd = 4) String card,
            @Mask(EMAIL) String email,
            @Mask(NULLIFY) String dropped) {}

    record Contact(String name, @Mask(EMAIL) String email) {}

    @Nested
    @DisplayName("README: installing / getting started")
    class GettingStarted {

        @Test
        void producesTheDocumentedOutputs() {
            Contact masked = Masker.mask(new Contact("Abdulloh", "developer@company.com"));

            assertEquals("Abdulloh", masked.name());
            assertEquals("d********@company.com", masked.email());
        }
    }

    @Nested
    @DisplayName("README: mask types table")
    class MaskTypesTable {

        @Test
        void producesTheDocumentedOutputs() {
            TableRow masked = Masker.mask(new TableRow(
                    "hunter2-super-secret",
                    "4532111122228888",
                    "john.doe@domain.com",
                    "anything"));

            assertEquals("********", masked.secret());
            assertEquals("************8888", masked.card());
            assertEquals("j*******@domain.com", masked.email());
            assertNull(masked.dropped());
        }
    }

    @Nested
    @DisplayName("README: nested Records")
    class NestedRecords {

        @Test
        void producesTheDocumentedOutputs() {
            var user = new UserDto(
                    "usr_9981",
                    "developer@company.com",
                    "4532111122228888",
                    "s3cr3t-p4ssw0rd",
                    new Address("Jakarta", "10110"));

            UserDto masked = Masker.mask(user);

            assertEquals("usr_9981", masked.id());
            assertEquals("d********@company.com", masked.email());
            assertEquals("************8888", masked.creditCard());
            assertEquals("********", masked.password());
            assertEquals("Jakarta", masked.address().city());
            assertEquals("10***", masked.address().zipCode());
        }

        @Test
        void leavesTheOriginalUntouched() {
            var address = new Address("Jakarta", "10110");
            var user = new UserDto("usr_9981", "developer@company.com", "4532111122228888",
                    "s3cr3t-p4ssw0rd", address);

            Masker.mask(user);

            assertEquals("developer@company.com", user.email());
            assertEquals("4532111122228888", user.creditCard());
            assertEquals("s3cr3t-p4ssw0rd", user.password());
            assertEquals("10110", address.zipCode());
        }
    }

    @Nested
    @DisplayName("README: Records inside collections")
    class RecordsInCollections {

        @Test
        void masksEveryElementOfAListAndEveryMapValue() {
            var account = new Account(
                    "abdulloh",
                    List.of(new PaymentMethod("visa", "4532111122228888"),
                            new PaymentMethod("mastercard", "5500005555555559")),
                    Map.of("primary", new PaymentMethod("visa", "4532111122228888")));

            Account masked = Masker.mask(account);

            assertEquals("abdulloh", masked.username());
            assertEquals("visa", masked.savedCards().get(0).provider());
            assertEquals("************8888", masked.savedCards().get(0).cardNumber());
            assertEquals("************5559", masked.savedCards().get(1).cardNumber());
            assertEquals("************8888", masked.vault().get("primary").cardNumber());
        }

        @Test
        void masksACollectionPassedInDirectly() {
            List<UserDto> users = List.of(
                    new UserDto("a", "one@x.com", "4532111122228888", "p1", new Address("Bandung", "40111")),
                    new UserDto("b", "two@y.com", "5500005555555559", "p2", new Address("Medan", "20111")));

            List<UserDto> masked = Masker.mask(users);

            assertEquals("o**@x.com", masked.get(0).email());
            assertEquals("t**@y.com", masked.get(1).email());
            assertEquals("40***", masked.get(0).address().zipCode());
        }
    }
}
