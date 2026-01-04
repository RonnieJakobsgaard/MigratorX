# MigratorX

A flexible database migration framework with support for both centralized and distributed migration strategies.

## Overview

MigratorX provides two migration approaches:

1. **Centralized Migrations**: Traditional Flyway-based migrations (existing functionality)
2. **Distributed Migrations**: RabbitMQ-based distributed migration execution (new)

## Project Structure

```
migratorx/
├── migratorx-core/          # Core migration framework (Flyway-based)
├── migration-worker/         # Distributed migration worker (Spring Boot + RabbitMQ)
├── migration-dispatcher/     # CLI for dispatching migrations to workers
├── docs/                     # Documentation
│   └── distributed-migrations.md
├── docker-compose.yml        # Local development infrastructure
└── README.md                # This file
```

## Features

### Core Features (migratorx-core)
- Flyway-based schema migrations
- JSON and direct migration providers
- Parallel migration execution
- Command-line interface

### Distributed Migration Features
- **RabbitMQ Message Queue**: Reliable message distribution
- **Atomic Migration Claiming**: Each migration executes exactly once
- **Automatic Retries**: Failed migrations retry up to 3 times with 30s delays
- **Dead Letter Queue**: Permanently failed migrations route to DLQ
- **Manual Acknowledgments**: Ensures at-least-once delivery
- **Horizontal Scaling**: Run multiple workers for high throughput
- **Status Tracking**: Complete migration history in PostgreSQL

## Quick Start

### Prerequisites

- Java 21+
- PostgreSQL 13+
- RabbitMQ 3.12+
- Gradle 8.7+

### Local Development Setup

1. **Start Infrastructure**

```bash
docker-compose up -d
```

This starts:
- PostgreSQL on port 5432
- RabbitMQ on port 5672 (AMQP) and 15672 (Management UI)

2. **Build the Project**

```bash
./gradlew build
```

3. **Start Migration Worker**

```bash
./gradlew :migration-worker:bootRun
```

4. **Dispatch a Migration** (in another terminal)

```bash
# Create a test migration file
echo "CREATE TABLE users (id SERIAL PRIMARY KEY, name VARCHAR(200));" > test-migration.sql

# Dispatch it
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-add-users-table -v 1.0.0 -f test-migration.sql -r YourName"
```

5. **Verify Migration**

```bash
# Connect to PostgreSQL
psql -h localhost -U migratorx -d migratorx

# Check migration history
SELECT migration_id, status, started_at, applied_at 
FROM migration_history 
ORDER BY started_at DESC;

# Check users table was created
\dt users
```

## Usage

### Distributed Migrations

#### Starting the Worker

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/migratorx
export SPRING_DATASOURCE_USERNAME=migratorx
export SPRING_DATASOURCE_PASSWORD=migratorx
export SPRING_RABBITMQ_HOST=localhost

./gradlew :migration-worker:bootRun
```

#### Dispatching Migrations

**From file:**
```bash
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-migration -v 1.0.0 -f migration.sql -r developer"
```

**Inline SQL:**
```bash
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-migration -v 1.0.0 -s 'CREATE TABLE test (id INT);' -r developer"
```

### Core Migrations (Flyway-based)

```bash
./gradlew :migratorx-core:bootRun --args="-i jdbc:postgresql://localhost:5432/mydb -u user -p pass -m /path/to/migrations"
```

## Configuration

### Environment Variables

#### Worker Configuration

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
| MIGRATIONS_QUEUE | Worker queue | migrations.worker.queue |
| MIGRATION_RETRY_ATTEMPTS | Max retries | 3 |

#### Dispatcher Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| SPRING_RABBITMQ_HOST | RabbitMQ host | localhost |
| SPRING_RABBITMQ_PORT | RabbitMQ port | 5672 |
| SPRING_RABBITMQ_USERNAME | RabbitMQ username | guest |
| SPRING_RABBITMQ_PASSWORD | RabbitMQ password | guest |
| MIGRATIONS_EXCHANGE | Exchange name | migrations.exchange |

## Message Format

Migrations are dispatched as JSON messages:

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

## Architecture

### Distributed Migrations Flow

```
Dispatcher (CLI)  ──publish──▶  RabbitMQ Exchange
                                      │
                                      ├──worker──▶ Worker Queue ──▶ Worker(s)
                                      │                               │
                                      ├──retry──▶ Retry Queue        │
                                      │           (30s TTL)           │
                                      │              │                │
                                      │              └────────────────┘
                                      │                               │
                                      └──failed──▶ Failed Queue       ▼
                                                                  PostgreSQL
                                                                 (migration_history)
