# Clearwave Spring Kensa Example

A showcase project demonstrating [Kensa](https://kensa.dev) running inside a Spring Boot application — using the `kensa-spring-boot-starter` and `kensa-spring-boot-starter-web` modules to wire BDD test reporting and auto-capture HTTP interactions.

The domain mirrors the sibling [`clearwave-example`](../clearwave-example), but the HTTP layer has been ported from `http4k` to Spring Boot Web. The UI tests are intentionally omitted — this project focuses on Spring-native server-side acceptance testing.

- **FeasibilityService** — checks whether a broadband service can be delivered to a given address. Built as a `@RestController` + `@Service`, fans out to two suppliers via `RestTemplate`.
- **OrderService** — places a broadband order and processes asynchronous supplier notifications back through the same Spring app's callback endpoint.

Tests are written using the Kensa Given-When-Then DSL. Each test boots a real Spring Boot application on a random port via `@SpringBootTest(webEnvironment = RANDOM_PORT)`; supplier traffic is served by two small in-process Spring Boot stub apps (`OpenNetworkStub` for JSON, `FibreVisionStub` for XML).

## Running locally

```bash
./gradlew test
```

Runs `FeasibilityServiceTest` (4 scenarios) and `OrderServiceTest` (3 scenarios, including async supplier notifications). The Kensa HTML report is written to `build/kensa-site/`.

To open the report:

```bash
kensa --dir build/kensa-site
```

## What this example demonstrates

| Feature | Where |
|---|---|
| `@KensaTest`-style meta-annotation | `support/ClearwaveSpringTest.kt` — composes `@SpringBootTest` with `KensaSpringExtension` + `KensaExtension`. |
| `kensa.*` properties from `application.yml` | `src/test/resources/application.yml` — title, package display, setup strategy, auto-open tab. |
| Runtime `Kensa.konfigure { ... }` for non-yaml settings | `support/ClearwaveTestConfig.kt` `@PostConstruct` block — acronyms (yaml binding doesn't cover compound types). |
| Custom party-aware HTTP capture | `support/PartyAwareInterceptors.kt` — overrides the default `Client`/`Server` participants with `Customer`, `FeasibilityService`, `OrderService`, `OpenNetwork`, `FibreVision` based on URL path and content type. |
| Supplier stubs as Spring Boot apps | `stubs/OpenNetworkStub.kt`, `stubs/FibreVisionStub.kt` — each boots its own Spring context on a random port, primed per `TrackingId` for parallel-safe test execution. |
| Async supplier notifications | `OrderServiceTest` — supplier stubs fire callbacks back to the SUT, asserted via `thenEventually`. |

## Wiring

The `kensa-spring-boot-starter` autoconfig provides `KensaSpringExtension`, which sits between `SpringExtension` and `KensaExtension` on `@KensaTest`. It binds `kensa.*` properties from the Spring `Environment` into `Kensa.configuration` before the lifecycle starts writing output. The `-web` module adds three auto-registered interceptors (`HandlerInterceptor`, `RestTemplateCustomizer`, `WebClientCustomizer`); this example overrides them with the party-aware variants in `support/PartyAwareInterceptors.kt`, taking advantage of the `@ConditionalOnMissingBean` backoff in the auto-config.

See [kensa.dev/docs/integrations/spring-boot-starter](https://kensa.dev/docs/integrations/spring-boot-starter) for the full integration documentation.

## Dependencies

| Library | Role |
|---|---|
| [Kensa Spring Boot Starter](https://kensa.dev/docs/integrations/spring-boot-starter) | `@KensaTest` annotation + `kensa.*` property binding |
| [Spring Boot 3.x](https://spring.io/projects/spring-boot) | Application framework + Web + Test |
| [JUnit Jupiter 5](https://junit.org/junit5/) | Test runtime |
| [Kotest](https://kotest.io/) | Assertions |
| [Jackson](https://github.com/FasterXML/jackson) | JSON / XML serialization |

## Prerequisites

You need a local install of the Kensa snapshot for the modules under development. From the kensa repo:

```bash
./gradlew publishToMavenLocal
```

The `gradle/libs.versions.toml` here pins `kensa = "0.8.0-SNAPSHOT"` and `mavenLocal()` is in the repository list.
