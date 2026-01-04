# Distributed Migrations with MigratorX

## Architecture Overview

The MigratorX distributed migration framework enables SQL migrations to be dispatched as messages and processed asynchronously by one or more worker nodes. This architecture provides:

- **Scalability**: Run multiple workers to process migrations in parallel
- **Reliability**: Built-in retry logic and dead-letter queue for failed migrations
- **Idempotency**: Each migration is applied exactly once using database locking
- **Observability**: Track migration status and history in PostgreSQL

## Components

### 1. Migration Dispatcher
A CLI tool that reads SQL files or strings and publishes migration messages to RabbitMQ.

### 2. Migration Worker
A Spring Boot microservice that consumes migration messages and applies them to PostgreSQL.

### 3. RabbitMQ
Message broker that provides:
- Durable exchange for routing messages
- Worker queue for pending migrations
- Retry queue with TTL for failed migrations
- Dead-letter queue for permanently failed migrations

### 4. PostgreSQL
Database that stores:
- Application data
- `migration_history` table for tracking migration state

## Message Flow

```
┌─────────────┐       ┌───────────┐       ┌────────────┐       ┌────────────┐
│ Dispatcher  │──────▶│ RabbitMQ  │──────▶│   Worker   │──────▶│ PostgreSQL │
│    (CLI)    │       │  Exchange │       │  (Spring)  │       │            │
└─────────────┘       └───────────┘       └────────────┘       └────────────┘
                            │                    │
                            │                    │ (on failure)
                            ▼                    ▼
                      ┌──────────┐         ┌──────────┐
                      │  Retry   │────────▶│  Failed  │
                      │  Queue   │ (x3)    │  Queue   │
                      └──────────┘         └──────────┘
```

## RabbitMQ Topology

### Exchange
- **Name**: `migrations.exchange` (configurable)
- **Type**: Direct
- **Durable**: Yes
- **Purpose**: Routes migration messages to appropriate queues

### Queues

#### Worker Queue
- **Name**: `migrations.worker.queue`
- **Binding**: `migrations.exchange` with routing key `migrations.request`
- **Purpose**: Holds pending migrations for workers to consume
- **Durable**: Yes

#### Retry Queue
- **Name**: `migrations.retry.queue`
- **Binding**: `migrations.exchange` with routing key `migrations.request`
- **TTL**: 30 seconds (configurable)
- **Dead Letter Exchange**: `migrations.exchange`
- **Purpose**: Temporarily holds failed migrations before retry

#### Failed Queue
- **Name**: `migrations.failed.queue`
- **Binding**: `migrations.exchange` with routing key `migrations.failed`
- **Purpose**: Holds permanently failed migrations for investigation

## Message Format

### Structure

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

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `migrationId` | String | Yes | Unique identifier for the migration |
| `version` | String | Yes | Version number for ordering and tracking |
| `type` | String | Yes | Migration type (currently only "sql" supported) |
| `payload` | String | Yes | Full SQL text to execute |
| `metadata` | Object | No | Additional metadata (e.g., requestedBy) |

## Migration History Table

### Schema

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

### Fields

- **id**: Auto-incrementing primary key
- **migration_id**: Unique identifier (enforces idempotency)
- **version**: Migration version
- **checksum**: SHA-256 hash of the SQL payload
- **status**: Current status (IN_PROGRESS, APPLIED, FAILED)
- **node_id**: Hostname of the worker that processed the migration
- **requested_by**: User who dispatched the migration
- **started_at**: When the migration was claimed
- **applied_at**: When the migration completed successfully
- **error**: Error message if migration failed

### Status Values

- `IN_PROGRESS`: Worker is currently applying the migration
- `APPLIED`: Migration was successfully applied
- `FAILED`: Migration failed after all retries

## Idempotency Guarantee

The worker ensures each migration is applied exactly once:

