# Distributed Migrations Architecture

This document describes the distributed migration framework for MigratorX, which uses RabbitMQ for message queuing and Spring Boot workers for execution.

## Overview

The distributed migration system consists of two main components:

1. **Migration Dispatcher**: CLI application that reads SQL files and publishes migration messages to RabbitMQ
2. **Migration Worker**: Spring Boot application that consumes messages, executes migrations on PostgreSQL, and tracks history

## Architecture

```
┌─────────────────────┐
│  Migration          │
│  Dispatcher (CLI)   │
│                     │
│  Reads SQL file     │
│  Publishes message  │
└──────────┬──────────┘
           │
           │ AMQP
           ▼
┌─────────────────────┐
│   RabbitMQ          │
│                     │
│  migrations.exchange│
│  ├─ worker.queue    │
│  ├─ retry.queue     │
│  └─ failed.queue    │
└──────────┬──────────┘
           │
           │ AMQP
           ▼
┌─────────────────────┐
│  Migration Worker   │
│  (Spring Boot)      │
│                     │
│  1. Claim migration │
│  2. Execute SQL     │
│  3. Update history  │
│  4. Handle retries  │
└──────────┬──────────┘
           │
           │ JDBC
           ▼
┌─────────────────────┐
│   PostgreSQL        │
│                     │
│  - Target DB        │
│  - migration_history│
└─────────────────────┘
```

## Message Format

Migration messages are JSON objects with the following structure:

```json
{
  "migrationId": "20260104-add-users-table",
  "version": "20260104",
  "type": "SQL",
  "payload": "CREATE TABLE users (...);",
  "metadata": {
    "requestedBy": "admin@example.com",
    "sourceFile": "migrations/sql/20260104-add-users-table.sql",
    "dispatchedAt": "2026-01-04T10:30:00Z"
  }
}
```

### Fields

- **migrationId** (required): Unique identifier for the migration. Used for idempotency and deduplication.
- **version** (optional): Version string for tracking and ordering migrations.
- **type** (required): Migration type, typically "SQL" for SQL migrations.
- **payload** (required): Full SQL text to be executed.
- **metadata** (optional): Additional information about the migration request.
  - **requestedBy**: User or system that requested the migration
  - **sourceFile**: Original file path
  - **dispatchedAt**: ISO 8601 timestamp when message was dispatched

## RabbitMQ Topology

The system uses a direct exchange with three queues:

### migrations.exchange (Direct Exchange)

Main exchange for routing migration messages.

### migrations.worker.queue

- **Purpose**: Main queue where workers consume migration messages
- **Durability**: Durable (survives broker restarts)
- **Routing Key**: `migrations.worker.queue`

### migrations.retry.queue

- **Purpose**: Temporary queue for failed migrations awaiting retry
- **Durability**: Durable
- **TTL**: 30 seconds (configurable via `MIGRATION_RETRY_TTL`)
- **Dead Letter Exchange**: `migrations.exchange`
- **Dead Letter Routing Key**: `migrations.worker.queue`

After TTL expires, messages are automatically republished to the worker queue.

### migrations.failed.queue

- **Purpose**: Permanent storage for migrations that exceeded max retry attempts
- **Durability**: Durable
- **Routing Key**: `migrations.failed.queue`

Messages in this queue require manual intervention.

## Migration Execution Flow

### 1. Claim Migration

The worker attempts to claim a migration by inserting a record into `migration_history`:

```sql
INSERT INTO migration_history 
(migration_id, version, checksum, status, node_id, requested_by, started_at)
VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, NOW())
```

The **unique constraint** on `migration_id` ensures atomic claiming:
- **Success**: Migration is claimed, worker proceeds to execute
- **Duplicate Key Error**: Migration already exists
  - If status is `APPLIED`: Skip (already completed)
  - Otherwise: Raise error (conflict)

### 2. Execute SQL

If claimed successfully, the worker:
1. Begins a database transaction
2. Executes the SQL payload using `JdbcTemplate.execute()`
3. Updates status to `APPLIED` on success
4. Rolls back and updates status to `FAILED` on error

### 3. Update History

