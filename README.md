# Off The Record

> Annotation-driven redaction for Java records and beans. Zero runtime dependencies, Java 17+.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mabdulloh/off-the-record-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.mabdulloh/off-the-record-core)
[![CI](https://github.com/mabdulloh/off-the-record/actions/workflows/ci.yml/badge.svg)](https://github.com/mabdulloh/off-the-record/actions/workflows/ci.yml)
[![Coverage](https://codecov.io/gh/mabdulloh/off-the-record/branch/main/graph/badge.svg)](https://codecov.io/gh/mabdulloh/off-the-record)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

`off-the-record` turns a DTO holding sensitive values into a redacted copy of the same type, so you can
log it, print it, or hand it to a support tool without leaking PII. Mark the sensitive properties once,
and the object you get back is still a `UserDto`, just safe to print.

```java
UserDto safe = Masker.mask(user);
log.info("checkout failed for {}", safe);
```

---

## Why this exists

Masking libraries from the Java 8 era redact values by mutating private fields through
`Field.setAccessible(true)`. That does not work on records: their fields are `final` and immutable by
design, and reaching into them across module boundaries fails under JDK strong encapsulation.

`off-the-record` does the only thing that is correct for an immutable type. It reads the properties,
applies the redaction rules, and builds a new instance through the type's own public API. The original
object is never touched, and no field is ever written directly, not even for a plain class.

There are good masking libraries that operate on JSON text or on `Map` structures. This one works on your
typed objects, so what comes back still type-checks.

## Getting started

```xml
<dependency>
    <groupId>io.github.mabdulloh</groupId>
    <artifactId>off-the-record-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("io.github.mabdulloh:off-the-record-core:0.1.0")
```

Nothing comes with it: no transitive dependencies, no configuration, no setup step.

Annotate a property and call `Masker.mask`:

```java
import io.github.mabdulloh.offtherecord.Mask;
import io.github.mabdulloh.offtherecord.Masker;

import static io.github.mabdulloh.offtherecord.MaskType.EMAIL;

public record Contact(String name, @Mask(EMAIL) String email) {}

Contact masked = Masker.mask(new Contact("Abdulloh", "developer@company.com"));

masked.name();    // "Abdulloh"                unannotated, untouched
masked.email();   // "d********@company.com"   redacted
```

## Features

- **Records and plain classes.** Both are first class. Beans are read through getters and copied through
  setters, a `toBuilder()`, or a constructor matched by parameter name.
- **Walks the whole graph.** Nested records and beans, plus `List`, `Set`, `Map` values, `Optional`, and
  object arrays, without you writing a loop.
- **Four built-in strategies**, and a [`MaskStrategy`](#custom-strategies) interface for anything else.
- **Fails closed.** A strategy that throws, a getter that blows up, or a return value the property cannot
  hold all become a full redaction. No failure path hands back the original.
- **Idempotent.** `mask(mask(x))` equals `mask(x)`, so double-masking is not a bug you have to think about.
- **Loud about mistakes.** A misplaced annotation fails at first use with a message naming the property,
  rather than silently doing nothing.
- **Keeps the shape.** An object reachable from two places yields one copy, and reference cycles
  terminate. If nothing changed, you get the original instance back rather than a needless copy.
- **Zero runtime dependencies.** Pure Java 17: `java.lang.invoke`, `java.lang.reflect`, nothing else.

## Examples

### Records, nested

```java
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

masked.id();                 // "usr_9981"
masked.email();              // "d********@company.com"
masked.creditCard();         // "************8888"
masked.password();           // "********"
masked.address().city();     // "Jakarta"
masked.address().zipCode();  // "10***"            nested record, masked too
```

### Plain classes

Annotate the field or the getter, never both:

```java
public class UserBean {
    private String id;
    @Mask(EMAIL) private String email;
    @Mask(value = PARTIAL, preserveEnd = 4) private String creditCard;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getCreditCard() { return creditCard; }
    public void setCreditCard(String creditCard) { this.creditCard = creditCard; }
}

UserBean masked = Masker.mask(user);   // "d********@company.com", "************8888"
```

Copying uses whichever mechanism the class supports, in this order:

| Mechanism | Needs |
| :--- | :--- |
| No-arg constructor plus setters | a getter and setter for every property. Preferred: the only one that can mask a reference cycle |
| `toBuilder()` builder | a public `toBuilder()` and `build()`, plus a builder setter per property |
| All-args constructor | parameter names matching the properties, so compile with `-parameters` |

Never by field order: `Class.getDeclaredFields()` returns fields in no guaranteed order, so positional
matching would silently swap two same-typed properties on a different JDK. When parameter names are
unavailable, masking declines and says so.

### Collections and maps

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

Collections work as the argument too:

```java
List<UserDto> maskedUsers = Masker.mask(users);
```

### Custom strategies

For anything the built-ins do not cover, implement `MaskStrategy`:

```java
public final class NikStrategy implements MaskStrategy {
    @Override
    public Object mask(Object value, MaskContext context) {
        String nik = (String) value;
        return nik.substring(0, 2) + "**********" + nik.substring(12);
    }
}

public record Citizen(@Mask(with = NikStrategy.class) String nationalId) {}
```

One instance per strategy class is created and shared, so it must be stateless, thread safe, idempotent,
and have a public no-arg constructor.

The engine holds the guarantees so you do not have to: your strategy is never handed a `null`, a return
value the property cannot hold becomes a full redaction, and anything you throw becomes a full redaction
rather than reaching the caller.

Unlike the built-ins, a custom strategy is not limited to `String`:

```java
public record Patient(@Mask(with = YearOnlyStrategy.class) LocalDate dateOfBirth) {}
```

`MaskType` implements `MaskStrategy` itself, so the four built-ins are ordinary strategies. If the SPI
could not express them, it would not be enough for yours either.

## Configuration

Everything is configured on the annotation.

```java
@Mask(value = PARTIAL, preserveEnd = 4, maskChar = '#')
```

#### `value`
Type: `MaskType`
Default: `REDACT_ALL`

Which built-in strategy to apply. Mutually exclusive with `with`.

| Value | Behaviour | Input | Output |
| :--- | :--- | :--- | :--- |
| `REDACT_ALL` | fixed-length mask, hides the original length | `hunter2-super-secret` | `********` |
| `PARTIAL` | keep the preserved edges, same length out | `4532111122228888` | `************8888` |
| `EMAIL` | keep the first char of the local part and the domain | `john.doe@domain.com` | `j*******@domain.com` |
| `NULLIFY` | drop the value | `anything` | `null` |

`REDACT_ALL` is the default because it leaks the least. Reach for the others deliberately: `PARTIAL` and
`EMAIL` preserve length, which reveals it.

#### `with`
Type: `Class<? extends MaskStrategy>`
Default: none

A [custom strategy](#custom-strategies) instead of a built-in. Applies to any property type, not just
`String`.

#### `preserveStart` / `preserveEnd`
Type: `int`
Default: `0`

How many leading and trailing characters `PARTIAL` keeps. If they cover the whole value, everything is
masked instead, so a short value is never leaked by a generous setting.

```java
@Mask(value = PARTIAL, preserveStart = 2) String zipCode;   // "10110" -> "10***"
@Mask(value = PARTIAL, preserveEnd = 4)   String card;      // "4532111122228888" -> "************8888"
```

#### `maskChar`
Type: `char`
Default: `'*'`

The replacement character, used by every built-in except `NULLIFY` and by every fail-closed redaction.

```java
@Mask(value = PARTIAL, preserveEnd = 4, maskChar = '#') String card;   // "############8888"
```

## Release history

- **0.2.0** — unreleased. Plain class and JavaBean support, the public `MaskStrategy` SPI, and
  identity-tracked traversal so cycles and shared references keep the graph's shape.
  [Requirements](docs/requirements-0.2.0.md).
- **0.1.0** — current release. The four built-in strategies, nested records, container traversal, and
  fail-closed behaviour. [Requirements](docs/requirements-0.1.0.md).

Each requirements document lists what the release deliberately does not do, and the limitations worth
knowing before you adopt it.

## Contributing

Fork the repository and work on a feature branch. Pull requests are warmly welcome.

Two things worth knowing before you open one. Every behaviour is pinned by a test, including every example
in this file, so `mvn verify` should stay green. And the library never calls `setAccessible(true)`: that is
the practice it exists to avoid, so a patch that reaches past a type's public API will not be merged.

## Links

- Repository: https://github.com/mabdulloh/off-the-record
- Issue tracker: https://github.com/mabdulloh/off-the-record/issues
  - For a security issue, please email muhammad.abdulloh93@gmail.com directly rather than opening a
    public issue.
- Maven Central: https://central.sonatype.com/artifact/io.github.mabdulloh/off-the-record-core

## Licensing

The code in this project is licensed under the MIT license. See [LICENSE](LICENSE).

`off-the-record` redacts values for display and logs. It is not encryption, tokenization, or
pseudonymization, and on its own it does not make data non-personal under any regulation.