1. Before processing, worker checks if `migration_id` exists in `migration_history`
2. If exists with status `APPLIED`: Skip (already done)
3. If exists with status `IN_PROGRESS`: Skip (another worker is processing)
4. If not exists: Insert row with status `IN_PROGRESS` (claims the migration)
5. If insert fails (duplicate key): Another worker claimed it first
6. Apply the SQL in a transaction
7. Update status to `APPLIED` or `FAILED`

## Retry Logic

### Retry Flow

1. Worker receives message and attempts to apply migration
2. If successful: Ack message, update status to `APPLIED`
3. If failed: Check retry count in `x-death` header
   - If under max attempts: Send back to queue for retry
   - If at max attempts: Send to failed queue

### Configuration

- **Max Attempts**: 3 (default, configurable via `MIGRATION_RETRY_ATTEMPTS`)
- **Retry Delay**: 30 seconds (configurable via `MIGRATION_RETRY_TTL`)

### Retry Count Tracking

RabbitMQ tracks retries using the `x-death` header, which includes:
- Queue name where message died
- Reason for death
- Count of deaths
- Original routing key

## Environment Variables

### Common

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_RABBITMQ_HOST` | localhost | RabbitMQ host |
| `SPRING_RABBITMQ_PORT` | 5672 | RabbitMQ port |
| `SPRING_RABBITMQ_USERNAME` | migratorx | RabbitMQ username |
| `SPRING_RABBITMQ_PASSWORD` | migratorx123 | RabbitMQ password |
| `MIGRATIONS_EXCHANGE` | migrations.exchange | Exchange name |
| `MIGRATIONS_ROUTING_KEY` | migrations.request | Routing key for worker queue |

### Worker-Specific

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | jdbc:postgresql://localhost:5432/migratorx | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | migratorx | Database username |
| `SPRING_DATASOURCE_PASSWORD` | migratorx123 | Database password |
| `MIGRATIONS_QUEUE` | migrations.worker.queue | Worker queue name |
| `MIGRATIONS_RETRY_QUEUE` | migrations.retry.queue | Retry queue name |
| `MIGRATIONS_FAILED_QUEUE` | migrations.failed.queue | Failed queue name |
| `MIGRATION_RETRY_ATTEMPTS` | 3 | Max retry attempts |
| `MIGRATION_RETRY_TTL` | 30000 | Retry delay in milliseconds |

## Quick Start

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- PostgreSQL on port 5432
- RabbitMQ on port 5672 (AMQP) and 15672 (Management UI)

### 2. Start Worker

```bash
./gradlew :migration-worker:bootRun
```

The worker will:
- Connect to PostgreSQL and RabbitMQ
- Create the `migration_history` table if needed
- Start listening for migration messages

### 3. Dispatch a Migration

Create a SQL file `example.sql`:
```sql
CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);
```

Dispatch it:
```bash
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-create-products -v 1.0.0 -f example.sql -r YourUsername"
```

### 4. Verify

Check the worker logs for:
```
INFO  c.m.w.a.MigrationListener - Received migration message: MigrationMessage{migrationId='20260104-create-products', ...}
INFO  c.m.w.s.MigrationService - Claimed migration 20260104-create-products on node ...
INFO  c.m.w.s.MigrationService - Successfully applied migration 20260104-create-products
```

Query the database:
```sql
SELECT * FROM migration_history;
SELECT * FROM products;
```

## Monitoring

### RabbitMQ Management UI

Access at http://localhost:15672 (username: `migratorx`, password: `migratorx123`)

Monitor:
- Queue lengths
- Message rates
- Consumer count
- Failed messages in dead-letter queue

### Application Health

Worker exposes actuator endpoints:
- Health: http://localhost:8080/actuator/health
- Info: http://localhost:8080/actuator/info

### Database Queries

Check migration history:
```sql
-- All migrations
SELECT migration_id, version, status, started_at, applied_at FROM migration_history ORDER BY started_at DESC;

-- Failed migrations
SELECT * FROM migration_history WHERE status = 'FAILED';

