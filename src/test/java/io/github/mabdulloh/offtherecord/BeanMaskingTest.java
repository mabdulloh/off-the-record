package io.github.mabdulloh.offtherecord;

import static io.github.mabdulloh.offtherecord.MaskType.EMAIL;
import static io.github.mabdulloh.offtherecord.MaskType.PARTIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Masking plain classes: the three copy mechanisms, annotation placement, and inheritance. */
class BeanMaskingTest {

    private static final String CARD = "4532111122228888";
    private static final String MASKED = "************8888";

    // --- fixtures ---------------------------------------------------------------------------------

    /** No-arg constructor plus setters, so the setter mechanism applies. */
    static class UserBean {
        private String id;
        @Mask(EMAIL) private String email;
        @Mask(value = PARTIAL, preserveEnd = 4) private String creditCard;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getCreditCard() { return creditCard; }
        public void setCreditCard(String creditCard) { this.creditCard = creditCard; }

        static UserBean of(String id, String email, String creditCard) {
            UserBean bean = new UserBean();
            bean.setId(id);
            bean.setEmail(email);
            bean.setCreditCard(creditCard);
            return bean;
        }
    }

    /** Annotated on the getter rather than the field. */
    static class GetterAnnotated {
        private String secret = "hunter2";

        @Mask
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    /** Annotated twice for the same property, which is a misconfiguration. */
    static class DoubleAnnotated {
        @Mask private String secret = "hunter2";

        @Mask
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    /** Annotated, but unreadable, so the annotation cannot be honoured. */
    static class NoGetter {
        @Mask private String secret = "hunter2";

        public void setSecret(String secret) { this.secret = secret; }
    }

    abstract static class BaseBean {
        @Mask(EMAIL) private String email;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    static class ChildBean extends BaseBean {
        private String displayName;

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }

    /**
     * Immutable, and the only constructor takes the builder, so neither setters nor an all-args
     * constructor can apply. Forces the builder mechanism.
     */
    static final class BuilderOnlyBean {
        private final String id;
        @Mask(value = PARTIAL, preserveEnd = 4) private final String card;

        private BuilderOnlyBean(Builder builder) {
            this.id = builder.id;
            this.card = builder.card;
        }

        public String getId() { return id; }
        public String getCard() { return card; }

        public Builder toBuilder() { return new Builder().id(id).card(card); }

        // Public, because masking only ever calls a class's public API.
        public static final class Builder {
            private String id;
            private String card;

            public Builder id(String id) { this.id = id; return this; }
            public Builder card(String card) { this.card = card; return this; }
            public BuilderOnlyBean build() { return new BuilderOnlyBean(this); }
        }
    }

    /** Immutable with no builder and no setters, so only the name-matched constructor can apply. */
    static final class ImmutableBean {
        private final String id;
        @Mask(value = PARTIAL, preserveEnd = 4) private final String card;

        ImmutableBean(String id, String card) {
            this.id = id;
            this.card = card;
        }

        public String getId() { return id; }
        public String getCard() { return card; }
    }

    /** Two properties but a one-argument constructor, so nothing can rebuild it. */
    static final class UnbuildableBean {
        @Mask private final String secret;
        private final String derived;

        UnbuildableBean(String secret) {
            this.secret = secret;
            this.derived = "derived-" + secret.length();
        }

        public String getSecret() { return secret; }
        public String getDerived() { return derived; }
    }

    /**
     * A getter that blows up, the way a detached lazy association would. The toggle is static so it does
     * not become a property, and so the masked copy can be read back afterwards.
     */
    static class ExplodingGetter {
        static boolean explode = true;

        private String id = "usr_1";
        @Mask private String secret = "hunter2";

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSecret() {
            if (explode) {
                throw new IllegalStateException("not initialised");
            }
            return secret;
        }
        public void setSecret(String secret) { this.secret = secret; }
    }

    record CardRecord(@Mask(value = PARTIAL, preserveEnd = 4) String number) {}

    /** A bean holding a record. */
    static class BeanWithRecord {
        private CardRecord card;

        public CardRecord getCard() { return card; }
        public void setCard(CardRecord card) { this.card = card; }
    }

    /** A record holding a bean. */
    record RecordWithBean(UserBean user) {}

    static class BeanHolder {
        private List<UserBean> users;

        public List<UserBean> getUsers() { return users; }
        public void setUsers(List<UserBean> users) { this.users = users; }
    }

    // --- tests ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Setter mechanism")
    class Setters {

        @Test
        void masksAnnotatedPropertiesAndLeavesTheRestAlone() {
            UserBean masked = Masker.mask(UserBean.of("usr_9981", "developer@company.com", CARD));

            assertEquals("usr_9981", masked.getId());
            assertEquals("d********@company.com", masked.getEmail());
            assertEquals(MASKED, masked.getCreditCard());
        }

        @Test
        void doesNotTouchTheOriginal() {
            UserBean original = UserBean.of("usr_9981", "developer@company.com", CARD);

            Masker.mask(original);

            assertEquals("developer@company.com", original.getEmail());
            assertEquals(CARD, original.getCreditCard());
        }

