# Requirements — `off-the-record` v0.1.0

Status: accepted scope for the first release
Guiding principle: KISS. Ship a small engine that is correct and safe, not a framework.

---

## 1. Problem statement

Java Records are immutable by design: their fields are `final` and cannot be mutated in place.
Masking libraries written in the Java 8 era mask sensitive values by mutating private fields via
`Field.setAccessible(true)`, which cannot work on Records and additionally fails across module
boundaries under JDK strong encapsulation.

Teams that use Records for DTOs therefore have no annotation-driven way to produce a redacted copy
of an object for logging, support tooling, or sharing with a third party.

## 2. Goal for v0.1.0

Given a Record instance whose components are annotated with `@Mask`, produce a **new Record instance
of the same type** in which the annotated values are redacted and everything else is untouched.

That is the whole release. Nothing else.

## 3. Who this is for

| Actor | Need |
| :--- | :--- |
| Backend developer | Log a DTO without leaking PII into the log aggregator |
| Support engineer | See enough of a card number to identify it, not enough to use it |
| Security reviewer | A predictable, auditable redaction rule expressed next to the field it protects |

## 4. Functional requirements

### FR-1 — Annotation
`@Mask` is applied to Record components. It carries a mask type and, for partial masking, how many
leading and trailing characters to preserve, plus the mask character.

Acceptance: an annotated component is redacted; an unannotated component is returned unchanged.

### FR-2 — Mask types
Exactly four in v0.1.0:

| Type | Behaviour |
| :--- | :--- |
| `PARTIAL` | Preserve `preserveStart` leading and `preserveEnd` trailing characters, mask the middle. Output length equals input length. |
| `EMAIL` | Preserve the first character of the local part and the whole domain. |
| `REDACT_ALL` | Replace with a fixed-length mask, so output reveals nothing about input length. |
| `NULLIFY` | Replace with `null`. |

Acceptance: each type produces the documented output for the documented input, including the edge
cases in FR-7.

### FR-3 — Nested Records
A component whose value is itself a Record is masked recursively.

Acceptance: `@Mask` on a component of a nested Record takes effect when the outer Record is masked.

### FR-4 — Containers
Records nested inside `List`, `Set`, `Map` values, `Optional`, and object arrays are masked
recursively without the caller iterating.

Acceptance: a `List<PaymentMethod>` held by a Record comes back with every element masked; iteration
order is preserved.

### FR-5 — Entry point
A single static call, `Masker.mask(value)`, returning the same declared type as its argument. It
accepts a Record or a container of Records.

Acceptance: `UserDto masked = Masker.mask(user);` compiles without a cast.

### FR-6 — Fail closed
The library must never return an unmasked value as the result of an internal failure.

- If a mask strategy throws, the value becomes a full redaction. FR-9 rejects the configurations that
  could cause this, so the handler is a last line of defence against a bug in a strategy rather than a
  path a caller can reach. It degrades instead of throwing on purpose: a masked log statement should not
  be able to fail the request that produced it.
- If the canonical constructor rejects the masked values, `MaskingException` is thrown. The original
  object is not returned.
- If the traversal depth limit is exceeded (deep graph or reference cycle), `MaskingException` is
  thrown.

Acceptance: no code path returns the original sensitive value after an error.

### FR-7 — Defined edge cases
| Input | Required behaviour |
| :--- | :--- |
| `null` value | stays `null`, no exception |
| empty string | `PARTIAL`/`EMAIL` return empty; `REDACT_ALL` returns the fixed mask |
| `preserveStart + preserveEnd >= length` | mask everything; never fall back to returning the input |
| single-character email local part | mask it entirely rather than preserve it |
| string with no `@` under `EMAIL` | mask the whole string |

### FR-8 — Idempotency
`mask(mask(x))` equals `mask(x)`.

Acceptance: a property test asserts this for every mask type.

### FR-9 — Misconfiguration is loud
Invalid annotation usage fails with a `MaskingException` naming the record and component:
`PARTIAL`/`EMAIL`/`REDACT_ALL` on a non-`String` component, `NULLIFY` on a primitive component, or an
unusable preserve window. A window is unusable if either bound is negative, or if
`preserveStart + preserveEnd` overflows `int`, since the overflowed sum would defeat the "window covers
the whole value" check in FR-7 and drive the strategy into an index error.

Acceptance: each case has a test asserting the exception message names the offending component.

## 5. Non-functional requirements

- **NFR-1** Java 17 baseline, compiled with `--release 17`.
- **NFR-2** No runtime dependencies. Test dependencies only.
- **NFR-3** Thread-safe. Per-Record metadata is cached in a `ClassValue`, which avoids the
  classloader retention you get from a `Map<Class<?>, ?>` cache in redeployable containers.
- **NFR-4** No allocation when nothing changes: if no component of a Record was modified, the
  original instance is returned.
- **NFR-5** Public API surface is four types. Everything else is package-private.

## 6. Explicit non-goals for v0.1.0

Deferred on purpose, so the first release stays reviewable:

- POJO / JavaBean support — Records only.
- Jackson, Spring Boot, and logging integrations.
- Externalised policy (YAML, path expressions) for types you cannot annotate.
- Role- or context-conditional masking.
- Custom user-supplied strategies (SPI).
- Annotation processor / compile-time codegen and GraalVM native metadata.
- Deterministic tokenization (HMAC), format-preserving masking, date/number strategies.
- Published benchmarks.

## 7. Known limitations to document, not solve

1. **Canonical constructor validation.** Masking rebuilds the Record through its canonical
   constructor, so a compact constructor that validates a masked component (regex, Luhn, length)
   will reject the masked value and masking fails. Keep validation off masked components, or use a
   format-preserving mask.
2. **Map keys are not masked.** Only values are traversed, because masking keys can collide and
   silently drop entries.
3. **Collection components must be declared as interfaces.** Masking returns unmodifiable copies, so a
   component typed `ArrayList` or `HashMap` cannot hold the result and the canonical constructor rejects
   it. `List`, `Set`, `Map`, `SortedSet`, and `SortedMap` all work.
4. **`PARTIAL` and `EMAIL` preserve length**, which leaks the length of the original value. Use
   `REDACT_ALL` when length itself is sensitive.
5. **Module access.** Masking a Record in a named module requires that module to `open` its package,
   otherwise the canonical constructor is not reachable.
6. **Not anonymization.** This is redaction for display and logs. It is not encryption, not
   tokenization, and not pseudonymization, and it does not by itself make data non-personal.

## 8. Definition of done

- [x] All functional requirements have a passing JUnit 5 test.
- [x] `mvn verify` is green on Java 17+.
- [x] Every example in `README.md` is covered by a test asserting the exact documented output
      (`ReadmeExamplesTest`).
- [x] `README.md` claims nothing that the code does not do.

### Traceability

| Requirement | Covered by |
| :--- | :--- |
| FR-1 annotation | `ReadmeExamplesTest`, `MaskTypeTest` |
| FR-2 mask types | `MaskTypeTest` |
| FR-3 nested Records | `ReadmeExamplesTest.NestedRecords` |
| FR-4 containers | `TraversalTest.Containers` |
| FR-5 entry point | every test (no casts anywhere) |
| FR-6 fail closed | `FailClosedTest` |
| FR-7 edge cases | `MaskTypeTest`, `TraversalTest.NoRule` |
| FR-8 idempotency | `IdempotencyTest` |
| FR-9 misconfiguration | `MisconfigurationTest` |
| NFR-4 no needless allocation | `TraversalTest.NothingToMask` |
