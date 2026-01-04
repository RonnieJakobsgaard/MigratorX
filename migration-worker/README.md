# Migration Worker

A Spring Boot microservice that consumes migration messages from RabbitMQ and applies them to a PostgreSQL database.

## Features

- **Atomic Migration Claiming**: Uses unique constraint on `migration_id` to ensure each migration is executed only once
- **Automatic Retries**: Failed migrations are retried up to 3 times with 30-second delays
- **Dead Letter Queue**: Messages that exceed retry limit are moved to a failed queue for manual review
- **Manual Acknowledgments**: Ensures messages are only acknowledged after database commit
- **Persistent Messages**: All messages and queues are durable

## Architecture

### Queue Structure

1. **migrations.worker.queue**: Main queue for processing migrations
2. **migrations.retry.queue**: TTL queue (30s) that re-routes back to main queue
3. **migrations.failed.queue**: Dead letter queue for permanently failed migrations

### Migration Lifecycle

1. Message arrives in `migrations.worker.queue`
2. Worker attempts atomic INSERT into `migration_history` with `status=IN_PROGRESS`
3. If INSERT fails (duplicate key), message is acknowledged and skipped
4. If INSERT succeeds, SQL is executed
5. On success: Update status to `APPLIED`, calculate checksum, acknowledge message
6. On failure: 
   - If retries < 3: Send to retry queue with incremented counter
   - If retries >= 3: Nack message (routes to failed queue)

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/migratorx` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `migratorx` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | `migratorx` |
| `SPRING_RABBITMQ_HOST` | RabbitMQ host | `localhost` |
| `SPRING_RABBITMQ_PORT` | RabbitMQ port | `5672` |
| `SPRING_RABBITMQ_USERNAME` | RabbitMQ username | `guest` |
| `SPRING_RABBITMQ_PASSWORD` | RabbitMQ password | `guest` |
| `MIGRATIONS_EXCHANGE` | Exchange name | `migrations.exchange` |
| `MIGRATIONS_QUEUE` | Worker queue name | `migrations.worker.queue` |
| `MIGRATIONS_RETRY_QUEUE` | Retry queue name | `migrations.retry.queue` |
| `MIGRATIONS_FAILED_QUEUE` | Failed queue name | `migrations.failed.queue` |
| `MIGRATION_RETRY_ATTEMPTS` | Max retry attempts | `3` |

## Building

```bash
./gradlew :migration-worker:build
```

## Running

### With Docker Compose

```bash
# From project root
docker-compose up -d postgres rabbitmq
./gradlew :migration-worker:bootRun
```

### With Environment Variables

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://db-host:5432/mydb
export SPRING_DATASOURCE_USERNAME=myuser
export SPRING_DATASOURCE_PASSWORD=mypass
export SPRING_RABBITMQ_HOST=rabbitmq-host

./gradlew :migration-worker:bootRun
```

## Message Format

The worker expects JSON messages with the following structure:

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

### Fields

- **migrationId**: Unique identifier for the migration (must be unique across all migrations)
- **version**: Version string for tracking
- **type**: Migration type (currently only "sql" is supported)
- **payload**: The SQL script to execute
- **metadata**: Optional metadata map (e.g., requestedBy)

## Database Schema

The worker automatically creates the `migration_history` table:

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

## Testing

```bash
./gradlew :migration-worker:test
```

## Monitoring

Check migration status:

```sql
SELECT migration_id, status, started_at, applied_at, error 
FROM migration_history 
ORDER BY started_at DESC;
```

Check failed migrations:

```sql
SELECT * FROM migration_history WHERE status = 'FAILED';
```

## Troubleshooting

### Message stuck in retry loop

Check RabbitMQ management UI for messages in retry queue with high retry counts.

### Migration marked as IN_PROGRESS but not completed

This can happen if the worker crashes mid-migration. Manually update status or restart migration:

```sql
UPDATE migration_history 
SET status = 'FAILED', error = 'Worker crashed' 
WHERE migration_id = 'your-migration-id' AND status = 'IN_PROGRESS';
```

Then republish the migration message.