-- In-progress migrations (possible stuck migrations)
SELECT * FROM migration_history WHERE status = 'IN_PROGRESS' AND started_at < NOW() - INTERVAL '5 minutes';
```

## Troubleshooting

### Migration Stuck in IN_PROGRESS

**Symptom**: Migration shows IN_PROGRESS status for a long time

**Causes**:
- Worker crashed while processing
- Long-running migration

**Solution**:
```sql
-- Check status
SELECT * FROM migration_history WHERE status = 'IN_PROGRESS';

-- If worker crashed, manually reset to retry
UPDATE migration_history SET status = 'FAILED', error = 'Reset for retry' WHERE migration_id = 'YOUR_MIGRATION_ID';
```

### Messages Not Being Consumed

**Symptom**: Messages pile up in worker queue

**Checks**:
1. Verify worker is running
2. Check worker logs for errors
3. Verify database connectivity
4. Check RabbitMQ connection

### Migration Applied Multiple Times

**Should Not Happen** - The unique constraint on `migration_id` prevents this

If it happens:
1. Check database unique constraint exists
2. Verify worker is using transactions
3. Check for application bugs

### High Failure Rate

**Symptom**: Many messages in failed queue

**Investigation**:
1. Check worker logs for error patterns
2. Query `migration_history` for error messages
3. Verify SQL syntax
4. Check database permissions

```sql
SELECT migration_id, error, COUNT(*) 
FROM migration_history 
WHERE status = 'FAILED' 
GROUP BY migration_id, error 
ORDER BY COUNT(*) DESC;
```

## Best Practices

### Migration IDs

Use descriptive, timestamped IDs:
- ✅ `20260104-add-users-table`
- ✅ `20260104-001-create-schema`
- ❌ `migration1`
- ❌ `update`

### Versioning

Use semantic versioning:
- Major version for breaking changes
- Minor version for new features
- Patch version for fixes

Example: `1.2.3`

### SQL Guidelines

1. **Use transactions**: Worker wraps SQL in transactions, but be aware of DDL limitations
2. **Make idempotent**: Use `IF NOT EXISTS`, `IF EXISTS` clauses
3. **Test first**: Run SQL in dev environment before dispatching
4. **Keep small**: Large migrations can block workers
5. **Avoid long-running operations**: Consider breaking into smaller migrations

### Operational

1. **Monitor queues**: Set up alerts for queue depth
2. **Scale workers**: Add workers during high load
3. **Backup before major migrations**: Always have a rollback plan
4. **Review failed queue regularly**: Investigate and reprocess failures
5. **Maintain version order**: Dispatch migrations in order when they have dependencies

## Scaling

### Horizontal Scaling

Run multiple worker instances:

```bash
# Terminal 1
./gradlew :migration-worker:bootRun

# Terminal 2
./gradlew :migration-worker:bootRun

# Terminal 3
./gradlew :migration-worker:bootRun
```

Or with Docker:
```bash
docker-compose up --scale migration-worker=3
```

### Load Distribution

RabbitMQ distributes messages round-robin to available workers.
Workers use database locking to prevent duplicate processing.

### Performance Considerations

- Database connection pool size
- RabbitMQ prefetch count
- Worker instance resources (CPU, memory)

## Security Considerations

1. **Credentials**: Use environment variables, never hardcode
2. **SQL Injection**: Worker executes SQL as-is, validate before dispatching
3. **Access Control**: Restrict who can dispatch migrations
4. **Network Security**: Use TLS for RabbitMQ and PostgreSQL in production
5. **Audit Trail**: `migration_history` table tracks who requested each migration

## Future Enhancements

Potential improvements:
- Support for rollback migrations
- Integration with Flyway for version tracking
- Web UI for monitoring and manual dispatch
- Support for multi-tenancy
- Scheduled migrations
- Pre-flight validation of SQL
- Integration with CI/CD pipelines
