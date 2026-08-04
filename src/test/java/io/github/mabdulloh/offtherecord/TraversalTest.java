package io.github.mabdulloh.offtherecord;

import static io.github.mabdulloh.offtherecord.MaskType.PARTIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** How the engine walks an object graph, and what it does not walk into. */
class TraversalTest {

    record Card(@Mask(value = PARTIAL, preserveEnd = 4) String number) {}

    record Plain(String a, int b) {}

    record Holder(List<Card> list, Set<Card> set, Map<String, Card> map) {}

    record OptionalHolder(Optional<Card> card) {}

    record ArrayHolder(Card[] cards, int[] counts) {}

    record DeepHolder(List<List<List<Card>>> deep) {}

    record Wrapper(Object anything) {}

    record Node(String name, List<Node> children) {}

    record SortedHolder(SortedSet<Card> cards, SortedMap<String, Card> vault) {}

    record ConcreteHolder(ArrayList<Card> cards) {}

    private static final String CARD = "4532111122228888";
    private static final String MASKED = "************8888";

    @Nested
    @DisplayName("Records with nothing to mask")
    class NothingToMask {

        @Test
        void returnTheSameInstanceRatherThanACopy() {
            Plain plain = new Plain("nothing sensitive", 7);
            assertSame(plain, Masker.mask(plain));
        }

        @Test
        void collectionsWithNothingToMaskAreReturnedAsIs() {
            List<Plain> list = List.of(new Plain("a", 1));
            assertSame(list, Masker.mask(list));
        }
    }

    @Nested
    @DisplayName("Containers")
    class Containers {

        @Test
        void masksListElementsAndPreservesOrder() {
            Holder masked = Masker.mask(new Holder(
                    List.of(new Card("4532111122228888"), new Card("5500005555555559")),
                    Set.of(),
                    Map.of()));

            assertIterableEquals(
                    List.of(new Card("************8888"), new Card("************5559")),
                    masked.list());
        }

        @Test
        void masksSetElementsAndPreservesIterationOrder() {
            Set<Card> input = new LinkedHashSet<>();
            input.add(new Card("4532111122228888"));
            input.add(new Card("5500005555555559"));

            Holder masked = Masker.mask(new Holder(List.of(), input, Map.of()));

            assertIterableEquals(
                    List.of(new Card("************8888"), new Card("************5559")),
                    masked.set());
        }

        @Test
        void masksMapValuesAndLeavesKeysAlone() {
            Map<String, Card> input = new LinkedHashMap<>();
            input.put("primary", new Card(CARD));
            input.put("backup", new Card("5500005555555559"));

            Holder masked = Masker.mask(new Holder(List.of(), Set.of(), input));

            assertIterableEquals(List.of("primary", "backup"), masked.map().keySet());
            assertEquals(MASKED, masked.map().get("primary").number());
            assertEquals("************5559", masked.map().get("backup").number());
        }

        @Test
        void keepsSortedMapOrderingAndComparator() {
            TreeMap<String, Card> input = new TreeMap<>(Comparator.reverseOrder());
            input.put("a", new Card(CARD));
            input.put("b", new Card(CARD));

            Holder masked = Masker.mask(new Holder(List.of(), Set.of(), input));

            assertIterableEquals(List.of("b", "a"), masked.map().keySet());
        }

        @Test
        void keepsSortedSetComparator() {
            TreeSet<Card> input = new TreeSet<>(Comparator.comparing(Card::number).reversed());
            input.add(new Card("4111111111111111"));
            input.add(new Card("5500005555555559"));

            Holder masked = Masker.mask(new Holder(List.of(), input, Map.of()));

            assertIterableEquals(
                    List.of(new Card("************5559"), new Card("************1111")),
                    masked.set());
        }

        @Test
        void masksInsideOptional() {
            OptionalHolder masked = Masker.mask(new OptionalHolder(Optional.of(new Card(CARD))));
            assertEquals(MASKED, masked.card().orElseThrow().number());
        }

        @Test
        void leavesAnEmptyOptionalAlone() {
            OptionalHolder empty = new OptionalHolder(Optional.empty());
            assertSame(empty, Masker.mask(empty));
        }

        @Test
        void masksObjectArraysAndKeepsTheComponentType() {
            ArrayHolder masked = Masker.mask(new ArrayHolder(
                    new Card[] {new Card(CARD)},
                    new int[] {1, 2, 3}));

            assertEquals(Card[].class, masked.cards().getClass());
            assertEquals(MASKED, masked.cards()[0].number());
        }

