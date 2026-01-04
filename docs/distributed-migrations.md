# Distributed Migrations Framework

## Overview

The Distributed Migrations Framework provides a scalable, fault-tolerant solution for applying database migrations in distributed environments. It uses RabbitMQ for message distribution and PostgreSQL for state management.

## Architecture

```
┌──────────────┐        ┌──────────────┐        ┌──────────────┐
│  Dispatcher  │───────▶│   RabbitMQ   │◀───────│    Worker    │
│     CLI      │        │   Exchange   │        │   (Node 1)   │
└──────────────┘        │   + Queues   │        └──────────────┘
                        │              │               │
                        └──────────────┘               │
                               │                       ▼
                               │                ┌──────────────┐
                               │                │  PostgreSQL  │
                               │                │  migration_  │
                               │                │   history    │
                               │                └──────────────┘
                               │                       ▲
                               │                       │
                               └──────────▶┌──────────────────┐
                                           │    Worker        │
                                           │   (Node 2)       │
                                           └──────────────────┘
```

## Components

### 1. Migration Dispatcher

A CLI tool that publishes migration messages to RabbitMQ.

**Responsibilities:**
- Read SQL migrations from files or command-line input
- Create JSON migration messages
- Publish messages to RabbitMQ exchange with persistent delivery mode

### 2. Migration Worker

A Spring Boot microservice that consumes and executes migrations.

**Responsibilities:**
- Consume messages from RabbitMQ queue
- Atomically claim migrations using database unique constraints
- Execute SQL migrations
- Update migration status and metadata
- Handle failures with automatic retries
- Use manual acknowledgments to ensure at-least-once delivery

### 3. RabbitMQ Infrastructure

**Exchanges:**
- `migrations.exchange` (DirectExchange, durable)

**Queues:**
- `migrations.worker.queue` (durable, DLX configured)
- `migrations.retry.queue` (durable, TTL=30s, DLX back to worker queue)
- `migrations.failed.queue` (durable, for permanently failed migrations)

**Routing Keys:**
- `worker`: Routes to main worker queue
- `retry`: Routes to retry queue
- `failed`: Routes to failed queue

### 4. PostgreSQL Database

**migration_history table:**
- Tracks all migrations and their execution status
- Uses UNIQUE constraint on `migration_id` for idempotency
- Stores checksums, error messages, and execution metadata

## Message Flow

### Successful Migration

```
1. Dispatcher publishes message to exchange with routing key "worker"
2. Message arrives in migrations.worker.queue
3. Worker receives message
4. Worker performs atomic INSERT into migration_history with status=IN_PROGRESS
5. Worker executes SQL payload
6. Worker updates migration_history with status=APPLIED, checksum, and timestamp
7. Worker acknowledges message to RabbitMQ
```

### Failed Migration (with retry)

```
1. Worker attempts to execute migration
2. Execution fails (e.g., syntax error)
3. Worker publishes message to retry queue with incremented retry counter
4. Worker acknowledges original message
5. Message sits in retry queue for 30 seconds (TTL)
6. After TTL expires, message is dead-lettered back to worker queue
7. Process repeats until max retries (3) exceeded
```

### Failed Migration (max retries exceeded)

```
1. Worker attempts migration for the 3rd time
2. Execution fails again
3. Worker negatively acknowledges (nack) message
4. Message is dead-lettered to migrations.failed.queue
5. Migration is marked as FAILED in database
```

### Duplicate Migration Detection

```
1. Worker receives message
2. Worker attempts INSERT into migration_history
3. INSERT fails due to UNIQUE constraint on migration_id
4. Worker acknowledges message (migration already processed)
5. No SQL execution occurs
```

## Idempotency Guarantees

The framework ensures each migration executes **exactly once** through:

1. **Database Constraint**: UNIQUE index on `migration_id` in `migration_history`
2. **Atomic Claim**: INSERT before execution ensures only one worker can claim a migration
3. **Status Tracking**: Status field prevents re-execution of completed migrations
4. **Checksum Validation**: SHA-256 checksums detect payload modifications

## Retry Behavior

**Configuration:**
- Default retry attempts: 3
- Default retry delay: 30 seconds
- Configurable via environment variables

