package com.example.migratorx.flyway;

import com.example.migratorx.provider.MigrationTask;
import org.flywaydb.core.Flyway;

public class MigratorXFlyway {

    private final Flyway flyway;

    public MigratorXFlyway(MigrationTask migrationTask) {
        flyway = Flyway.configure()
                .dataSource(migrationTask.getUri(), migrationTask.getUsername(), migrationTask.getPassword())
                .locations(migrationTask.getMigrationScriptLocation())
                .load();
    }

    public void info() {
        flyway.info();
    }

    public void migrate() {
        repair();
        flyway.migrate();
    }

    public void repair() {
        flyway.repair();
    }
}
