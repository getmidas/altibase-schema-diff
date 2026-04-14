# Development

Local build and run (no Gradle installation required; the project includes a wrapper).

## Prerequisites

- **Java 25** — e.g. `brew install openjdk@25` (macOS) or [Eclipse Temurin 25](https://adoptium.net/)
- Set `JAVA_HOME` or `PATH` so `java` is Java 25.

## Build

From the project root:

```bash
./gradlew shadowJar
```

Output: `build/libs/altibase-schema-diff.jar` (fat JAR with dependencies).

## Test

From the project root:

```bash
./gradlew test
```

Uses JUnit 5 (Jupiter). Test sources: `src/test/java/`. Add test classes under `com.f9n.altibase.schemadiff` (or mirror `src/main/java`). CI (`.github/workflows/ci.yml`) runs tests on every push and PR.

## Run locally

**Option A — use JAR from Releases:** download `altibase-schema-diff.jar` from [Releases](https://github.com/f9n/altibase-schema-diff/releases), then:

```bash
java -jar altibase-schema-diff.jar \
  --source-server <host1> \
  --target-server <host2> \
  --source-user <user> --source-password <password> \
  --target-user <user> --target-password <password>
```

**Option B — build from source:** after `./gradlew shadowJar`:

```bash
java -jar build/libs/altibase-schema-diff.jar \
  --source-server <host1> \
  --target-server <host2>
```

Or with environment variables:

```bash
export ALTIBASE_SOURCE_SERVER=<host1>
export ALTIBASE_SOURCE_USER=<user>
export ALTIBASE_SOURCE_PASSWORD=<password>
export ALTIBASE_TARGET_SERVER=<host2>
export ALTIBASE_TARGET_USER=<user>
export ALTIBASE_TARGET_PASSWORD=<password>

java -jar build/libs/altibase-schema-diff.jar
```

Or with Gradle (no JAR build needed):

```bash
./gradlew run --args='--source-server <host1> --target-server <host2>'
```

## Logging

The tool logs to stderr. Use `--debug` or `--trace` for more verbosity:

```bash
java -jar build/libs/altibase-schema-diff.jar --debug \
  --source-server <host1> --target-server <host2>
```

| Flag | Level | What it shows |
|------|-------|---------------|
| *(default)* | INFO | Schema counts, extraction time |
| `--debug` | DEBUG | Table/procedure/sequence/view names, per-schema timing |
| `--trace` | TRACE | Every column detail |

## Releases

When a **tag** is pushed (e.g. `v1.0.0`), GitHub Actions (`.github/workflows/release.yml`) runs:

1. **JAR** — Builds `altibase-schema-diff.jar` and attaches it to a GitHub Release for that tag.
2. **Docker** — Builds and pushes the image to [GitHub Container Registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry): `ghcr.io/<owner>/altibase-schema-diff:<tag>`.

To create a release: push a tag (e.g. `git tag v1.0.0 && git push origin v1.0.0`). Then download the JAR from the Releases page or pull the image: `docker pull ghcr.io/f9n/altibase-schema-diff:v1.0.0`.