**Retry Logic:**
1. Worker catches execution exception
2. Checks retry count in message header
3. If count < max retries: publishes to retry queue with incremented counter
4. If count >= max retries: nacks message (routes to failed queue)

**Retry Queue Behavior:**
- Messages enter with TTL of 30 seconds
- After TTL, dead-letter exchange routes back to worker queue
- Retry count preserved in message header

## Failure Scenarios

### Scenario 1: Worker Crashes During Execution

**State:** Migration marked as IN_PROGRESS in database

**Resolution:**
1. Identify stuck migrations: `SELECT * FROM migration_history WHERE status = 'IN_PROGRESS'`
2. Manually update status to FAILED
3. Re-publish migration message via dispatcher

### Scenario 2: Database Connection Lost

**State:** Message remains in RabbitMQ (not acknowledged)

**Resolution:**
1. Worker retries connection (Spring Boot default behavior)
2. If retries exhausted, message moves to retry queue
3. Retry queue provides additional attempts

### Scenario 3: Invalid SQL Syntax

**State:** Migration fails, enters retry cycle

**Resolution:**
1. After max retries, message moves to failed queue
2. Check failed queue in RabbitMQ Management UI
3. Fix SQL and re-dispatch corrected migration

### Scenario 4: Network Partition

**State:** Worker can't reach RabbitMQ or database

**Resolution:**
1. Messages remain in queue (unacknowledged)
2. Other workers can process if available
3. When network restored, worker resumes processing

## Scalability

### Horizontal Scaling

- Deploy multiple worker instances
- Each worker connects to same RabbitMQ and PostgreSQL
- RabbitMQ distributes messages across workers (prefetch=1)
- Database unique constraint prevents duplicate execution

### Performance Tuning

**RabbitMQ:**
```yaml
spring.rabbitmq.listener.simple.prefetch: 1  # Process one message at a time
spring.rabbitmq.listener.simple.concurrency: 5  # 5 concurrent consumers per worker
```

**Database Connection Pool:**
```yaml
spring.datasource.hikari.maximum-pool-size: 10
spring.datasource.hikari.minimum-idle: 5
```

## Security Considerations

### RabbitMQ

- Use dedicated user accounts with limited permissions
- Enable TLS for production deployments
- Restrict management UI access

### Database

- Use separate database user for migrations
- Grant only necessary permissions (INSERT, UPDATE, SELECT on migration_history)
- Enable SSL connections in production

### Message Validation

- Workers validate message schema before execution
- Reject malformed messages to failed queue
- Log all validation failures

## Monitoring and Observability

### Metrics to Monitor

1. **Queue Depth**: Length of migrations.worker.queue
2. **Failed Queue**: Number of messages in migrations.failed.queue
3. **Processing Rate**: Migrations per minute
4. **Retry Rate**: Messages entering retry queue
5. **Execution Time**: Average migration execution duration

### Logging

**Worker Logs:**
```
INFO: Received migration message: {migrationId}
INFO: Claimed migration: {migrationId} by node: {nodeId}
INFO: Successfully applied migration: {migrationId}
ERROR: Failed to apply migration: {migrationId}, error: {message}
```

**Database Queries:**
```sql
-- Recent migrations
SELECT migration_id, status, started_at, applied_at 
FROM migration_history 
ORDER BY started_at DESC 
LIMIT 10;

-- Failed migrations
SELECT migration_id, error, started_at 
FROM migration_history 
WHERE status = 'FAILED';

-- In-progress migrations (potential stuck)
SELECT migration_id, node_id, started_at,
       AGE(NOW(), started_at) as duration
FROM migration_history 
WHERE status = 'IN_PROGRESS';
```

### RabbitMQ Management UI

Access at: http://localhost:15672 (default credentials: guest/guest)

**Key Pages:**
- Queues: Monitor message counts and rates
- Exchanges: Verify bindings and routing
- Connections: See active worker connections

## Configuration Reference

