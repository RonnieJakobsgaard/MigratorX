# Migration Dispatcher

CLI application to dispatch migration messages to RabbitMQ for execution by the migration worker.

## Features

- **Simple CLI Interface**: Dispatch migrations with a single command
- **Automatic ID Generation**: Derives migration ID from filename
- **Version Extraction**: Automatically extracts version from filename patterns
- **Metadata Tracking**: Records who requested the migration and when

## Usage

Basic usage:

```bash
java -jar migration-dispatcher-1.0.0-SNAPSHOT.jar <sql-file-path>
```

With optional parameters:

```bash
java -jar migration-dispatcher-1.0.0-SNAPSHOT.jar <sql-file-path> [migration-id] [version] [requested-by]
```

### Parameters

- `sql-file-path` (required): Path to SQL migration file
- `migration-id` (optional): Custom migration identifier. Defaults to filename without extension
- `version` (optional): Migration version. Defaults to extracted version from filename
- `requested-by` (optional): User requesting the migration. Defaults to system username

### Examples

Dispatch a migration file:

```bash
java -jar migration-dispatcher-1.0.0-SNAPSHOT.jar migrations/sql/20260104-add-users-table.sql
```

With custom parameters:

```bash
java -jar migration-dispatcher-1.0.0-SNAPSHOT.jar \
  migrations/sql/V1.0__add_users.sql \
  add-users-migration \
  1.0 \
  admin@example.com
```

## Message Format

The dispatcher publishes a JSON message with the following structure:

```json
{
  "migrationId": "20260104-add-users-table",
  "version": "20260104",
  "type": "SQL",
  "payload": "CREATE TABLE users (...);",
  "metadata": {
    "requestedBy": "admin",
    "sourceFile": "migrations/sql/20260104-add-users-table.sql",
    "dispatchedAt": "2026-01-04T10:30:00Z"
  }
}
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| SPRING_RABBITMQ_HOST | localhost | RabbitMQ host |
| SPRING_RABBITMQ_PORT | 5672 | RabbitMQ port |
| SPRING_RABBITMQ_USERNAME | guest | RabbitMQ username |
| SPRING_RABBITMQ_PASSWORD | guest | RabbitMQ password |
| MIGRATIONS_EXCHANGE | migrations.exchange | Exchange name |
| MIGRATIONS_QUEUE | migrations.worker.queue | Queue routing key |

## Building

```bash
mvn clean package
```

## Running

```bash
java -jar target/migration-dispatcher-1.0.0-SNAPSHOT.jar migrations/sql/my-migration.sql
```

Or with custom RabbitMQ configuration:

```bash
SPRING_RABBITMQ_HOST=rabbitmq \
SPRING_RABBITMQ_USERNAME=admin \
SPRING_RABBITMQ_PASSWORD=secret \
java -jar target/migration-dispatcher-1.0.0-SNAPSHOT.jar migrations/sql/my-migration.sql
```

## Docker Compose

See the root `docker-compose.yml` for local testing with RabbitMQ.
