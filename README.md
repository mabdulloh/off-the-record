# Off The Record

> Annotation-driven redaction for Java records and beans. Zero runtime dependencies, Java 17+.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mabdulloh/off-the-record-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.mabdulloh/off-the-record-core)
[![CI](https://github.com/mabdulloh/off-the-record/actions/workflows/ci.yml/badge.svg)](https://github.com/mabdulloh/off-the-record/actions/workflows/ci.yml)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

`off-the-record` turns a DTO holding sensitive values into a redacted copy of the same type, so you can
log it, print it, or hand it to a support tool without leaking PII. Records and plain classes both work.

```java
UserDto safe = Masker.mask(user);
log.info("checkout failed for {}", safe);
```

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

Available on Maven Central.

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

No transitive dependencies come with it.

## The annotation

Put `@Mask` on the properties that hold sensitive values: a record component, or a field, or its getter.

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Mask {
    MaskType value() default MaskType.REDACT_ALL;
    Class<? extends MaskStrategy> with() default MaskStrategy.class;
    int preserveStart() default 0;
    int preserveEnd() default 0;
    char maskChar() default '*';
}
```

`value()` picks a built-in; `with()` points at [your own strategy](#custom-strategies). They are mutually
exclusive. `preserveStart`, `preserveEnd`, and `maskChar` apply to `PARTIAL`; `maskChar` also applies to
`EMAIL`, to `REDACT_ALL`, and to every fail-closed redaction.

On a class, annotate the field or the getter, never both.

## Mask types

| Type | Behaviour | Input | Output |
| :--- | :--- | :--- | :--- |
| `REDACT_ALL` | fixed-length mask, hides the original length | `hunter2-super-secret` | `********` |
| `PARTIAL` | keep `preserveStart` leading and `preserveEnd` trailing chars, same length out | `4532111122228888` (`preserveEnd = 4`) | `************8888` |
| `EMAIL` | keep the first char of the local part and the domain | `john.doe@domain.com` | `j*******@domain.com` |
| `NULLIFY` | drop the value | `anything` | `null` |

`REDACT_ALL` is the default because it leaks the least. Reach for the others deliberately.

## Usage

### Nested records

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

### Inside collections

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

### Plain classes

Beans work the same way. Annotate the field or the getter, and you get back a copy of the same type:

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

Values are read through getters and written through the class's own public API, never by touching a field.
That is the same reason this library exists, and it does not get suspended for classes.

Copying uses whichever of these the class supports, in this order:

| Mechanism | Needs |
| :--- | :--- |
| No-arg constructor plus setters | a getter and setter for every property. Preferred: it is the only one that can mask a reference cycle |
| `toBuilder()` builder | a public `toBuilder()` and `build()`, plus a builder setter per property |
| All-args constructor | parameter names matching the properties, so the class must be compiled with `-parameters` |

Never by field order. `Class.getDeclaredFields()` is documented as returning fields in no particular
order, so matching a constructor positionally would work on one JDK and silently swap two same-typed
properties on another. An unmasked value landing in the wrong field is the one failure this library must
not have, so when parameter names are unavailable it declines and says so.

If a class supports none of the three, reading still works, so an object that needs no masking passes
through untouched. Only one that actually changed and cannot be rebuilt fails.

Inherited properties are masked too. Records and beans nest inside each other freely.

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

The engine holds the guarantees so you do not have to. Your strategy is never handed a `null`. A return
value the property cannot hold becomes a full redaction. Anything you throw, including a
`ClassCastException` from a careless cast, becomes a full redaction rather than reaching the caller.

Unlike the built-ins, a custom strategy is not restricted to `String`:

```java
public record Patient(@Mask(with = YearOnlyStrategy.class) LocalDate dateOfBirth) {}
```

`MaskType` implements `MaskStrategy` itself, so the four built-ins are ordinary strategies. If the SPI
could not express them, it would not be enough for yours either.

## How it works

Each class is inspected once. Accessors, setters, constructors, and builder methods are resolved to
`MethodHandle`s and cached in a `ClassValue`, keyed by the class. Subsequent calls read through the cached
handles, apply the mask, and rebuild through the cached constructor or setters.

`ClassValue` rather than a `ConcurrentHashMap<Class<?>, ?>`: a map keyed by `Class` holds a strong
reference to the class and, through it, its classloader, which leaks on redeploy in application
servers.

Each `mask()` call keeps an identity map of original to masked copy. That does two things. Reference
cycles terminate, and an object reachable from two places yields one copy, so a diamond in the input stays
a diamond in the output instead of quietly becoming a tree.

If nothing actually changed, the original instance is returned rather than an identical copy. Anything
from the JDK, and every enum constant, is left alone rather than walked into.

No published benchmarks yet. When there are, they will report ns/op and allocation rate rather than a
single headline multiplier.

## Design rules

**Fail closed.** No failure path returns the unmasked value.

- A strategy that throws, or returns a value the property cannot hold, yields a full redaction.
- A getter that throws yields a full redaction, rather than failing the call.
- A constructor, setter, or builder that rejects the masked values raises `MaskingException`. The
  original object is not returned as a fallback.
- A reference cycle through a type that cannot be built blank and populated afterwards raises
  `MaskingException`, naming the path it took.

**Idempotent.** `mask(mask(x))` equals `mask(x)`.

**Loud about misconfiguration.** `MaskingException` at first use, naming the type and property, if you
put `PARTIAL`/`EMAIL`/`REDACT_ALL` on a non-`String` property, `NULLIFY` on a primitive, `@Mask` on both a
field and its getter, `@Mask` on a property with no getter, both `value()` and `with()`, a strategy with no
public no-arg constructor, or an unusable preserve window: either bound negative, or a
`preserveStart + preserveEnd` that overflows `int` and would otherwise slip past the "the window covers
the whole value" check.

**Defined edges.** `null` stays `null`. Empty strings stay empty under `PARTIAL` and `EMAIL`. When
`preserveStart + preserveEnd` covers the whole value, everything is masked rather than nothing. A
single-character email local part is masked instead of preserved.

## Known limitations

1. **Validation on the write path.** Masking rebuilds through a canonical constructor, a setter, or a
   builder. Anything that validates a masked property (regex, checksum, length) will reject the redacted
   value and masking will fail. Keep validation off masked properties, or preserve enough of the format.
2. **Map keys are not masked**, only values. Masking keys can collide and silently drop entries.
3. **`PARTIAL` and `EMAIL` preserve length**, which reveals the original length. Use `REDACT_ALL`
   when the length itself is sensitive.
4. **A class needs one of the three copy mechanisms.** Private final fields with no builder, no setters,
   and no name-matched constructor cannot be copied, because masking will not write fields directly.
5. **Declare collection properties as interfaces** (`List`, `Set`, `Map`, `SortedSet`, `SortedMap`), not
   as concrete types like `ArrayList` or `HashMap`. Masking returns unmodifiable copies, so a property
   typed as a concrete class cannot hold the result.
6. **Cycles need setters.** A cycle through records, builders, or all-args constructors cannot be masked:
   the copy would have to exist before the values it is built from.
7. **Named modules** must `open` the package containing the type, otherwise its constructor is not
   reachable.
8. **This is for DTOs, not entities.** Masking a JPA entity calls its getters, which with an open session
   can trigger lazy loading and turn a `log.info` into an N+1. A getter that throws degrades to a
   redaction rather than failing, but the right answer is to mask the DTO you were going to return anyway.
9. **This is redaction, not anonymization.** It is not encryption, tokenization, or pseudonymization,
   and on its own it does not make data non-personal under any regulation.

## Scope

**0.1.0**: the four mask types, nested records, container traversal, fail-closed behaviour, the metadata
cache. See [docs/requirements-0.1.0.md](docs/requirements-0.1.0.md).

**0.2.0**: JavaBean support, the `MaskStrategy` SPI, identity-tracked traversal for cycles and shared
references. See [docs/requirements-0.2.0.md](docs/requirements-0.2.0.md).

Out, on purpose: Jackson and Spring Boot integrations, externalised policy for types you cannot annotate,
role-conditional masking, compile-time codegen, tokenization, JPA awareness.

## Roadmap

Nothing here is started. Order is intent, not commitment.

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

## Releasing

Publishing runs through the [Central Publisher Portal](https://central.sonatype.com), which replaced
OSSRH when that was shut down on 30 June 2025. Everything it needs lives in the `publish` profile, so a
normal build needs neither a GPG key nor a javadoc run.

```bash
mvn -Ppublish verify     # dry run: builds and signs the three jars, uploads nothing
mvn -Ppublish deploy     # the real thing
```

The upload is validated and then waits for you to press publish at
[central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments). Flip
`autoPublish` to `true` in the profile once you trust the pipeline.

### One-time setup

The Portal namespace, the token, and a non-`SNAPSHOT` version are already in place. What remains is a
signing key.

```bash
# 1. Generate it. Choose RSA and RSA, 4096 bits, and an expiry you will actually renew.
#    Use the same email as the <developer> entry in pom.xml.
gpg --full-generate-key

# 2. Note the long key id: the part after "sec rsa4096/" in the output.
gpg --list-secret-keys --keyid-format LONG

# 3. Publish the public half. Central verifies signatures against a keyserver, and this is the one
#    it checks. Allow a few minutes for propagation before deploying.
gpg --keyserver keyserver.ubuntu.com --send-keys <LONG_KEY_ID>
```

Then `mvn -Ppublish deploy` and let `gpg-agent` prompt for the passphrase. Nothing about the key goes
into `pom.xml`, `settings.xml`, or the environment, which is the point: `maven-gpg-plugin` 3.2+ refuses
to run if it finds the passphrase in your Maven configuration.

For a non-interactive build, export `MAVEN_GPG_PASSPHRASE` instead. If gpg then complains it cannot
prompt, add `pinentry-mode loopback` to `~/.gnupg/gpg.conf`.

### Later, from CI

`actions/setup-java` imports a key from a secret and writes the `central` server into `settings.xml`, so
the same profile works unchanged. Export the private half once and store it as the secret:

```bash
gpg --armor --export-secret-keys <LONG_KEY_ID>
```

That output is your private key. Paste it straight into the repository secret; never commit it, and
never paste it anywhere else.

## License

MIT. See [LICENSE](LICENSE).