### Worker Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| SPRING_DATASOURCE_URL | PostgreSQL JDBC URL | jdbc:postgresql://localhost:5432/migratorx |
| SPRING_DATASOURCE_USERNAME | Database username | migratorx |
| SPRING_DATASOURCE_PASSWORD | Database password | migratorx |
| SPRING_RABBITMQ_HOST | RabbitMQ host | localhost |
| SPRING_RABBITMQ_PORT | RabbitMQ port | 5672 |
| SPRING_RABBITMQ_USERNAME | RabbitMQ username | guest |
| SPRING_RABBITMQ_PASSWORD | RabbitMQ password | guest |
| MIGRATIONS_EXCHANGE | Exchange name | migrations.exchange |
| MIGRATIONS_QUEUE | Worker queue name | migrations.worker.queue |
| MIGRATIONS_RETRY_QUEUE | Retry queue name | migrations.retry.queue |
| MIGRATIONS_FAILED_QUEUE | Failed queue name | migrations.failed.queue |
| MIGRATION_RETRY_ATTEMPTS | Max retry attempts | 3 |

### Dispatcher Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| SPRING_RABBITMQ_HOST | RabbitMQ host | localhost |
| SPRING_RABBITMQ_PORT | RabbitMQ port | 5672 |
| SPRING_RABBITMQ_USERNAME | RabbitMQ username | guest |
| SPRING_RABBITMQ_PASSWORD | RabbitMQ password | guest |
| MIGRATIONS_EXCHANGE | Exchange name | migrations.exchange |

## Deployment

### Local Development

```bash
# Start infrastructure
docker-compose up -d postgres rabbitmq

# Start worker
./gradlew :migration-worker:bootRun

# Dispatch migration (in another terminal)
./gradlew :migration-dispatcher:bootRun --args="-m test-migration -v 1.0.0 -f migration.sql"
```

### Production Deployment

**Prerequisites:**
- PostgreSQL 13+ cluster
- RabbitMQ 3.12+ cluster
- Container orchestration platform (Kubernetes, ECS, etc.)

**Worker Deployment:**
1. Build Docker image: `docker build -t migration-worker:latest migration-worker/`
2. Deploy to container platform with appropriate environment variables
3. Configure autoscaling based on queue depth
4. Set up health checks on Spring Boot actuator endpoints

**Dispatcher Usage:**
1. Build standalone JAR: `./gradlew :migration-dispatcher:bootJar`
2. Distribute JAR to CI/CD systems
3. Execute as part of deployment pipelines

## Best Practices

1. **Migration Naming**: Use descriptive, timestamped IDs (e.g., `20260104-1430-add-users-table`)
2. **Idempotent SQL**: Always write migrations that are safe to re-run
3. **Testing**: Test migrations in non-production environments first
4. **Rollback Plans**: Create corresponding rollback migrations
5. **Small Changes**: Keep migrations focused on single logical changes
6. **Version Control**: Store all migration files in source control
7. **Monitoring**: Set up alerts for failed queue depth and stuck migrations
8. **Documentation**: Document complex migrations with comments in SQL

## Troubleshooting

### Problem: Messages not being consumed

**Checks:**
1. Verify worker is running: check logs for startup messages
2. Check RabbitMQ connection: look for connection errors in logs
3. Verify queue exists: check RabbitMQ Management UI
4. Check queue bindings: ensure proper routing key bindings

### Problem: Migrations stuck in IN_PROGRESS

**Resolution:**
```sql
-- Find stuck migrations (running > 10 minutes)
SELECT * FROM migration_history 
WHERE status = 'IN_PROGRESS' 
  AND started_at < NOW() - INTERVAL '10 minutes';

-- Mark as failed and investigate
UPDATE migration_history 
SET status = 'FAILED', 
    error = 'Timeout - manually marked as failed' 
WHERE migration_id = 'problematic-migration-id';
```

### Problem: High retry queue depth

**Causes:**
- Invalid SQL syntax
- Database connection issues
- Insufficient permissions

**Resolution:**
1. Check worker logs for error messages
2. Review failed migrations in database
3. Fix underlying issue
4. Purge retry queue if needed
5. Re-dispatch corrected migrations

## Future Enhancements

- Support for non-SQL migrations (data migrations, API calls)
- Migration dependency graph for ordering
- Parallel execution of independent migrations
- Web UI for monitoring and management
- Integration with schema versioning tools
- Automated rollback on failure

## References

- [Migration Worker README](../migration-worker/README.md)
- [Migration Dispatcher README](../migration-dispatcher/README.md)
- [RabbitMQ Documentation](https://www.rabbitmq.com/documentation.html)
- [Spring AMQP Reference](https://docs.spring.io/spring-amqp/reference/)