On success:
```sql
UPDATE migration_history 
SET status = 'APPLIED', applied_at = NOW()
WHERE migration_id = ? AND status = 'IN_PROGRESS'
```

On failure:
```sql
UPDATE migration_history 
SET status = 'FAILED', error = ?
WHERE migration_id = ? AND status = 'IN_PROGRESS'
```

### 4. Retry Logic

If execution fails, the worker:
1. Reads the `x-retry-count` header from the message (default 0)
2. Increments the retry count
3. If retry count < max attempts (default 3):
   - Republishes message to `migrations.retry.queue` with updated header
   - Message waits in retry queue for TTL duration
   - Automatically republished to worker queue after TTL
4. If retry count >= max attempts:
   - Publishes message to `migrations.failed.queue`
   - Logs error for manual investigation

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

### Fields

- **id**: Auto-incrementing primary key
- **migration_id**: Unique migration identifier (ensures idempotency)
- **version**: Migration version for ordering
- **checksum**: SHA-256 hash of payload for verification
- **status**: Current status (`IN_PROGRESS`, `APPLIED`, `FAILED`)
- **node_id**: Identifier of worker that executed the migration
- **requested_by**: User who requested the migration
- **started_at**: When migration execution started
- **applied_at**: When migration was successfully applied
- **error**: Error message if migration failed

## Configuration

### Environment Variables

#### Migration Worker

| Variable | Default | Description |
|----------|---------|-------------|
| SPRING_DATASOURCE_URL | jdbc:postgresql://localhost:5432/migrations | PostgreSQL URL |
| SPRING_DATASOURCE_USERNAME | postgres | Database username |
| SPRING_DATASOURCE_PASSWORD | postgres | Database password |
| SPRING_RABBITMQ_HOST | localhost | RabbitMQ host |
| SPRING_RABBITMQ_PORT | 5672 | RabbitMQ port |
| SPRING_RABBITMQ_USERNAME | guest | RabbitMQ username |
| SPRING_RABBITMQ_PASSWORD | guest | RabbitMQ password |
| MIGRATIONS_EXCHANGE | migrations.exchange | Exchange name |
| MIGRATIONS_QUEUE | migrations.worker.queue | Worker queue |
| MIGRATIONS_RETRY_QUEUE | migrations.retry.queue | Retry queue |
| MIGRATIONS_FAILED_QUEUE | migrations.failed.queue | Failed queue |
| MIGRATION_RETRY_ATTEMPTS | 3 | Max retry attempts |
| MIGRATION_RETRY_TTL | 30000 | Retry delay (ms) |

#### Migration Dispatcher

| Variable | Default | Description |
|----------|---------|-------------|
| SPRING_RABBITMQ_HOST | localhost | RabbitMQ host |
| SPRING_RABBITMQ_PORT | 5672 | RabbitMQ port |
| SPRING_RABBITMQ_USERNAME | guest | RabbitMQ username |
| SPRING_RABBITMQ_PASSWORD | guest | RabbitMQ password |
| MIGRATIONS_EXCHANGE | migrations.exchange | Exchange name |
| MIGRATIONS_QUEUE | migrations.worker.queue | Queue routing key |

## Usage Examples

### 1. Start Infrastructure

```bash
docker-compose up -d postgres rabbitmq
```

Wait for services to be healthy:
```bash
docker-compose ps
```

### 2. Start Migration Worker

```bash
cd migration-worker
mvn clean package
java -jar target/migration-worker-1.0.0-SNAPSHOT.jar
```

Or with Docker Compose:
```bash
docker-compose up -d migration-worker
```

### 3. Dispatch a Migration

```bash
cd migration-dispatcher
mvn clean package
java -jar target/migration-dispatcher-1.0.0-SNAPSHOT.jar \
  ../migrations/sql/20260104-add-users-table.sql
```

### 4. Monitor Execution

Check worker logs:
```bash
docker-compose logs -f migration-worker
```

Check RabbitMQ management UI:
```
http://localhost:15672
Username: guest
Password: guest
```

Query migration history:
```sql
SELECT migration_id, version, status, applied_at, error
FROM migration_history
ORDER BY started_at DESC;
```

