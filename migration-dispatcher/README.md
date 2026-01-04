# Migration Dispatcher

A command-line interface (CLI) for dispatching SQL migration messages to RabbitMQ.

## Overview

The Migration Dispatcher is a Spring Boot CLI application that publishes migration messages to a RabbitMQ exchange for consumption by migration workers.

## Usage

### Basic Usage

```bash
./gradlew :migration-dispatcher:bootRun --args="-m <migration-id> -v <version> -f <sql-file>"
```

### Command Line Options

| Option | Long Form | Required | Description |
|--------|-----------|----------|-------------|
| `-m` | `--migration-id` | Yes | Unique migration identifier |
| `-v` | `--version` | Yes | Migration version |
| `-f` | `--file` | Yes* | Path to SQL file |
| `-s` | `--sql` | Yes* | SQL string (alternative to -f) |
| `-r` | `--requested-by` | No | User requesting migration (default: "cli-user") |
| `-h` | `--help` | No | Show help message |

*Either `-f` or `-s` must be provided

### Examples

#### Dispatch migration from SQL file

```bash
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-add-users-table -v 20260104.1 -f /path/to/migration.sql -r RonnieJakobsgaard"
```

#### Dispatch migration with inline SQL

```bash
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-add-users-table -v 20260104.1 -s 'CREATE TABLE users (id SERIAL PRIMARY KEY, name VARCHAR(200));' -r RonnieJakobsgaard"
```

#### Using environment variables

```bash
export SPRING_RABBITMQ_HOST=rabbitmq-prod.example.com
export SPRING_RABBITMQ_USERNAME=prod-user
export SPRING_RABBITMQ_PASSWORD=secret
export MIGRATIONS_EXCHANGE=migrations.prod.exchange

./gradlew :migration-dispatcher:bootRun --args="-m 20260104-add-users-table -v 20260104.1 -f migration.sql"
```

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_RABBITMQ_HOST` | RabbitMQ host | `localhost` |
| `SPRING_RABBITMQ_PORT` | RabbitMQ port | `5672` |
| `SPRING_RABBITMQ_USERNAME` | RabbitMQ username | `guest` |
| `SPRING_RABBITMQ_PASSWORD` | RabbitMQ password | `guest` |
| `MIGRATIONS_EXCHANGE` | Exchange name | `migrations.exchange` |

## Message Format

The dispatcher creates messages in the following JSON format:

```json
{
  "migrationId": "20260104-add-users-table",
  "version": "20260104.1",
  "type": "sql",
  "payload": "CREATE TABLE users (id SERIAL PRIMARY KEY, name VARCHAR(200));",
  "metadata": {
    "requestedBy": "RonnieJakobsgaard"
  }
}
```

## Building

```bash
./gradlew :migration-dispatcher:build
```

## Creating a Standalone JAR

```bash
./gradlew :migration-dispatcher:bootJar
```

The JAR will be created at: `migration-dispatcher/build/libs/migration-dispatcher-0.0.1-SNAPSHOT.jar`

### Running the Standalone JAR

```bash
java -jar migration-dispatcher/build/libs/migration-dispatcher-0.0.1-SNAPSHOT.jar \
  -m 20260104-add-users-table \
  -v 20260104.1 \
  -f migration.sql \
  -r RonnieJakobsgaard
```

## Integration with CI/CD

### Example: GitHub Actions

```yaml
- name: Dispatch Migration
  run: |
    java -jar migration-dispatcher.jar \
      -m "${{ github.sha }}-migration" \
      -v "${{ github.run_number }}" \
      -f migrations/latest.sql \
      -r "${{ github.actor }}"
  env:
    SPRING_RABBITMQ_HOST: ${{ secrets.RABBITMQ_HOST }}
    SPRING_RABBITMQ_USERNAME: ${{ secrets.RABBITMQ_USER }}
    SPRING_RABBITMQ_PASSWORD: ${{ secrets.RABBITMQ_PASS }}
```

### Example: Jenkins

```groovy
sh """
  java -jar migration-dispatcher.jar \
    -m ${BUILD_NUMBER}-migration \
    -v ${BUILD_NUMBER} \
    -f migrations/schema-update.sql \
    -r jenkins
"""
```

## Troubleshooting

### Connection Refused

Ensure RabbitMQ is running and accessible:

```bash
telnet localhost 5672
```

### Authentication Failed

Verify credentials:

```bash
rabbitmqctl list_users
```

### Exchange Not Found

The exchange is created automatically by the worker. Ensure at least one worker has started before dispatching migrations.

## Best Practices

1. **Migration IDs**: Use descriptive, timestamped IDs (e.g., `20260104-add-users-table`)
2. **Idempotent SQL**: Write SQL that can be safely re-executed (use `IF NOT EXISTS`, etc.)
3. **Version Control**: Store SQL files in version control
4. **Testing**: Test migrations in a non-production environment first
5. **Rollback Scripts**: Create corresponding rollback migrations

## See Also

- [Migration Worker README](../migration-worker/README.md)
- [Distributed Migrations Documentation](../docs/distributed-migrations.md)
