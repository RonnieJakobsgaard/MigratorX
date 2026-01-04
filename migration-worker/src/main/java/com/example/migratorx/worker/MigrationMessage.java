package com.example.migratorx.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Data Transfer Object for migration messages
 */
public class MigrationMessage {
    
    @JsonProperty("migrationId")
    private String migrationId;
    
    @JsonProperty("version")
    private String version;
    
    @JsonProperty("type")
    private String type;
    
    @JsonProperty("payload")
    private String payload;
    
    @JsonProperty("metadata")
    private Map<String, String> metadata;

    // Default constructor for Jackson
    public MigrationMessage() {
    }

    public MigrationMessage(String migrationId, String version, String type, String payload, Map<String, String> metadata) {
        this.migrationId = migrationId;
        this.version = version;
        this.type = type;
        this.payload = payload;
        this.metadata = metadata;
    }

    public String getMigrationId() {
        return migrationId;
    }

    public void setMigrationId(String migrationId) {
        this.migrationId = migrationId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    @Override
    public String toString() {
        return "MigrationMessage{" +
                "migrationId='" + migrationId + '\'' +
                ", version='" + version + '\'' +
                ", type='" + type + '\'' +
                ", metadata=" + metadata +
                '}';
    }
}
