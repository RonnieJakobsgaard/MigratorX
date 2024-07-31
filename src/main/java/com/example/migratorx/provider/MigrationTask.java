package com.example.migratorx.provider;

public class MigrationTask {

    private String uri;
    private String username;
    private String password;
    private String migrationScriptLocation;

    public MigrationTask() {
    }

    public MigrationTask(String uri, String username, String password, String migrationScriptLocation) {
        this.uri = uri;
        this.username = username;
        this.password = password;
        this.migrationScriptLocation = migrationScriptLocation;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getMigrationScriptLocation() {
        return migrationScriptLocation;
    }

    public void setMigrationScriptLocation(String migrationScriptLocation) {
        this.migrationScriptLocation = migrationScriptLocation;
    }
}