        @Test
        void leavesPrimitiveArraysAlone() {
            int[] counts = {1, 2, 3};
            ArrayHolder masked = Masker.mask(new ArrayHolder(new Card[] {new Card(CARD)}, counts));
            assertSame(counts, masked.counts());
        }

        @Test
        void masksThroughNestedContainers() {
            DeepHolder masked = Masker.mask(new DeepHolder(List.of(List.of(List.of(new Card(CARD))))));
            assertEquals(MASKED, masked.deep().get(0).get(0).get(0).number());
        }

        @Test
        void masksARecordHeldInAnObjectTypedComponent() {
            // Dispatch is on the runtime class, so erasure and loose typing do not hide a Record.
            Wrapper masked = Masker.mask(new Wrapper(new Card(CARD)));
            assertEquals(MASKED, ((Card) masked.anything()).number());
        }

        @Test
        void returnsUnmodifiableCopiesOfMaskedContainers() {
            Holder masked = Masker.mask(new Holder(
                    new ArrayList<>(List.of(new Card(CARD))),
                    new LinkedHashSet<>(Set.of(new Card(CARD))),
                    new HashMap<>(Map.of("k", new Card(CARD)))));

            assertThrows(UnsupportedOperationException.class, () -> masked.list().add(new Card(CARD)));
            assertThrows(UnsupportedOperationException.class, () -> masked.set().clear());
            assertThrows(UnsupportedOperationException.class, () -> masked.map().remove("k"));
        }

        @Test
        void aComponentDeclaredAsSortedStillHoldsTheMaskedCopy() {
            // This is why the sorted branches exist: a plain LinkedHashSet copy would not fit here.
            SortedSet<Card> cards = new TreeSet<>(Comparator.comparing(Card::number));
            cards.add(new Card(CARD));

            SortedHolder masked = Masker.mask(new SortedHolder(
                    cards,
                    new TreeMap<>(Map.of("primary", new Card(CARD)))));

            assertEquals(MASKED, masked.cards().first().number());
            assertEquals(MASKED, masked.vault().get("primary").number());
        }

        @Test
        void aComponentDeclaredAsAConcreteCollectionTypeIsRejected() {
            // Documented limitation: masking hands back unmodifiable copies, which an ArrayList-typed
            // component cannot hold, so the canonical constructor refuses them.
            ConcreteHolder original = new ConcreteHolder(new ArrayList<>(List.of(new Card(CARD))));

            MaskingException thrown = assertThrows(MaskingException.class, () -> Masker.mask(original));
            assertTrue(thrown.getMessage().contains("declare collections as interfaces"), thrown.getMessage());
        }

        @Test
        void doesNotMutateTheInputContainers() {
            List<Card> input = new ArrayList<>(List.of(new Card(CARD)));
            Masker.mask(new Holder(input, Set.of(), Map.of()));
            assertEquals(CARD, input.get(0).number());
        }
    }

    @Nested
    @DisplayName("Values with no rule")
    class NoRule {

        @Test
        void nullIsReturnedAsNull() {
            assertNull(Masker.mask(null));
        }

        @Test
        void anUnknownObjectIsReturnedAsIs() {
            Object plain = new Object();
            assertSame(plain, Masker.mask(plain));
        }

        @Test
        void aStringIsReturnedAsIs() {
            // A bare String carries no annotation, so there is no rule to apply to it.
            String value = "not a record";
            assertSame(value, Masker.mask(value));
        }
    }

    @Nested
    @DisplayName("Depth and cycles")
    class DepthAndCycles {

        @Test
        void aReferenceCycleFailsInsteadOfRecursingForever() {
            List<Node> children = new ArrayList<>();
            Node node = new Node("root", children);
            children.add(node);

            MaskingException thrown = assertThrows(MaskingException.class, () -> Masker.mask(node));
            assertTrue(thrown.getMessage().contains("cycle"), thrown.getMessage());
        }

        @Test
        void aGraphDeeperThanTheLimitFails() {
            Node deepest = new Node("leaf", List.of());
            Node current = deepest;
            for (int i = 0; i < 40; i++) {
                current = new Node("level-" + i, List.of(current));
            }
            Node root = current;

            assertThrows(MaskingException.class, () -> Masker.mask(root));
        }

        @Test
        void aGraphWithinTheLimitSucceeds() {
            Node current = new Node("leaf", List.of());
            for (int i = 0; i < 5; i++) {
                current = new Node("level-" + i, List.of(current));
            }
            Node root = current;

            assertSame(root, Masker.mask(root));
            assertFalse(root.children().isEmpty());
        }
    }
}
