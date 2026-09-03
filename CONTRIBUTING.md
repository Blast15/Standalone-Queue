# Contributing to StandaloneQueue

## Development setup

- JDK 21
- Paper API 1.21+
- Maven 3.9+ (the CI build uses `mvn -B clean package`)

Run the checks locally with:

```bash
mvn clean test
mvn clean package
```

Keep changes focused, explain behavior changes, and add or update JUnit tests. Do not commit build output, IDE metadata, local dependency caches, or generated reports.

Before opening a PR, verify `git diff --check` and include manual reproduction steps for bug fixes.
