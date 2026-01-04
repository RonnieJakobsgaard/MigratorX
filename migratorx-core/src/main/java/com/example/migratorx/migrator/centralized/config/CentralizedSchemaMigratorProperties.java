package com.example.migratorx.migrator.centralized.config;

public class CentralizedSchemaMigratorProperties {

    private int workers;

    public CentralizedSchemaMigratorProperties(int workers) {
        this.workers = workers;
    }

    public int getWorkers() {
        return workers;
    }

    public void setWorkers(int workers) {
        this.workers = workers;
    }
}
