package com.example.migratorx.provider;

import java.util.List;

public interface MigrationProvider {

    List<MigrationTask> get();
}