### 5. Handle Failed Migrations

Inspect failed queue in RabbitMQ UI or via CLI:
```bash
docker exec migratorx-rabbitmq rabbitmqctl list_queues
```

Retrieve and inspect failed messages, fix issues, and redispatch:
```bash
java -jar target/migration-dispatcher-1.0.0-SNAPSHOT.jar \
  migrations/sql/fixed-migration.sql \
  original-migration-id
```

## Scaling Workers

Multiple worker instances can run concurrently:

```bash
docker-compose up -d --scale migration-worker=3
```

Benefits:
- **Parallel Execution**: Different migrations executed simultaneously
- **High Availability**: Worker failures don't stop processing
- **Load Distribution**: RabbitMQ distributes messages across workers

The atomic claiming mechanism prevents duplicate execution even with multiple workers.

## Security Considerations

1. **SQL Injection**: Payload contains raw SQL. Only trusted sources should dispatch migrations.
2. **Secrets Management**: Use environment variables or secret managers for database credentials.
3. **Network Security**: Use TLS for RabbitMQ and PostgreSQL in production.
4. **Access Control**: Restrict who can dispatch migrations (authentication/authorization layer).
5. **Audit Trail**: `migration_history` provides complete audit log with user tracking.

## Monitoring and Observability

Key metrics to monitor:

1. **Queue Depths**: 
   - High worker queue depth → Need more workers
   - Growing failed queue → Investigate failures

2. **Processing Time**: Track `started_at` to `applied_at` duration

3. **Success Rate**: Ratio of APPLIED to FAILED migrations

4. **Retry Rate**: Frequency of messages in retry queue

5. **Worker Health**: Database and RabbitMQ connection status

Integrate with monitoring tools:
- **Prometheus**: Expose Spring Boot Actuator metrics
- **Grafana**: Visualize queue depths and processing times
- **ELK Stack**: Centralize and analyze worker logs

## Troubleshooting

### Migration Stuck in IN_PROGRESS

**Cause**: Worker crashed or was killed during execution

**Solution**:
```sql
-- Check stuck migrations
SELECT * FROM migration_history 
WHERE status = 'IN_PROGRESS' 
AND started_at < NOW() - INTERVAL '1 hour';

-- Reset if safe
UPDATE migration_history 
SET status = 'FAILED', error = 'Worker timeout - reset manually'
WHERE migration_id = '<stuck-migration-id>' AND status = 'IN_PROGRESS';
```

### High Retry Queue Depth

**Cause**: Persistent execution failures (syntax errors, permission issues)

**Solution**:
1. Check worker logs for error patterns
2. Fix SQL syntax or database permissions
3. Drain retry queue if needed
4. Redispatch with corrected SQL

### Failed Queue Growing

**Cause**: Migrations exceeding max retry attempts

**Solution**:
1. Investigate common failure causes
2. Fix underlying issues (permissions, network, syntax)
3. Reset `migration_history` for affected migrations
4. Redispatch corrected migrations

## Best Practices

1. **Test Migrations**: Test SQL on staging before dispatching to production
2. **Idempotent SQL**: Use `IF NOT EXISTS` clauses when possible
3. **Small Batches**: Break large changes into smaller migrations
4. **Version Naming**: Use consistent naming (YYYYMMDD-description)
5. **Rollback Plan**: Prepare rollback migrations for schema changes
6. **Monitor Actively**: Watch queues and logs during migration windows
7. **Document Changes**: Include comments in SQL explaining purpose
8. **Validate First**: Run EXPLAIN or dry-run before dispatching

## Future Enhancements

Potential improvements:

1. **Rollback Support**: Automatic rollback on failure with down migrations
2. **Dependency Management**: Declare and enforce migration dependencies
3. **Scheduling**: Delayed execution for off-peak deployment
4. **Approval Workflow**: Multi-stage approval before execution
5. **Webhook Notifications**: Alert on migration completion or failure
6. **Multi-Database**: Support for multiple target databases
7. **Schema Validation**: Pre-execution validation against target schema
8. **Migration Preview**: Dry-run mode to see what would be executed
