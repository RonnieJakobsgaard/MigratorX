# Migration Dispatcher

A command-line tool to dispatch SQL migration messages to the MigratorX worker queue via RabbitMQ.

## Overview

The Migration Dispatcher is a Spring Boot CLI application that:
- Reads SQL from files or command-line arguments
- Constructs migration messages
- Publishes messages to RabbitMQ for workers to consume

## Requirements

- Java 17+
- RabbitMQ 3.13+

## Configuration

Configure the dispatcher using environment variables:

- `SPRING_RABBITMQ_HOST` - RabbitMQ host (default: `localhost`)
- `SPRING_RABBITMQ_PORT` - RabbitMQ port (default: `5672`)
- `SPRING_RABBITMQ_USERNAME` - RabbitMQ username (default: `migratorx`)
- `SPRING_RABBITMQ_PASSWORD` - RabbitMQ password (default: `migratorx123`)
- `MIGRATIONS_EXCHANGE` - Exchange name (default: `migrations.exchange`)
- `MIGRATIONS_ROUTING_KEY` - Routing key (default: `migrations.request`)

## Building

```bash
./gradlew :migration-dispatcher:build
```

## Usage

### Dispatch from SQL File

```bash
./gradlew :migration-dispatcher:bootRun --args="--migration-id=20260104-add-users-table --version=20260104.1 --file=/path/to/migration.sql --requested-by=YourUsername"
```

### Dispatch SQL String Directly

```bash
./gradlew :migration-dispatcher:bootRun --args="--migration-id=20260104-add-users-table --version=20260104.1 --sql='CREATE TABLE users (id SERIAL PRIMARY KEY, name VARCHAR(200));' --requested-by=YourUsername"
```

### Show Help

```bash
./gradlew :migration-dispatcher:bootRun --args="--help"
```

## Command-Line Options

| Option | Short | Description | Required |
|--------|-------|-------------|----------|
| `--migration-id` | `-m` | Unique migration identifier | Yes |
| `--version` | `-v` | Migration version | Yes |
| `--file` | `-f` | Path to SQL file | No* |
| `--sql` | `-s` | SQL string directly | No* |
| `--type` | `-t` | Migration type (default: sql) | No |
| `--requested-by` | `-r` | Username (default: system user) | No |
| `--help` | `-h` | Show help message | No |

*Either `--file` or `--sql` must be provided.

## Examples

### Example 1: Create a Table

Create a file `create_users_table.sql`:
```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    email VARCHAR(200) UNIQUE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);
```

Dispatch it:
```bash
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-create-users -v 1.0.0 -f create_users_table.sql -r RonnieJakobsgaard"
```

### Example 2: Add an Index

```bash
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-add-email-index -v 1.0.1 -s 'CREATE INDEX idx_users_email ON users(email);' -r RonnieJakobsgaard"
```

### Example 3: Insert Data

```bash
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-seed-users -v 1.0.2 -s \"INSERT INTO users (name, email) VALUES ('John Doe', 'john@example.com'), ('Jane Smith', 'jane@example.com');\" -r RonnieJakobsgaard"
```

## Running with Docker Compose

From the repository root:

```bash
# Start RabbitMQ
docker-compose up -d rabbitmq

# Dispatch a migration
export SPRING_RABBITMQ_HOST=localhost
export SPRING_RABBITMQ_USERNAME=migratorx
export SPRING_RABBITMQ_PASSWORD=migratorx123

./gradlew :migration-dispatcher:bootRun --args="-m my-migration -v 1.0 -f migration.sql"
```

## Output

Upon successful dispatch, the tool outputs:

```
Migration dispatched successfully:
  Migration ID: 20260104-add-users-table
  Version: 20260104.1
  Type: sql
  Exchange: migrations.exchange
  Routing Key: migrations.request
```

## Message Format

The dispatcher creates JSON messages in this format:

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

## Error Handling

The dispatcher will exit with code 1 if:
- Required arguments are missing
- SQL file cannot be read
- Connection to RabbitMQ fails

Check stderr for error messages.

## Verifying Messages

You can verify messages are queued using the RabbitMQ Management UI:

1. Open http://localhost:15672
2. Login with `migratorx` / `migratorx123`
3. Navigate to Queues
4. Check `migrations.worker.queue` for pending messages

## Best Practices

1. **Use descriptive migration IDs**: Include date and description (e.g., `20260104-add-users-table`)
2. **Use semantic versioning**: Version your migrations (e.g., `1.0.0`, `1.0.1`)
3. **Test SQL before dispatching**: Run SQL in a test environment first
4. **Keep migrations idempotent**: Use `IF NOT EXISTS` clauses where possible
5. **Track dispatched migrations**: Keep a log of what you've dispatched

## Troubleshooting

### Cannot connect to RabbitMQ
- Verify RabbitMQ is running: `docker-compose ps rabbitmq`
- Check connection settings
- Ensure firewall allows port 5672

### Message sent but not processed
- Check that migration-worker is running
- Verify exchange and queue exist in RabbitMQ
- Check worker logs for errors

### SQL file not found
- Use absolute paths or paths relative to project root
- Verify file exists and is readable
