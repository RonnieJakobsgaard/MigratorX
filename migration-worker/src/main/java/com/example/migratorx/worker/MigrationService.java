package com.example.migratorx.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;

/**
 * Migration Service
 * 
 * Handles claiming and applying migrations using atomic INSERT strategy
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);
    private final JdbcTemplate jdbcTemplate;
    private final String nodeId;

    public MigrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.nodeId = getNodeId();
        ensureMigrationHistoryTable();
    }

    private String getNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-node-" + System.currentTimeMillis();
        }
    }

    private void ensureMigrationHistoryTable() {
        String createTableSql = """
            CREATE TABLE IF NOT EXISTS migration_history (
              id BIGSERIAL PRIMARY KEY,
              migration_id TEXT NOT NULL UNIQUE,
              version TEXT,
              checksum TEXT,
              status TEXT NOT NULL,
              node_id TEXT,
              requested_by TEXT,
              started_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
              applied_at TIMESTAMP WITH TIME ZONE,
              error TEXT
            )
            """;
        
        try {
            jdbcTemplate.execute(createTableSql);
            log.info("Migration history table ensured");
        } catch (Exception e) {
            log.error("Failed to create migration_history table", e);
            throw new RuntimeException("Failed to initialize migration_history table", e);
        }
    }

    /**
     * Claims a migration by atomically inserting into migration_history
     * 
     * @return true if successfully claimed, false if already claimed/applied
     */
    @Transactional
    public boolean claimMigration(MigrationMessage message) {
        String requestedBy = message.getMetadata() != null ? 
            message.getMetadata().get("requestedBy") : null;

        String insertSql = """
            INSERT INTO migration_history 
            (migration_id, version, status, node_id, requested_by, started_at)
            VALUES (?, ?, 'IN_PROGRESS', ?, ?, ?)
            """;

        try {
            int rows = jdbcTemplate.update(
                insertSql,
                message.getMigrationId(),
                message.getVersion(),
                nodeId,
                requestedBy,
                Timestamp.from(Instant.now())
            );
            
            log.info("Claimed migration: {} by node: {}", message.getMigrationId(), nodeId);
            return rows > 0;
        } catch (DuplicateKeyException e) {
            log.info("Migration {} already claimed or applied, skipping", message.getMigrationId());
            return false;
        }
    }

    /**
     * Applies the migration SQL and updates status to APPLIED
     */
    @Transactional
    public void applyMigration(MigrationMessage message) {
        try {
            // Execute the SQL payload
            jdbcTemplate.execute(message.getPayload());
            
            // Calculate checksum
            String checksum = calculateChecksum(message.getPayload());
            
            // Update migration_history to APPLIED
            String updateSql = """
                UPDATE migration_history
                SET status = 'APPLIED',
                    checksum = ?,
                    applied_at = ?
                WHERE migration_id = ?
                """;
            
            jdbcTemplate.update(
                updateSql,
                checksum,
                Timestamp.from(Instant.now()),
                message.getMigrationId()
            );
            
            log.info("Successfully applied migration: {}", message.getMigrationId());
        } catch (Exception e) {
            log.error("Failed to apply migration: {}", message.getMigrationId(), e);
            markMigrationFailed(message.getMigrationId(), e.getMessage());
            throw new RuntimeException("Failed to apply migration: " + message.getMigrationId(), e);
        }
    }

    /**
     * Marks a migration as failed in the database
     */
    @Transactional
    public void markMigrationFailed(String migrationId, String errorMessage) {
        String updateSql = """
            UPDATE migration_history
            SET status = 'FAILED',
                error = ?
            WHERE migration_id = ?
            """;
        
        jdbcTemplate.update(updateSql, errorMessage, migrationId);
        log.error("Marked migration as FAILED: {}", migrationId);
    }

    /**
     * Calculates SHA-256 checksum of the SQL payload
     */
    private String calculateChecksum(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to calculate checksum", e);
            return "unknown";
        }
    }
}
