# Installation

1. Build with Java 21 and `mvn clean install`. The resulting JAR targets Java 21 and also runs on Java 25.
2. Copy `target/StandaloneQueue-2.0.0.jar` to the Paper `plugins/` directory; remove older StandaloneQueue JARs first.
3. Use Java 21 for Paper 1.21.11; Java 25 for Paper 26.x.
4. Start once to generate `plugins/StandaloneQueue/config.yml`.
5. Review queue/anti-bot limits and permission policy. Use `/queue reload` for supported configuration changes; persistence settings require a server restart.

The target range is Paper 1.21.11–26.3. Paper 26.1.1 build 29 is ALPHA and 26.3 build 170 is BETA, so these compatibility results are experimental. Do not install the staging probe on production. No proxy is required.

See [actual version matrix and remaining production gates](../testing/real-paper.md), [migration/rollback](../releases/2.0.0.md), and [Paper Java requirements](https://docs.papermc.io/paper/getting-started/).
