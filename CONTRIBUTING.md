# Contributing to StandaloneQueue

## Development setup

- JDK 21
- Paper API 1.21+
- Maven 3.9+ (the CI build uses `mvn -B clean verify`)

Run the checks locally with:

```bash
mvn -B clean verify
```

Keep changes focused, explain behavior changes, and add or update JUnit tests. Do not commit build output, IDE metadata, local dependency caches, or generated reports.

Before opening a PR, verify `git diff --check` and include manual reproduction steps for bug fixes.

Maven is the authoritative build. Gradle metadata is retained for IDE use and is not a release gate. Use injected clocks for queue deadlines; do not add sleeps to timing tests. See `docs/architecture/queue-invariants.md` and `docs/releases/1.1.0.md` before changing admission.

`mvn clean verify` runs Surefire unit/regressions and Failsafe Paper API listener integration tests. Build the optional staging probe with `mvn clean install` followed by `mvn clean verify -f staging-probe/pom.xml`. It is a separate test artifact; never bundle it into production. Real Paper/client/backend gates are recorded in `docs/testing/phase-2-staging.md`.
