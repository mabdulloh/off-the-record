# Requirements — `off-the-record` v0.3.0

Status: draft. Tightening the 0.2.0 release, no new public types.
Guiding principle: same as before. KISS. One feature, no framework.

Read [0.2.0](requirements-0.2.0.md) and [0.1.0](requirements-0.1.0.md) first. This release assumes both hold
and adds nothing to the public surface.

---

## 1. Why this is a separate release

A 0.2.0 audit found six small things worth fixing. None of them changes the public API. None of them is a
behaviour change a user can see from a working program — they are holes that the engine already closes by
coincidence and that nothing is checking it keeps closing. The kind that stays true right up until it stops
being true.

Three were called out in the 0.2.0 requirements document itself as gaps, never filled. The other three fell
out of the review.

Nothing in here is a feature. Features wait for a real user asking for them.

## 2. Goal

1. Close the six gaps the 0.2.0 audit named.
2. Document what 0.3.0 deliberately does not do, so 0.4.0 has something to react to.

That is the whole release.

## 3. Functional requirements

### FR-3.1 — Mechanism selection skips `BUILDER` for a bean with one unreadable property

**Audit finding B3, partially confirmed during implementation.** `BeanMasker` discovers properties via
fields, then looks for a getter per property. A property with no getter is kept in the property list
because it can still be written, but mechanism selection requires `allReadable == true` for `BUILDER`
and `ALL_ARGS` alike, and requires `allReadable && allWritable` for `SETTERS`. The mechanism must fall
through to `NONE`, which throws a `MaskingException` describing what is missing.

The defensive code path in `BeanMasker.mask` and `BeanMasker.rebuild` (`BUILDER` case) skips an
unreadable property rather than write `null` into the builder. Both are belt-and-braces: the mechanism
selection already routes around the unreadable property, and the defensive code only runs if mechanism
selection ever changes.

**Acceptance:** a builder-copied bean with one property that has no getter throws `MaskingException`
naming the class and the missing getter. The fixture and the test are part of `BeanMaskingTest.Builders`.

### FR-3.2 — `BeanMasker.mask` does not allocate a parallel `originals[]` array in the `SETTERS` path

**Audit finding P1.** `BeanMasker.mask` allocates two `Object[]` arrays per call. `originals[]` is only read
in the `BUILDER` branch, for one comparison per property. In the `SETTERS` path — the most common one — it
is dead weight, and `Object[N]` per masked bean is the only allocation that grows with the bean's size.

The narrowest fix: allocate `originals[]` only when `mechanism == BUILDER`, or compare against the source
`original` parameter directly in the BUILDER loop. Either is one-method, no-API-change.

**Acceptance:** masking a setter-copied bean of N properties allocates one `Object[]` of size N, not two.
Asserted on the setter path, not just by inspection.

### FR-3.3 — Re-annotating an inherited property in a subclass is rejected, naming both places

**Audit finding T11.** `BeanMaskingTest.Inheritance` covers an inherited property that is annotated once,
on the superclass. `BeanMasker.findSpec` walks the hierarchy and throws when the same property is
annotated in two places. Nothing asserts it.

This is FR-2.4's second sentence, called out as a gap in the 0.2.0 requirements doc and never filled.

**Acceptance:** a subclass that re-annots a property annotated on its superclass throws `MaskingException`
at first mask, with a message naming both the superclass declaration and the subclass one. The fixture and
the test are part of `BeanMaskingTest.Placement`.

### FR-3.4 — A bean whose getter returns a transformed form of its field is masked on the transformed form

**Audit finding T12.** The engine reads through getters, by design. A bean whose `getEmail()` returns
`email.trim().toLowerCase()` is masked on the trimmed-and-lowercased value, not on the raw field. This is
what makes the public-API-only rule honest. FR-2.2's acceptance states this, and nothing pins it.

**Acceptance:** a bean with a getter that transforms its value is masked on the transformed value, the
original is unchanged, and the redaction applied matches the rule as written. The fixture lives in
`BeanMaskingTest`.

### FR-3.5 — A null inside a container is masked to null, with the path label `[i]` still recorded

**Audit finding T3.** `TraversalTest` covers `mask(null)` at the top level and `Optional.empty()`, but not
`List.of(null)` and not `Optional<Optional<X>>`. The engine short-circuits null in `MaskingSession.mask`,
but the surrounding traversal still walks and records the path label, which is the whole point of the
identity map.

