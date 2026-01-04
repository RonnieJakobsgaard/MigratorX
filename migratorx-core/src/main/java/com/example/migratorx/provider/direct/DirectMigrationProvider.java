package com.example.migratorx.provider.direct;

import com.example.migratorx.provider.MigrationProvider;
import com.example.migratorx.provider.MigrationTask;

import java.util.List;

public class DirectMigrationProvider implements MigrationProvider {

    private final String uri;
    private final String username;
    private final String password;
    private final String migrationScriptLocation;

    public DirectMigrationProvider(String uri, String username, String password, String migrationScriptLocation) {
        this.uri = uri;
        this.username = username;
        this.password = password;
        this.migrationScriptLocation = migrationScriptLocation;
    }

    @Override
    public List<MigrationTask> get() {
        return List.of(new MigrationTask(uri, username, password, migrationScriptLocation));
    }
}
