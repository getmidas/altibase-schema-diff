# altibase-schema-diff

Schema comparison and drift detection tool for Altibase Database clusters.

For local build, run, and releases, see [DEVELOPMENT.md](DEVELOPMENT.md).

---

Compares two Altibase servers and reports differences in:

- **Schemas** (users)
- **Tables** (column names, types, sizes, nullability, defaults)
- **Stored Procedures** (existence + source code)
- **Functions** (existence + source code)
- **Sequences** (min/max values, cycle, cache size)
- **Views** (existence + definition source)

## Quick Start

```bash
./gradlew shadowJar
java -jar build/libs/altibase-schema-diff.jar \
  --source-server 192.168.1.1 \
  --source-user sys \
  --source-password manager \
  --target-server 192.168.1.2 \
  --target-user sys \
  --target-password manager
```

## Usage

### CLI arguments

```bash
java -jar build/libs/altibase-schema-diff.jar \
  --source-server 192.168.1.1 \
  --source-port 20300 \
  --source-user sys \
  --source-password manager \
  --source-database mydb \
  --target-server 192.168.1.2 \
  --target-port 20300 \
  --target-user sys \
  --target-password manager \
  --target-database mydb
```

### Environment variables

All options can be set via environment variables. Useful for keeping passwords out of shell history:

```bash
export ALTIBASE_SOURCE_SERVER=192.168.1.1
export ALTIBASE_SOURCE_USER=sys
export ALTIBASE_SOURCE_PASSWORD=manager
export ALTIBASE_TARGET_SERVER=192.168.1.2
export ALTIBASE_TARGET_USER=sys
export ALTIBASE_TARGET_PASSWORD=manager

java -jar build/libs/altibase-schema-diff.jar
```

You can also mix environment variables with CLI arguments (CLI takes precedence):

```bash
export ALTIBASE_SOURCE_PASSWORD=secret123
export ALTIBASE_TARGET_PASSWORD=secret456

java -jar build/libs/altibase-schema-diff.jar \
  --source-server 192.168.1.1 \
  --target-server 192.168.1.2
```

Full list of environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `ALTIBASE_SOURCE_SERVER` | `127.0.0.1` | Source server hostname/IP |
| `ALTIBASE_SOURCE_PORT` | `20300` | Source server port |
| `ALTIBASE_SOURCE_USER` | `sys` | Source username |
| `ALTIBASE_SOURCE_PASSWORD` | `manager` | Source password |
| `ALTIBASE_SOURCE_DATABASE` | `mydb` | Source database |
| `ALTIBASE_TARGET_SERVER` | `127.0.0.1` | Target server hostname/IP |
| `ALTIBASE_TARGET_PORT` | `20300` | Target server port |
| `ALTIBASE_TARGET_USER` | `sys` | Target username |
| `ALTIBASE_TARGET_PASSWORD` | `manager` | Target password |
| `ALTIBASE_TARGET_DATABASE` | `mydb` | Target database |
| `ALTIBASE_CONNECT_TIMEOUT` | `10` | Connection timeout (seconds) |

### Filter specific schemas

```bash
java -jar build/libs/altibase-schema-diff.jar \
  --source-server 192.168.1.1 \
  --target-server 192.168.1.2 \
  --schemas USER1,USER2
```

### Caching

Schema extraction can be slow for large databases. The tool caches snapshots locally:

```bash
# Custom cache directory and TTL
java -jar build/libs/altibase-schema-diff.jar \
  --source-server 192.168.1.1 \
  --target-server 192.168.1.2 \
  --cache-dir /tmp/schema-cache \
  --cache-ttl 7200

# Disable caching
java -jar build/libs/altibase-schema-diff.jar \
  --source-server 192.168.1.1 \
  --target-server 192.168.1.2 \
  --no-cache
```

Default cache directory: `~/.altibase-schema-diff/cache/`
Default TTL: 3600 seconds (1 hour)

### Exit codes

| Code | Meaning |
|------|---------|
| `0` | No differences found |
| `1` | Connection or extraction error |
| `2` | Differences detected |

## Example output

```
=== Altibase Schema Diff ===
  Source: 192.168.1.1:20300/mydb
  Target: 192.168.1.2:20300/mydb

--- Schemas ---
  - TEST_USER [source only]

--- Tables ---
  Schema: USER1
    ~ ORDERS [different]
        + NEW_COLUMN VARCHAR(100) [target only]
        - OLD_COLUMN INTEGER [source only]
        ~ STATUS: type VARCHAR(20) → VARCHAR(50)

--- Stored Procedures ---
  Schema: USER1
    ~ UPDATE_STATUS [different]
        source code differs

--- Sequences ---
  Schema: USER1
    ~ ORDER_SEQ [different]
        MAX_VALUE: 999999 → 9999999

--- Summary ---
  Total differences: 4
    1 only in source
    1 only in target
    2 different
```

## Docker

**1. Pull the image** from [GitHub Container Registry](https://github.com/f9n/altibase-schema-diff/pkgs/container/altibase-schema-diff):

```bash
docker pull ghcr.io/f9n/altibase-schema-diff:latest
```

Or use a specific release tag (e.g. `v1.0.0`): `docker pull ghcr.io/f9n/altibase-schema-diff:v1.0.0`.

**2. Run:**

```bash
docker run --rm \
  -e ALTIBASE_SOURCE_SERVER=192.168.1.1 \
  -e ALTIBASE_SOURCE_USER=sys \
  -e ALTIBASE_SOURCE_PASSWORD=manager \
  -e ALTIBASE_TARGET_SERVER=192.168.1.2 \
  -e ALTIBASE_TARGET_USER=sys \
  -e ALTIBASE_TARGET_PASSWORD=manager \
  ghcr.io/f9n/altibase-schema-diff:latest
```

With CLI arguments:

```bash
docker run --rm ghcr.io/f9n/altibase-schema-diff:latest \
  --source-server 192.168.1.1 \
  --target-server 192.168.1.2 \
  --schemas USER1,USER2 \
  --no-cache
```

**Optional — build the image locally:** `docker build -t altibase-schema-diff .` then use `altibase-schema-diff` as the image name.

## Requirements

- Java 25+
- Altibase JDBC driver (bundled in fat JAR)

## License

GPL-3.0
