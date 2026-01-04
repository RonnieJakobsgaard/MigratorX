package com.example.migratorx.migrator.centralized;

import com.example.migratorx.flyway.MigratorXFlyway;
import com.example.migratorx.migrator.Migrator;
import com.example.migratorx.migrator.centralized.config.CentralizedSchemaMigratorProperties;
import com.example.migratorx.provider.MigrationProvider;
import com.example.migratorx.provider.MigrationTask;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CentralizedSchemaMigrator implements Migrator {

    private final MigrationProvider migrationProvider;
    private final ExecutorService executorService;

    public CentralizedSchemaMigrator(MigrationProvider migrationProvider, CentralizedSchemaMigratorProperties properties) {
        this.migrationProvider = migrationProvider;
        this.executorService = Executors.newFixedThreadPool(properties.getWorkers());
    }

    @Override
    public void migrate() {
        migrationProvider.get().forEach(migrationTask -> executorService.submit(() -> doMigrate(migrationTask)));
    }

    private void doMigrate(MigrationTask migrationTask) {
        MigratorXFlyway migratorXFlyway = new MigratorXFlyway(migrationTask);
        migratorXFlyway.migrate();
    }
}
