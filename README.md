# MigratorX

A distributed database migration framework for PostgreSQL with RabbitMQ-based message dispatch and worker processing.

## Overview

MigratorX provides both centralized and distributed approaches to database schema migrations:

1. **Centralized Migration** (existing): Direct Flyway-based migrations
2. **Distributed Migration** (new): RabbitMQ-dispatched migrations with worker processing

## Quick Start with Distributed Migrations

### Prerequisites

- Java 17+
- Docker and Docker Compose

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts PostgreSQL and RabbitMQ.

### 2. Start the Migration Worker

```bash
./gradlew :migration-worker:bootRun
```

### 3. Dispatch a Migration

```bash
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-example -v 1.0.0 -s 'CREATE TABLE example (id SERIAL PRIMARY KEY);' -r YourName"
```

### 4. Verify

- Check worker logs for successful application
- Access RabbitMQ Management UI: http://localhost:15672 (migratorx/migratorx123)
- Query PostgreSQL: `psql -h localhost -U migratorx -d migratorx`

## Project Structure

```
MigratorX/
├── src/                          # Original centralized migrator
├── migration-worker/             # Distributed worker microservice
├── migration-dispatcher/         # CLI for dispatching migrations
├── docs/                         # Documentation
│   └── distributed-migrations.md # Comprehensive guide
├── docker-compose.yml            # Local development infrastructure
└── README.md                     # This file
```

## Modules

### Migration Worker

Spring Boot microservice that:
- Consumes migration messages from RabbitMQ
- Claims migrations using database locking
- Applies SQL migrations transactionally
- Handles retries and failures

[Read more](migration-worker/README.md)

### Migration Dispatcher

Command-line tool that:
- Reads SQL from files or CLI arguments
- Publishes migration messages to RabbitMQ

[Read more](migration-dispatcher/README.md)

## Documentation

- **[Distributed Migrations Guide](docs/distributed-migrations.md)**: Complete architecture, message format, configuration, and troubleshooting

## Building

Build all modules:

```bash
./gradlew build
```

Build specific module:

```bash
./gradlew :migration-worker:build
./gradlew :migration-dispatcher:build
```

## Running Tests

```bash
./gradlew test
```

## Key Features

### Distributed Migration Framework

- ✅ **Scalable**: Run multiple workers for parallel processing
- ✅ **Reliable**: Built-in retry logic with configurable attempts
- ✅ **Idempotent**: Migrations applied exactly once using database locks
- ✅ **Observable**: Track status in `migration_history` table
- ✅ **Fault-tolerant**: Dead-letter queue for failed migrations
- ✅ **Message-driven**: RabbitMQ for asynchronous dispatch

### Technology Stack

- **Java 17**
- **Spring Boot 3.2.5**
- **Spring AMQP** (RabbitMQ integration)
- **Spring JDBC** (Database operations)
- **PostgreSQL 16**
- **RabbitMQ 3.13**

## Configuration

Key environment variables:

### RabbitMQ
- `SPRING_RABBITMQ_HOST` (default: localhost)
- `SPRING_RABBITMQ_USERNAME` (default: migratorx)
- `SPRING_RABBITMQ_PASSWORD` (default: migratorx123)

### PostgreSQL
- `SPRING_DATASOURCE_URL` (default: jdbc:postgresql://localhost:5432/migratorx)
- `SPRING_DATASOURCE_USERNAME` (default: migratorx)
- `SPRING_DATASOURCE_PASSWORD` (default: migratorx123)

See [distributed-migrations.md](docs/distributed-migrations.md) for complete configuration options.

## Usage Examples

### Example 1: Create a Table

```bash
# Create SQL file
cat > create_users.sql << 'EOF'
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    email VARCHAR(200) UNIQUE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);
EOF

# Dispatch
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-create-users -v 1.0.0 -f create_users.sql"
```

### Example 2: Add an Index

```bash
./gradlew :migration-dispatcher:bootRun --args="-m 20260104-add-index -v 1.0.1 -s 'CREATE INDEX idx_users_email ON users(email);'"
```

### Example 3: Run Multiple Workers

```bash
# Terminal 1
./gradlew :migration-worker:bootRun

# Terminal 2
./gradlew :migration-worker:bootRun

# Terminal 3
./gradlew :migration-worker:bootRun
```

## Monitoring

### RabbitMQ Management

Access at http://localhost:15672 to monitor:
- Queue depths
- Message rates
- Consumer status
- Failed migrations

### Database Queries

```sql
-- View all migrations
SELECT * FROM migration_history ORDER BY started_at DESC;

-- Check failed migrations
SELECT migration_id, error FROM migration_history WHERE status = 'FAILED';

-- Monitor in-progress
SELECT * FROM migration_history WHERE status = 'IN_PROGRESS';
```

### Worker Health

```bash
curl http://localhost:8080/actuator/health
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

See [LICENSE](LICENSE) file.

## Support

For issues and questions:
- Open an issue on GitHub
- Check the [troubleshooting guide](docs/distributed-migrations.md#troubleshooting)
- Review module-specific READMEs

## Roadmap

- [ ] Web UI for migration management
- [ ] Support for rollback migrations
- [ ] Integration with Flyway versioning
- [ ] Multi-database support (MySQL, Oracle, etc.)
- [ ] Kubernetes deployment examples
- [ ] Terraform infrastructure as code
