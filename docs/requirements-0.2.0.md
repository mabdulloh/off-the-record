# Requirements — `off-the-record` v0.2.0 (draft)

Status: draft, decisions settled. Ready to be turned into tasks.
Guiding principle: same as 0.1.0. KISS. Two features, no framework.

Scope: JavaBean support, and a public `MaskStrategy` SPI.

---

## 1. Problem statement

Two limitations from 0.1.0 are the ones people hit first.

**Records only.** Most DTOs are still classes: Lombok POJOs, generated OpenAPI clients, hand-written
request and response objects. A library that only masks records cannot be adopted incrementally, so it
does not get adopted.

**Four fixed strategies.** `REDACT_ALL`, `PARTIAL`, `EMAIL`, and `NULLIFY` cover common cases and nothing
else. There is no way to mask an Indonesian NIK, truncate an IP address, bucket a salary, or emit a
deterministic token, and no way to add one without forking.

There is a tension to resolve honestly. 0.1.0's stated reason to exist is that other libraries mask by
mutating private fields through `Field.setAccessible(true)`. If bean support is implemented that way, the
README's central claim becomes false for half the library. That constraint shapes FR-2.1 and FR-2.2.

## 2. Goal for v0.2.0

1. Mask a JavaBean the same way we mask a record: return a redacted **copy**, never mutate the argument,
   never reach past the class's own public API to do it.
2. Let a caller supply their own redaction rule with `@Mask(with = MyStrategy.class)`, and implement the
   four built-in types on that same interface so the SPI is provably expressive enough.

Everything in 0.1.0 keeps working, unchanged, with no source edits required of existing users.

### What this library is for

**Data transfer objects.** Records and POJOs, the things that cross a boundary: REST responses, message
payloads, log lines, support tooling. Both shapes are first-class in 0.2.0, and neither is privileged.

**Not persistence entities.** Masking a JPA entity means calling getters on it, which can trigger lazy
loading and turn a `log.info` into an N+1. There is nothing a masking library can do about that, so the
answer is to mask the DTO you were going to return anyway, not the entity behind it. Entity-aware
masking, if it is ever wanted, is a separate opt-in module where a Hibernate dependency is expected. See
non-goals, and FR-2.11 for what happens if you do it anyway.

## 3. Functional requirements — JavaBean support

### FR-2.1 — Copy, do not mutate, and stay inside the public API
A masked bean is a new instance. The argument is never modified.

Copy is attempted in this order, first match wins:

| Order | Mechanism | Requires |
| :--- | :--- | :--- |
| 1 | no-arg constructor plus setters | accessible no-arg constructor, and a getter and setter for **every** property |
| 2 | `toBuilder()` style builder | a public `toBuilder()` plus `build()`, and a builder setter per property |
| 3 | all-args constructor matched **by parameter name** | one constructor whose parameter names map one-to-one onto property names |

Two corrections to the order this draft originally proposed, both found while implementing:

- **Setters come first, not the builder.** Setters are the only mechanism where a blank instance can be
  registered before it is populated, which is what makes a reference cycle resolvable (FR-2.5). Preferring
  a builder on a class that has both would throw away cycle support for nothing.
- **Setters require a getter and setter for every property, not just the masked ones.** A no-arg
  constructor produces an empty object, so anything not written back is silently lost. Declining the
  mechanism is better than returning a copy with missing data.

If none applies, the class can still be read, so an object that turns out to need no masking passes
through untouched. Only an object that actually changed and cannot be rebuilt fails, with a
`MaskingException` naming the class and which mechanism was missing. It never falls back to
`setAccessible(true)`, and never silently returns the original in place of a masked copy.

Mechanism 3 matches by name, never by position. `Class.getDeclaredFields()` is documented as returning
fields in no particular order, so positional matching would work on one JDK and silently swap two
same-typed properties on another. In a masking library that means an unmasked value landing in a field
something else displays, with no exception to notice. Concretely:

- Use `Parameter.getName()` only when `Parameter.isNamePresent()` is true.
- Require a total, unambiguous match: same count, every parameter name maps to a property, no leftovers
  on either side.
- If parameter names are absent, refuse with a message saying so, and suggest compiling with
  `-parameters`, adding a builder, or adding setters.

Names are only present when the class was compiled with `-parameters`, which is not the default, so this
mechanism declines more often than a positional one would. That is the point: it declines loudly instead
of guessing. This is also why records are safe today, since `RecordComponent` gives us guaranteed names.

Acceptance: a Lombok `@Data` bean, a Lombok `@Builder(toBuilder = true)` bean, and a hand-written bean
with getters and setters all mask correctly. A class compiled without `-parameters` that has only an
all-args constructor produces a `MaskingException` that names the missing mechanism. A class with private
final fields, no builder and no matching constructor does the same.

### FR-2.2 — Read through getters, not fields
Property values are read through the getter, not by reading the field. `isX()` is accepted for `boolean`.

This is what keeps the encapsulation claim true, and it is the difference between "bean support" and
"arbitrary POJO field mutation". A property with no getter is not maskable and, if annotated, is a
misconfiguration under FR-2.7.

Acceptance: a bean whose getter returns something different from the field (trimmed, lazily computed) is
masked based on what the getter returns.

### FR-2.3 — Annotation placement
`@Mask` gains `FIELD` and `METHOD` as targets alongside `RECORD_COMPONENT`.

- on a field: applies to the property of the same name
- on a getter: applies to that property
- on both, for the same property: misconfiguration under FR-2.7, rather than a precedence rule nobody
  will remember

Acceptance: field-annotated and getter-annotated beans both mask; annotating both fails loudly.

### FR-2.4 — Inheritance
Properties inherited from superclasses are masked. The walk stops at `Object`. A subclass may not
re-annotate an inherited property with a different rule; that is a misconfiguration.

Acceptance: a bean extending an abstract base that declares `@Mask` on a property masks that property.

### FR-2.5 — Identity-tracked traversal replaces the depth counter
Bean graphs cycle routinely: bidirectional associations, parent and child trees. The 0.1.0 depth-32
heuristic (marked `ponytail:` in `Masker`) refuses legitimate graphs and is replaced by an identity map of
original to masked copy, held for the duration of one `mask()` call.

**Shared references stay shared.** This is a correctness fix, not only cycle support. Today two fields
pointing at the same object produce two separate copies, quietly turning a diamond into a tree. With the
identity map, one original maps to one copy.

**Whether a cycle can be masked depends on the copy mechanism, and this is arithmetic rather than
effort:**

| Mechanism | Cycles |
| :--- | :--- |
| no-arg constructor plus setters | Supported. Create the shell, register `original -> copy`, then populate. The traversal comes back around, finds the registered copy, and writes the reference. Graph shape is preserved. |
| record canonical constructor, builder, all-args constructor | Impossible. Masked `A` needs masked `B`, which needs masked `A`. There is no construction order. `MaskingException`. |

The exception must name the path it found, for example
`Order.customer -> Customer.orders -> Order`. "Too deep or cyclic" is what 0.1.0 reports and it tells
nobody anything.

Acceptance: a bidirectional parent and child pair of setter-copied beans masks, and the masked child
points at the masked parent. The same cycle built from records raises `MaskingException` naming the path.
A graph 100 levels deep masks successfully. An object referenced from two places yields one masked copy,
verified by identity.

### FR-2.6 — Records and beans compose
A record holding a bean, and a bean holding a record, both mask through. Containers work the same for
both.

Acceptance: `record Order(Customer customer)` where `Customer` is a bean, and the reverse, both mask.

### FR-2.7 — Misconfiguration is loud, same as FR-9
`MaskingException` at first use, naming the class and property: `@Mask` on a property with no getter, on
both field and getter, re-annotated in a subclass, on a property that cannot be written back through any
mechanism in FR-2.1, or with both `value()` and `with` set.