        @Test
        void returnsTheSameInstanceWhenNothingChanged() {
            UserBean nothingSensitive = UserBean.of("usr_1", null, null);
            assertSame(nothingSensitive, Masker.mask(nothingSensitive));
        }
    }

    @Nested
    @DisplayName("Builder mechanism")
    class Builders {

        @Test
        void masksThroughToBuilder() {
            BuilderOnlyBean masked = Masker.mask(
                    new BuilderOnlyBean.Builder().id("acct_1").card(CARD).build());

            assertEquals("acct_1", masked.getId());
            assertEquals(MASKED, masked.getCard());
        }
    }

    @Nested
    @DisplayName("All-args constructor mechanism")
    class AllArgs {

        @Test
        void masksThroughAConstructorMatchedByParameterName() {
            ImmutableBean masked = Masker.mask(new ImmutableBean("acct_1", CARD));

            assertEquals("acct_1", masked.getId());
            assertEquals(MASKED, masked.getCard());
        }
    }

    @Nested
    @DisplayName("When no mechanism applies")
    class Unbuildable {

        @Test
        void failsLoudlyAndNamesWhatWasMissing() {
            MaskingException thrown = assertThrows(MaskingException.class,
                    () -> Masker.mask(new UnbuildableBean("hunter2")));

            assertTrue(thrown.getMessage().contains("UnbuildableBean"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("no-argument constructor"), thrown.getMessage());
        }

        @Test
        void doesNotLeakTheValueInTheMessage() {
            MaskingException thrown = assertThrows(MaskingException.class,
                    () -> Masker.mask(new UnbuildableBean("hunter2")));

            assertTrue(!thrown.getMessage().contains("hunter2"), "the message leaked the value");
        }
    }

    @Nested
    @DisplayName("Annotation placement")
    class Placement {

        @Test
        void honoursAnAnnotationOnTheGetter() {
            assertEquals("********", Masker.mask(new GetterAnnotated()).getSecret());
        }

        @Test
        void rejectsAnnotatingBothTheFieldAndTheGetter() {
            MaskingException thrown =
                    assertThrows(MaskingException.class, () -> Masker.mask(new DoubleAnnotated()));

            assertTrue(thrown.getMessage().contains("more than one place"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("secret"), thrown.getMessage());
        }

        @Test
        void rejectsAnnotatingAPropertyThatCannotBeRead() {
            MaskingException thrown =
                    assertThrows(MaskingException.class, () -> Masker.mask(new NoGetter()));

            assertTrue(thrown.getMessage().contains("no getter"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("Inheritance")
    class Inheritance {

        @Test
        void masksAPropertyDeclaredOnASuperclass() {
            ChildBean bean = new ChildBean();
            bean.setEmail("developer@company.com");
            bean.setDisplayName("Abdulloh");

            ChildBean masked = Masker.mask(bean);

            assertEquals("d********@company.com", masked.getEmail());
            assertEquals("Abdulloh", masked.getDisplayName());
        }
    }

    @Nested
    @DisplayName("Records and beans compose")
    class Composition {

        @Test
        void aBeanHoldingARecord() {
            BeanWithRecord bean = new BeanWithRecord();
            bean.setCard(new CardRecord(CARD));

            assertEquals(MASKED, Masker.mask(bean).getCard().number());
        }

        @Test
        void aRecordHoldingABean() {
            RecordWithBean masked = Masker.mask(
                    new RecordWithBean(UserBean.of("usr_1", "developer@company.com", CARD)));

            assertEquals("d********@company.com", masked.user().getEmail());
            assertEquals(MASKED, masked.user().getCreditCard());
        }

        @Test
        void beansInsideACollection() {
            BeanHolder holder = new BeanHolder();
            holder.setUsers(List.of(UserBean.of("a", "one@x.com", CARD)));

            BeanHolder masked = Masker.mask(holder);

            assertEquals("o**@x.com", masked.getUsers().get(0).getEmail());
            assertEquals(MASKED, masked.getUsers().get(0).getCreditCard());
        }
    }

    @Nested
    @DisplayName("Fail closed on read")
    class ReadFailures {

        @Test
        void aGetterThatThrowsBecomesARedactionRatherThanAFailedCall() {
            ExplodingGetter.explode = true;
            ExplodingGetter masked;
            try {
                masked = Masker.mask(new ExplodingGetter());
            } finally {
                ExplodingGetter.explode = false;
            }

            assertEquals("usr_1", masked.getId());
            assertEquals("********", masked.getSecret());
        }
    }

    @Nested
    @DisplayName("Values that are not structures")
    class PassThrough {

        @Test
        void jdkTypesAreLeftAlone() {
            var uuid = java.util.UUID.randomUUID();
            assertSame(uuid, Masker.mask(uuid));
        }

        @Test
        void enumsAreLeftAlone() {
            assertSame(MaskType.EMAIL, Masker.mask(MaskType.EMAIL));
        }
    }
}