```

### Key Components

1. **Migration Dispatcher**: CLI that publishes migration messages to RabbitMQ
2. **RabbitMQ Exchange**: Routes messages to appropriate queues
3. **Worker Queue**: Main queue for migration processing
4. **Retry Queue**: Temporary queue with TTL for failed migrations
5. **Failed Queue**: Dead letter queue for permanently failed migrations
6. **Migration Worker**: Consumes messages and applies migrations
7. **PostgreSQL**: Stores migration history and state

## Idempotency

Each migration executes **exactly once** through:

1. **Atomic INSERT**: Migration claimed via `INSERT INTO migration_history` with unique constraint
2. **Status Tracking**: Prevents re-execution of completed migrations
3. **Checksum Validation**: Detects payload modifications
4. **Manual ACK**: Messages acknowledged only after database commit

## Retry Behavior

- **Default Attempts**: 3
- **Retry Delay**: 30 seconds (via TTL queue)
- **Max Retries Exceeded**: Message routes to failed queue
- **Retry Counter**: Tracked in message header

## Building

### Build All Modules

```bash
./gradlew build
```

### Build Specific Module

```bash
./gradlew :migration-worker:build
./gradlew :migration-dispatcher:build
./gradlew :migratorx-core:build
```

### Create Standalone JARs

```bash
./gradlew :migration-worker:bootJar
./gradlew :migration-dispatcher:bootJar
```

JARs will be in respective `build/libs/` directories.

## Testing

### Run All Tests

```bash
./gradlew test
```

### Run Module Tests

```bash
./gradlew :migration-worker:test
./gradlew :migratorx-core:test
```

## Monitoring

### RabbitMQ Management UI

Access at: http://localhost:15672 (guest/guest)

- View queue depths
- Monitor message rates
- Check bindings and connections

### Database Queries

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

-- In-progress (potentially stuck)
SELECT migration_id, node_id, started_at,
       AGE(NOW(), started_at) as duration
FROM migration_history 
WHERE status = 'IN_PROGRESS';
```

## Documentation

- [Distributed Migrations Guide](docs/distributed-migrations.md) - Comprehensive guide to the distributed migration framework
- [Migration Worker README](migration-worker/README.md) - Worker configuration and operation
- [Migration Dispatcher README](migration-dispatcher/README.md) - CLI usage and examples

## Deployment

### Production Considerations

1. **Database**: Use connection pooling and SSL
2. **RabbitMQ**: Enable TLS, use dedicated users
3. **Workers**: Deploy multiple instances for high availability
4. **Monitoring**: Set up alerts for failed queue depth
5. **Logging**: Centralize logs for troubleshooting

### Example Production Deployment

```bash
# Build production JAR
./gradlew :migration-worker:bootJar

# Run with production config
java -jar migration-worker/build/libs/migration-worker-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod
```

## Troubleshooting

### Worker Not Consuming Messages

1. Check worker logs for startup errors
2. Verify RabbitMQ connection
3. Ensure queue exists and has proper bindings
4. Check consumer count in RabbitMQ Management UI

### Migrations Stuck in IN_PROGRESS

```sql
-- Find and mark as failed
UPDATE migration_history 
SET status = 'FAILED', 
    error = 'Timeout - manually reset' 
WHERE status = 'IN_PROGRESS' 
  AND started_at < NOW() - INTERVAL '10 minutes';
```

### High Retry Queue Depth

1. Check worker logs for repeated errors
2. Identify failing migration
3. Fix SQL or configuration issue
4. Purge retry queue if necessary
5. Re-dispatch corrected migration

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Support

For issues and questions:
- GitHub Issues: https://github.com/RonnieJakobsgaard/MigratorX/issues
- Documentation: [docs/distributed-migrations.md](docs/distributed-migrations.md)
