# Off The Record

> Annotation-driven redaction for Java Records. Zero runtime dependencies, Java 17+.

[![Java Version](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-0.1.0--SNAPSHOT-yellow.svg)](docs/requirements-0.1.0.md)

`off-the-record` turns a Record holding sensitive values into a redacted copy of the same type, so
you can log it, print it, or hand it to a support tool without leaking PII.

```java
UserDto safe = Masker.mask(user);
log.info("checkout failed for {}", safe);
```

Not published to Maven Central yet. See [Installation](#installation).

---

## Why this exists

Masking libraries from the Java 8 era redact values by mutating private fields through
`Field.setAccessible(true)`. That does not work on Records: their fields are `final` and immutable by
design, and reaching into them across module boundaries fails under JDK strong encapsulation.

`off-the-record` does the only thing that is correct for an immutable type: it reads the components,
applies the redaction rules, and builds a new instance through the canonical constructor. The
original object is never touched.

There are good masking libraries that operate on JSON text or on `Map` structures. This one works on
your typed objects, so the thing you get back is still a `UserDto`.

## Installation

Nothing is on Maven Central yet, so build and install it locally:

```bash
git clone https://github.com/mabdulloh/off-the-record.git
cd off-the-record
mvn install
```

Then depend on the snapshot:

```xml
<dependency>
    <groupId>io.github.mabdulloh</groupId>
    <artifactId>off-the-record-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## The annotation

Put `@Mask` on the Record components that hold sensitive values.

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Mask {
    MaskType value() default MaskType.REDACT_ALL;
    int preserveStart() default 0;
    int preserveEnd() default 0;
    char maskChar() default '*';
}
```

`preserveStart`, `preserveEnd`, and `maskChar` apply to `PARTIAL`. `maskChar` also applies to
`EMAIL` and `REDACT_ALL`.

## Mask types

| Type | Behaviour | Input | Output |
| :--- | :--- | :--- | :--- |
| `REDACT_ALL` | fixed-length mask, hides the original length | `hunter2-super-secret` | `********` |
| `PARTIAL` | keep `preserveStart` leading and `preserveEnd` trailing chars, same length out | `4532111122228888` (`preserveEnd = 4`) | `************8888` |
| `EMAIL` | keep the first char of the local part and the domain | `john.doe@domain.com` | `j*******@domain.com` |
| `NULLIFY` | drop the value | `anything` | `null` |

`REDACT_ALL` is the default because it leaks the least. Reach for the others deliberately.

## Usage

### Nested Records

```java
import io.github.mabdulloh.offtherecord.Mask;
import io.github.mabdulloh.offtherecord.Masker;

import static io.github.mabdulloh.offtherecord.MaskType.EMAIL;
import static io.github.mabdulloh.offtherecord.MaskType.PARTIAL;

public record Address(
        String city,
        @Mask(value = PARTIAL, preserveStart = 2) String zipCode
) {}

public record UserDto(
        String id,
        @Mask(EMAIL) String email,
        @Mask(value = PARTIAL, preserveEnd = 4) String creditCard,
        @Mask String password,
        Address address
) {}

var user = new UserDto(
        "usr_9981",
        "developer@company.com",
        "4532111122228888",
        "s3cr3t-p4ssw0rd",
        new Address("Jakarta", "10110"));

UserDto masked = Masker.mask(user);

masked.id();                 // "usr_9981"              unannotated, untouched
masked.email();              // "d********@company.com"
masked.creditCard();         // "************8888"
masked.password();           // "********"
masked.address().city();     // "Jakarta"
masked.address().zipCode();  // "10***"                 nested Record, masked
```

### Records inside collections

`List`, `Set`, `Map` values, `Optional`, and object arrays are traversed for you.

```java
public record PaymentMethod(
        String provider,
        @Mask(value = PARTIAL, preserveEnd = 4) String cardNumber
) {}

public record Account(
        String username,
        List<PaymentMethod> savedCards,
        Map<String, PaymentMethod> vault
) {}

Account masked = Masker.mask(account);
// every PaymentMethod in savedCards and in vault comes back masked, order preserved
```

You can also mask a collection directly:

```java
List<UserDto> maskedUsers = Masker.mask(users);
```

## How it works

Records are inspected once per type. The canonical constructor and the component accessors are
resolved to `MethodHandle`s and cached in a `ClassValue`, keyed by the Record class. Subsequent calls
read components through the cached handles, apply the mask, and invoke the cached constructor.

`ClassValue` rather than a `ConcurrentHashMap<Class<?>, ?>`: a map keyed by `Class` holds a strong
reference to the class and, through it, its classloader, which leaks on redeploy in application
servers.

If no component of a Record actually changed, the original instance is returned rather than an
identical copy.

No published benchmarks yet. When there are, they will report ns/op and allocation rate rather than a
single headline multiplier.

## Design rules

**Fail closed.** No failure path returns the unmasked value.

- A strategy that throws yields a full redaction.
- A canonical constructor that rejects the masked values raises `MaskingException`. The original
  object is not returned as a fallback.
- Exceeding the traversal depth limit (32) raises `MaskingException`. This is also what catches
  reference cycles.

**Idempotent.** `mask(mask(x))` equals `mask(x)`.

**Loud about misconfiguration.** `MaskingException` at first use, naming the record and component,
if you put `PARTIAL`/`EMAIL`/`REDACT_ALL` on a non-`String` component, `NULLIFY` on a primitive, or an
unusable preserve window: either bound negative, or a `preserveStart + preserveEnd` that overflows
`int` and would otherwise slip past the "the window covers the whole value" check.

**Defined edges.** `null` stays `null`. Empty strings stay empty under `PARTIAL` and `EMAIL`. When
`preserveStart + preserveEnd` covers the whole value, everything is masked rather than nothing. A
single-character email local part is masked instead of preserved.

## Known limitations

1. **Canonical constructor validation.** Masking rebuilds the Record through its canonical
   constructor. A compact constructor that validates a masked component will reject the masked value
   and masking will fail. Keep validation off masked components, or preserve enough of the format.
2. **Map keys are not masked**, only values. Masking keys can collide and silently drop entries.
3. **`PARTIAL` and `EMAIL` preserve length**, which reveals the original length. Use `REDACT_ALL`
   when the length itself is sensitive.
4. **Records only.** Regular classes and JavaBeans are not supported in 0.1.0.
5. **Declare collection components as interfaces** (`List`, `Set`, `Map`, `SortedSet`, `SortedMap`), not
   as concrete types like `ArrayList` or `HashMap`. Masking returns unmodifiable copies, so a component
   typed as a concrete class cannot hold the result and the canonical constructor will reject it.
6. **Named modules** must `open` the package containing the Record, otherwise its canonical
   constructor is not reachable.
7. **This is redaction, not anonymization.** It is not encryption, tokenization, or pseudonymization,
   and on its own it does not make data non-personal under any regulation.

## Scope of 0.1.0

In: the four mask types, nested Records, container traversal, fail-closed behaviour, the metadata
cache.

Out, on purpose: POJO support, Jackson and Spring Boot integrations, externalised policy for types
you cannot annotate, role-conditional masking, custom strategies, compile-time codegen, tokenization.

Full requirements, including acceptance criteria, are in
[docs/requirements-0.1.0.md](docs/requirements-0.1.0.md).

## Roadmap

Nothing here is started. Order is intent, not commitment.

- POJO and JavaBean support
- `MaskStrategy` SPI so you can register your own
- Jackson module, for masking at serialization time
- Spring Boot starter, for masking REST responses
- Logback and Log4j2 support
- Externalised policy for DTOs you do not own
- Annotation processor: generated maskers, safe `toString`, GraalVM native without reflection metadata
- Deterministic HMAC tokenization, so masked values still correlate across logs

## Building

```bash
mvn verify
```

Requires JDK 17 or newer. The library itself is compiled with `--release 17`.

## License

MIT. See [LICENSE](LICENSE).
