package com.example.migratorx.migrator;

import com.example.migratorx.TestMigratorXApplication;
import com.example.migratorx.flyway.MigratorXFlyway;
import com.example.migratorx.provider.MigrationProvider;
import com.example.migratorx.provider.MigrationTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Objects;

@SpringBootTest
@Import(TestMigratorXApplication.class)
@TestConfiguration(proxyBeanMethods = false)
public class TestMigrator {

    @Autowired
    PostgreSQLContainer<?> postgresContainer;

    FakeMigrationProvider fakeMigrationProvider = new FakeMigrationProvider(postgresContainer);

    @Test
    public void testMigrate() {
        // Arrange
        List<MigrationTask> migrationTasks = fakeMigrationProvider.get();

        // Act
        migrationTasks.forEach(migrationTask -> {
            MigratorXFlyway migratorXFlyway = new MigratorXFlyway(migrationTask);
            migratorXFlyway.migrate();
        });

        // Assert
        // No exceptions thrown
    }

    @Component
    public static class FakeMigrationProvider implements MigrationProvider {

        private final PostgreSQLContainer<?> postgresContainer;

        public FakeMigrationProvider(PostgreSQLContainer<?> postgresContainer) {
            this.postgresContainer = postgresContainer;
        }

        @Override
        public List<MigrationTask> get() {
            return List.of(new MigrationTask(postgresContainer.getJdbcUrl() , postgresContainer.getUsername(), postgresContainer.getPassword(), Objects.requireNonNull(this.getClass().getResource("/migrator/scripts")).getPath()));
        }
    }

}
