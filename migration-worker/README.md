# Migration Worker

Spring Boot application that consumes migration messages from RabbitMQ and executes them on PostgreSQL.

## Features

- **Atomic Migration Claiming**: Uses unique constraint on `migration_id` to prevent double execution
- **Transactional Execution**: SQL payloads executed within database transactions
- **Retry Logic**: Failed migrations automatically retried with configurable attempts and delay
- **Failed Queue**: Migrations exceeding retry attempts moved to failed queue for manual inspection
- **Migration History**: Complete audit trail of all migrations with timestamps and status

## Architecture

The worker:
1. Listens to `migrations.worker.queue` for migration messages
2. Attempts to claim migration by inserting into `migration_history` with `IN_PROGRESS` status
3. If already applied (status=APPLIED), skips the migration
4. Executes SQL payload using JdbcTemplate within a transaction
5. Updates status to `APPLIED` on success or `FAILED` on error
6. On failure, republishes to retry queue with delay
7. After max attempts, moves to failed queue

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| SPRING_DATASOURCE_URL | jdbc:postgresql://localhost:5432/migrations | PostgreSQL connection URL |
| SPRING_DATASOURCE_USERNAME | postgres | Database username |
| SPRING_DATASOURCE_PASSWORD | postgres | Database password |
| SPRING_RABBITMQ_HOST | localhost | RabbitMQ host |
| SPRING_RABBITMQ_PORT | 5672 | RabbitMQ port |
| SPRING_RABBITMQ_USERNAME | guest | RabbitMQ username |
| SPRING_RABBITMQ_PASSWORD | guest | RabbitMQ password |
| MIGRATIONS_EXCHANGE | migrations.exchange | Exchange name |
| MIGRATIONS_QUEUE | migrations.worker.queue | Worker queue name |
| MIGRATIONS_RETRY_QUEUE | migrations.retry.queue | Retry queue name |
| MIGRATIONS_FAILED_QUEUE | migrations.failed.queue | Failed queue name |
| MIGRATION_RETRY_ATTEMPTS | 3 | Maximum retry attempts |
| MIGRATION_RETRY_TTL | 30000 | Retry delay in milliseconds |

## Queue Topology

```
migrations.exchange (direct)
├── migrations.worker.queue (main queue)
├── migrations.retry.queue (with TTL, DLX back to main queue)
└── migrations.failed.queue (dead letter for max retries)
```

## Migration History Schema

```sql
CREATE TABLE migration_history (
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

## Building

```bash
mvn clean package
```

## Running

```bash
java -jar target/migration-worker-1.0.0-SNAPSHOT.jar
```

Or with custom configuration:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/migrations \
SPRING_RABBITMQ_HOST=rabbitmq \
java -jar target/migration-worker-1.0.0-SNAPSHOT.jar
```

## Docker Compose

See the root `docker-compose.yml` for local testing with PostgreSQL and RabbitMQ.
