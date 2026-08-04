package io.github.mabdulloh.offtherecord;

import static io.github.mabdulloh.offtherecord.MaskType.EMAIL;
import static io.github.mabdulloh.offtherecord.MaskType.PARTIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Identity tracking: reference cycles, and objects reachable from more than one place.
 *
 * <p>0.1.0 used a depth counter as a stand-in for cycle detection, which refused legitimately deep graphs
 * and turned a diamond into a tree. Both are fixed by the same identity map.
 */
class CycleAndSharingTest {

    private static final String CARD = "4532111122228888";
    private static final String MASKED = "************8888";

    // --- fixtures ---------------------------------------------------------------------------------

    static class Parent {
        private String name;
        private Child child;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Child getChild() { return child; }
        public void setChild(Child child) { this.child = child; }
    }

    static class Child {
        @Mask(EMAIL) private String email;
        private Parent parent;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public Parent getParent() { return parent; }
        public void setParent(Parent parent) { this.parent = parent; }
    }

    static class SelfReferencing {
        @Mask private String secret;
        private SelfReferencing self;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public SelfReferencing getSelf() { return self; }
        public void setSelf(SelfReferencing self) { this.self = self; }
    }

    record Card(@Mask(value = PARTIAL, preserveEnd = 4) String number) {}

    record Pair(Card first, Card second) {}

    record Node(String name, List<Node> children) {}

    static class BeanPair {
        private Child first;
        private Child second;

        public Child getFirst() { return first; }
        public void setFirst(Child first) { this.first = first; }
        public Child getSecond() { return second; }
        public void setSecond(Child second) { this.second = second; }
    }

    // --- tests ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Cycles through setter-copied beans")
    class ResolvableCycles {

        @Test
        void areMaskedAndKeepTheirShape() {
            Parent parent = new Parent();
            parent.setName("acct_1");
            Child child = new Child();
            child.setEmail("developer@company.com");
            child.setParent(parent);
            parent.setChild(child);

            Parent masked = Masker.mask(parent);

            assertEquals("acct_1", masked.getName());
            assertEquals("d********@company.com", masked.getChild().getEmail());
            // The cycle closes on the copy, not on the original.
            assertSame(masked, masked.getChild().getParent());
            assertNotSame(parent, masked);
        }

        @Test
        void leaveTheOriginalGraphAlone() {
            Parent parent = new Parent();
            Child child = new Child();
            child.setEmail("developer@company.com");
            child.setParent(parent);
            parent.setChild(child);

            Masker.mask(parent);

            assertEquals("developer@company.com", child.getEmail());
            assertSame(parent, child.getParent());
        }

        @Test
        void includeAnObjectThatPointsAtItself() {
            SelfReferencing original = new SelfReferencing();
            original.setSecret("hunter2");
            original.setSelf(original);

            SelfReferencing masked = Masker.mask(original);

            assertEquals("********", masked.getSecret());
            assertSame(masked, masked.getSelf());
        }
    }

    @Nested
    @DisplayName("Cycles that cannot be resolved")
    class UnresolvableCycles {

        @Test
        void throughRecordsFailWithThePathTheyTook() {
            List<Node> children = new ArrayList<>();
            Node node = new Node("root", children);
            children.add(node);

            MaskingException thrown = assertThrows(MaskingException.class, () -> Masker.mask(node));

            assertTrue(thrown.getMessage().contains("cycle"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("Node.children"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("->"), thrown.getMessage());
        }

        @Test
        void throughAClassWithNoSettersExplainWhy() {
            MaskingException thrown = assertThrows(MaskingException.class,
                    () -> Masker.mask(new SelfReferencingImmutable()));

            assertTrue(thrown.getMessage().contains("cycle"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("setters"), thrown.getMessage());
        }
    }

    /** Readable and self-referencing, but with no setters, so no blank copy can be registered. */
    static final class SelfReferencingImmutable {
        @Mask private final String secret = "hunter2";
        private final SelfReferencingImmutable self = this;

        public String getSecret() { return secret; }
        public SelfReferencingImmutable getSelf() { return self; }
    }

    @Nested
    @DisplayName("Shared references")
    class Sharing {

        @Test
        void yieldOneCopyRatherThanTwo() {
            Card shared = new Card(CARD);

            Pair masked = Masker.mask(new Pair(shared, shared));

            assertEquals(MASKED, masked.first().number());
            assertSame(masked.first(), masked.second(), "a diamond in the input became a tree");
        }

        @Test
        void workForBeansToo() {
            Child shared = new Child();
            shared.setEmail("developer@company.com");
            BeanPair pair = new BeanPair();
            pair.setFirst(shared);
            pair.setSecond(shared);

            BeanPair masked = Masker.mask(pair);

            assertEquals("d********@company.com", masked.getFirst().getEmail());
            assertSame(masked.getFirst(), masked.getSecond());
        }

        @Test
        void areNotConfusedByEqualButDistinctObjects() {
            // Two equal Cards are still two objects, so they get two copies.
            Pair masked = Masker.mask(new Pair(new Card(CARD), new Card(CARD)));

            assertEquals(masked.first(), masked.second());
            assertNotSame(masked.first(), masked.second());
        }
    }
}