## 4. Functional requirements — `MaskStrategy` SPI

### FR-2.8 — The interface
Two new public types, and nothing else:

```java
public interface MaskStrategy {
    Object mask(Object value, MaskContext context);
}

public record MaskContext(
        String propertyName,
        Class<?> declaringType,
        Class<?> propertyType,
        int preserveStart,
        int preserveEnd,
        char maskChar) {}
```

`Object` rather than `String`, because widening the signature later would break every implementor, and
date and number strategies are already on the roadmap for 0.3.0. Better to take the cast now than to
break the SPI one release after introducing it.

No abstract base class, no generic `MaskStrategy<T>`. A base class that handled nulls and type checks
would be putting a security invariant somewhere user code can subclass or bypass; FR-2.11 puts it in the
engine instead, where it holds regardless of what the author writes. Generics would add a wildcard to
`@Mask(with = ...)` and buy no runtime safety, since erasure means the engine checks types either way.

`MaskContext` deliberately does **not** carry the object being masked. A strategy for one property has no
business reading its siblings.

A complete custom strategy, for reference:

```java
public final class NikStrategy implements MaskStrategy {
    @Override
    public Object mask(Object value, MaskContext context) {
        String nik = (String) value;
        return nik.substring(0, 2) + "**********" + nik.substring(12);
    }
}
```

### FR-2.9 — Selection and instantiation
`@Mask(with = MyStrategy.class)` selects a custom strategy. `with` and `value()` are mutually exclusive;
setting both is a misconfiguration under FR-2.7.

A strategy is instantiated once per class via its public no-arg constructor and cached. Instances are
shared across threads and across calls.

Acceptance: two `mask()` calls on the same type instantiate the strategy once.

### FR-2.10 — The contract a strategy author must honour
Documented obligations. The engine enforces the ones it can, per FR-2.11.

- **Stateless and thread-safe.** Instances are shared. The library will not synchronise on them.
- **Idempotent.** `mask(mask(x))` must equal `mask(x)`, same as FR-8.
- **Type-compatible return.** A value that cannot be written back to the property fails closed.

The library cannot verify that a strategy actually redacts anything, and must not try. A correct strategy
returns its input unchanged when the input is already masked, so "output equals input" cannot be treated
as a bug.

### FR-2.11 — The engine owns the invariants, third-party code fails closed
The engine, not the strategy and not a base class:

- **Never passes `null` to a strategy.** Null in, null out, decided before dispatch, as in 0.1.0.
- **Checks the return value** is assignable to the property type. If not, full redaction.
- **Catches anything a strategy throws**, including `ClassCastException` from a careless cast. Full
  redaction.
- **Catches anything a getter throws**, so a bean whose getter fails degrades to a redacted property
  rather than failing the caller. This is also what makes an uninitialised JPA association harmless
  rather than fatal, without the core knowing what Hibernate is.

None of this may propagate the original value, and none of it may fail the caller's request.

This makes the fail-closed handler in `RecordMetadata.Component.redact` reachable and testable again.
0.1.0 closed the only configuration route into it, and it has been defensive-only since.

Acceptance: a strategy that throws yields `********`; a strategy returning an `Integer` for a `String`
property yields `********`; a getter that throws yields `********`; none of the three fails the call.

### FR-2.12 — Built-ins are strategies
`REDACT_ALL`, `PARTIAL`, `EMAIL`, and `NULLIFY` are reimplemented as `MaskStrategy` instances, so the
public SPI can express everything the library ships. `MaskType` stays as the convenient shorthand and its
observable behaviour does not change.

Acceptance: the entire 0.1.0 test suite passes unmodified.

## 5. Non-functional requirements

- **NFR-2.1** Java 17 baseline. No runtime dependencies. Unchanged.
- **NFR-2.2** Source and behaviour compatible with 0.1.0. No existing annotation, type, or output
  changes. The 0.1.0 suite is the regression gate.
