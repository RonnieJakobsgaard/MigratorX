# Migration Worker

A Spring Boot microservice that consumes migration messages from RabbitMQ and applies them to PostgreSQL databases.

## Overview

The Migration Worker is part of the MigratorX distributed migration framework. It:
- Listens for migration messages on RabbitMQ
- Claims migrations using a database lock mechanism (migration_history table)
- Applies SQL migrations transactionally
- Handles retries and dead-letter queuing for failed migrations
- Ensures idempotency through unique migration IDs

## Requirements

- Java 17+
- PostgreSQL 16+
- RabbitMQ 3.13+

## Configuration

Configure the worker using environment variables or application properties:

### Database Configuration
- `SPRING_DATASOURCE_URL` - PostgreSQL JDBC URL (default: `jdbc:postgresql://localhost:5432/migratorx`)
- `SPRING_DATASOURCE_USERNAME` - Database username (default: `migratorx`)
- `SPRING_DATASOURCE_PASSWORD` - Database password (default: `migratorx123`)

### RabbitMQ Configuration
- `SPRING_RABBITMQ_HOST` - RabbitMQ host (default: `localhost`)
- `SPRING_RABBITMQ_PORT` - RabbitMQ port (default: `5672`)
- `SPRING_RABBITMQ_USERNAME` - RabbitMQ username (default: `migratorx`)
- `SPRING_RABBITMQ_PASSWORD` - RabbitMQ password (default: `migratorx123`)

### Migration Configuration
- `MIGRATIONS_EXCHANGE` - Exchange name (default: `migrations.exchange`)
- `MIGRATIONS_QUEUE` - Worker queue name (default: `migrations.worker.queue`)
- `MIGRATIONS_RETRY_QUEUE` - Retry queue name (default: `migrations.retry.queue`)
- `MIGRATIONS_FAILED_QUEUE` - Failed queue name (default: `migrations.failed.queue`)
- `MIGRATION_RETRY_ATTEMPTS` - Max retry attempts (default: `3`)
- `MIGRATION_RETRY_TTL` - Retry TTL in milliseconds (default: `30000`)

## Building

```bash
./gradlew :migration-worker:build
```

## Running Locally

### With Docker Compose (recommended)

From the repository root:

```bash
# Start PostgreSQL and RabbitMQ
docker-compose up -d

# Run the worker
./gradlew :migration-worker:bootRun
```

### Standalone

```bash
# Set environment variables
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/migratorx
export SPRING_DATASOURCE_USERNAME=migratorx
export SPRING_DATASOURCE_PASSWORD=migratorx123
export SPRING_RABBITMQ_HOST=localhost
export SPRING_RABBITMQ_USERNAME=migratorx
export SPRING_RABBITMQ_PASSWORD=migratorx123

# Run the application
./gradlew :migration-worker:bootRun
```

## Message Format

The worker expects messages in the following JSON format:

```json
{
  "migrationId": "20260104-add-users-table",
  "version": "20260104.1",
  "type": "sql",
  "payload": "CREATE TABLE users (id SERIAL PRIMARY KEY, name VARCHAR(200));",
  "metadata": {
    "requestedBy": "YourUsername"
  }
}
```

## Migration History Table

The worker uses a `migration_history` table to track migrations:

```sql
CREATE TABLE IF NOT EXISTS migration_history (
    id BIGSERIAL PRIMARY KEY,
    migration_id TEXT NOT NULL UNIQUE,
    version TEXT,
    checksum TEXT,
    status TEXT NOT NULL,
    node_id TEXT,
    requested_by TEXT,
    started_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
    applied_at TIMESTAMP WITH TIME ZONE,
    error TEXT
);
```

This table is automatically created when the worker starts if it doesn't exist.

## Migration Statuses

- `IN_PROGRESS` - Migration is currently being applied
- `APPLIED` - Migration was successfully applied
- `FAILED` - Migration failed after all retries

## Retry Logic

The worker implements a retry mechanism:
1. Failed migrations are retried up to 3 times (configurable)
2. Messages are sent to a retry queue with a TTL (30 seconds default)
3. After max retries, messages are sent to the failed queue
4. The `x-death` header tracks retry count

## Health Check

The worker exposes actuator endpoints for health monitoring:

- Health: `http://localhost:8080/actuator/health`
- Info: `http://localhost:8080/actuator/info`

## Monitoring with RabbitMQ Management UI

When running with docker-compose, access the RabbitMQ management UI at:
- URL: http://localhost:15672
- Username: `migratorx`
- Password: `migratorx123`

## Testing

Run tests with:

```bash
./gradlew :migration-worker:test
```

## Troubleshooting

### Worker not consuming messages
- Check RabbitMQ connection settings
- Verify the exchange and queues are created
- Check RabbitMQ management UI for queue bindings

### Database connection issues
- Verify PostgreSQL is running and accessible
- Check credentials and connection string
- Ensure the database exists

### Migration fails but isn't retried
- Check the retry count in RabbitMQ management UI
- Verify `MIGRATION_RETRY_ATTEMPTS` configuration
- Check worker logs for error details

## Scaling

Multiple worker instances can run simultaneously. The migration_history table's unique constraint on `migration_id` ensures only one worker applies each migration.

```bash
# Run multiple workers
./gradlew :migration-worker:bootRun &
./gradlew :migration-worker:bootRun &
```