**Acceptance:** `List.of(null)` masks to `List.of(null)` (no wrapping `ArrayList`), and
`Optional<Optional.of(new Card(...))>` masks the inner card. Both asserted on the existing fixtures with
one element added.

### FR-3.6 — `MaskContext.toString()` formats `Type.name`, and is covered by a test

**Audit finding context-test gap.** `MaskContext.toString` is referenced from `MaskStrategyTest.Context`
and `Masker`'s cycle message paths, but the test reads `seen.toString()` as part of a larger assertion, not
on its own. Cheap to pin, cheap to drift.

**Acceptance:** one test in `MaskStrategyTest.Context` asserts `MaskContext("x", Foo.class, String.class,
0, 0, '*').toString() == "Foo.x"`.

## 4. Non-functional requirements

- **NFR-3.1** Java 17. No runtime dependencies. Unchanged.
- **NFR-3.2** Fully source and binary compatible with 0.2.0. Public surface still
  `Masker`, `Mask`, `MaskType`, `MaskStrategy`, `MaskContext`, `MaskingException`. No new types.
- **NFR-3.3** No behaviour change visible to a passing 0.2.0 user. A program that passed every 0.2.0 test
  passes every 0.3.0 test without recompilation.
- **NFR-3.4** The 0.1.0 and 0.2.0 suites pass unmodified. The new gaps are new tests, not replacements.

## 5. Explicit non-goals for v0.3.0

- **`MaskPolicy`, `MaskPolicy.Builder`, `Masker.using(policy)`.** Drawn up as the headline of a previous
  draft and parked. No concrete user has asked for masking classes they cannot annotate, and the mirror-class
  pattern plus a written test covers the drift problem for the few that need it. When a real user shows up,
  re-open the question.
- **Runtime values on `MaskContext` (keys, roles, environments).** Same status. The cryptographic `MaskType`
  it would enable belongs in a different library, not in a "zero runtime dependencies, Java 17+" one.
- **Date and number built-ins** (year-only, bucketing, IP truncation). Four lines each against the
  existing SPI. Convenience.
- **Jackson, Spring Boot, logging integrations.** Still.
- **Annotation processor, generated maskers, safe `toString()`, GraalVM metadata.**
- **JPA and Hibernate awareness.**
- **Masking map keys.**
- **`setAccessible(true)` as a fallback for anything.**
- **Published benchmarks.**

## 6. Known limitations to carry forward

Everything in 0.2.0 section 7 still applies. New with this release: none. The six fixes are holes, not
features; they do not create new limitations.

## 7. Open questions

None. The release's shape is small enough to argue about in code review rather than in the requirements doc.

## 8. Definition of done

- [ ] FR-3.1 through FR-3.6 each have a passing test.
- [ ] The 0.1.0 and 0.2.0 suites pass unmodified.
- [ ] `mvn verify` green on the JDK 17 and 21 matrix.
- [ ] No new public type, no new public method. The public surface is exactly the six types of 0.2.0.
- [ ] README "Release history" gains a 0.3.0 line naming the six FR numbers and saying the audit closed them.
- [ ] `README.md` install snippet and version-history line refer to 0.3.0, not 0.1.0.

### Traceability

| Requirement | Covered by |
| :--- | :--- |
| FR-3.1 mechanism selection skips `BUILDER` for unreadable property | `BeanMaskingTest.Builders.doesNotPickTheBuilderMechanismWhenOnePropertyIsUnreadable` |
| FR-3.2 allocation budget in SETTERS path | implicit: one less `Object[properties.length]` allocation per non-BUILDER mask |
| FR-3.3 re-annotation of inherited property | `BeanMaskingTest.Placement.rejectsReAnnotatingAnInheritedProperty` |
| FR-3.4 getter transforms the value | `BeanMaskingTest.GetterTransforms` |
| FR-3.5 null inside container, nested `Optional` | `TraversalTest.Containers.aNullElementInsideAListStaysNull`, `.aNestedOptionalMasksTheInnermostValue` |
| FR-3.6 `MaskContext.toString` | `MaskStrategyTest.Context.toStringFormatsDeclaringTypeDotPropertyName` |