- **NFR-2.3** Bean metadata is cached per class in a `ClassValue`, like record metadata.
- **NFR-2.4** Records keep their current path and must not get slower. Bean support is additive.
- **NFR-2.5** Public API grows from four types to six: `MaskStrategy` and `MaskContext`.
- **NFR-2.6** No allocation when nothing changed, for beans as well as records. The identity map from
  FR-2.5 is the one new per-call allocation, and it is the cost the `ponytail:` note anticipated.

## 6. Explicit non-goals for v0.2.0

- **JPA and Hibernate awareness.** Core masks DTOs. Entity support, if ever, is a separate opt-in module.
- **Global strategy overrides**, where a `MaskStrategy` on the classpath replaces a built-in everywhere.
  Behaviour that depends on classpath contents cannot be debugged from the source.
- **An abstract base class or generic `MaskStrategy<T>`.** See FR-2.8.
- Jackson, Spring Boot, and logging integrations.
- Externalised policy (YAML, path expressions) for types you cannot annotate.
- Role- or context-conditional masking.
- Annotation processor, generated maskers, safe `toString()`, GraalVM metadata.
- Deterministic HMAC tokenization, format-preserving masking, date and number strategies. The SPI makes
  these writable by users; shipping them is 0.3.0 at the earliest.
- Masking map keys. Still values only.
- `setAccessible(true)` as a fallback for anything. Not a scheduling decision, a design boundary.

## 7. Known limitations to carry forward

Still true in 0.2.0, still documented rather than solved:

1. Validation on the write path: a record's canonical constructor, or a bean's setter or builder, that
   rejects a masked value makes masking fail.
2. Map keys are not masked.
3. Collection components must be declared as interfaces.
4. `PARTIAL` and `EMAIL` preserve length.
5. Named modules must `open` their package.
6. Cycles cannot be masked through records, builders, or all-args constructors. FR-2.5.
7. Masking a JPA entity with an open session can trigger lazy loading. Mask the DTO instead.
8. This is redaction, not anonymization.

## 9. Definition of done

- [x] Every FR above has a passing test. 122 tests total, up from 81.
- [x] The 0.1.0 test suite passes, with one deliberate change: `aGraphDeeperThanTheLimitFails` asserted the
      depth-32 ceiling that FR-2.5 retires, so it is now `aDeepGraphIsNoLongerRefused` and masks 100 levels.
      No other 0.1.0 test was touched, and no 0.1.0 output changed.
- [x] `mvn verify` green, and the CI matrix covers JDK 17 and 21.
- [x] README documents bean support and the SPI, including which bean shapes are not supported and why.
- [x] The `ponytail:` note on `MAX_DEPTH` is gone, along with `MAX_DEPTH` itself.

### Traceability

| Requirement | Covered by |
| :--- | :--- |
| FR-2.1 copy mechanisms | `BeanMaskingTest.Setters`, `.Builders`, `.AllArgs`, `.Unbuildable` |
| FR-2.2 read through getters | `BeanMaskingTest.Placement`, `.ReadFailures` |
| FR-2.3 annotation placement | `BeanMaskingTest.Placement` |
| FR-2.4 inheritance | `BeanMaskingTest.Inheritance` |
| FR-2.5 cycles and sharing | `CycleAndSharingTest`, `TraversalTest.DepthAndCycles` |
| FR-2.6 records and beans compose | `BeanMaskingTest.Composition` |
| FR-2.7 misconfiguration | `BeanMaskingTest.Placement`, `MaskStrategyTest.Misconfiguration` |
| FR-2.8 the interface | `MaskStrategyTest.Selection`, `.Context` |
| FR-2.9 selection and instantiation | `MaskStrategyTest.Selection` |
| FR-2.10 author contract | documented; the enforceable half is FR-2.11 |
| FR-2.11 engine invariants | `MaskStrategyTest.Invariants`, `BeanMaskingTest.ReadFailures` |
| FR-2.12 built-ins are strategies | `MaskStrategyTest.BuiltIns`, plus the whole 0.1.0 suite |
