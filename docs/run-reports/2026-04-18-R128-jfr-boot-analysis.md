# R128 JFR boot-profile analysis — observations only

Tester attached JFR dumps for 4 services in
`docs/run-reports/r128-full-axis-bundle/jfr/r128-jfr-boot-profiles.zip`.
This is a read-only analysis to plan R129+ boot-time work. No code change
here — per `feedback_verify_at_runtime`, a structural boot-path change
needs tester-side validation, and the observations below need a specific
hypothesis + measurement-after-change loop.

## Hotspots by sample count (onboarding-api boot, 200 s)

| Rank | Hotspot | Samples | Nature |
|---|---|---|---|
| 1 | `org.springframework.boot.loader.*` (ZipContent, JarUrlConnection, NestedJarFile) | ~180 | Fat-JAR nested-jar unpacking |
| 2 | `org.antlr.v4.runtime.atn.ParserATNSimulator.*` | ~42 | Hibernate JPQL parser (every `@Query` parsed at boot) |
| 3 | `java.net.URL.<init>` / `URLStreamHandler.sameFile` / `ParseUtil.encodePath` | ~60 | URL-equality inside JAR loader — sibling cost of #1 |
| 4 | `java.lang.invoke.InvokerBytecodeGenerator.*` / `LambdaForm.compileToBytecode` | ~30 | Method-handle generation (AOP proxies, reflective invokers) |
| 5 | `java.util.concurrent.ConcurrentHashMap.computeIfAbsent` | ~25 | Reflection-cache warm-up |

## Exception counts during boot (onboarding-api)

```
5703 java.lang.ClassNotFoundException     (bootstrap classloader probing — normal noise)
 868 java.lang.NoSuchFieldException       (reflection probing)
 366 org.springframework.security.access.AccessDeniedException
 365 org.springframework.security.authorization.AuthorizationDeniedException
 365 InsufficientAuthenticationException
 171 io.jsonwebtoken.ExpiredJwtException
 195 java.io.EOFException                 (SPIRE / gRPC reconnects)
 285 java.lang.NoSuchMethodException
 127 java.lang.NoSuchMethodError
```

365 × 3 ≈ **1095 Spring-Security exceptions during a single cold boot.**
Each throw builds a stack trace — non-trivial fill-in cost. Likely sources:
- Health probes hitting `/actuator/health` before `SecurityAutoConfiguration`
  is ready.
- SPIFFE `PlatformJwtAuthFilter` processing probe requests while the
  JwtSource is still warming (R111 added a 5 s wait; the exception spam
  suggests we're exceeding that window in practice).
- The 171 `ExpiredJwtException` is one retry cycle hitting the same stale
  JWT over and over.

## What the profile does NOT explain

edi-converter booted in 18 s in the same run. That container uses
`exclude = { DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class }`,
so the ANTLR JPQL parse cost + Hibernate metamodel scan don't apply. The
~150 s gap between edi-converter and every other service is approximately
the combined cost of rows 2–4 above plus the exception-stacktrace tax.

## R129 hypotheses worth testing (one at a time, with runtime re-measurement)

1. **Silence probe-driven Security exceptions.** If actuator/health is
   accepting requests before auth is ready, configure `/actuator/health/**`
   as permitAll in shared-security so Spring Security never throws. Expected
   win: ~1 s per service (stack-trace fill-in cost × ~1095 on main thread).
2. **Set `hibernate.query.fail_on_pagination_over_collection_fetch=false`** +
   confirm `spring.data.jpa.repositories.bootstrap-mode=lazy` reaches every
   service (should — it's in JAVA_TOOL_OPTIONS — but worth verifying with a
   startup log assertion).
3. **Unpack the fat JAR to an exploded layout** at image-build time.
   `spring-boot-maven-plugin` has a `PROPERTIES` mode where dependencies
   are pre-extracted. Saves the entire #1 hotspot — the cost moves to
   image build, which is already cached by Docker layers. Estimated win:
   15–25 s per service if the ZipContent cost is as dominant as samples suggest.
4. **Consolidate SPIFFE retry loops.** 171 `ExpiredJwtException` + 195
   `EOFException` implies the workload client is burning cycles on the
   same unreachable endpoint during the agent handshake window. Add
   exponential backoff + short-circuit on repeat failure.

## What NOT to do

Do not attempt #3 without first confirming fewer ClassNotFoundException
throws on the exploded layout — Spring Boot's nested classloader has special
handling for jar: URLs that changes when you switch layouts. Could regress
SPIFFE / Flyway resource discovery if mis-configured.

---

Raw JFR files extracted under `/tmp/jfr-r128/*.jfr` for follow-up. The
`docker-compose.jfr.yml` from tester's bundle can be composed in to collect
a fresh profile after any proposed change.
