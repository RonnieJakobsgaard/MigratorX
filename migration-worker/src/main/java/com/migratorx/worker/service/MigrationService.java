package com.migratorx.worker.service;

import com.migratorx.worker.dto.MigrationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Service responsible for claiming migrations, executing SQL, and updating migration history
 */
@Service
public class MigrationService {

    private static final Logger logger = LoggerFactory.getLogger(MigrationService.class);

    private final JdbcTemplate jdbcTemplate;
    
    @Value("${spring.application.name:migration-worker}")
    private String nodeId;

    public MigrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Attempt to claim a migration by inserting into migration_history with IN_PROGRESS status
     * Returns true if claimed successfully, false if already applied
     * @throws RuntimeException if migration is already in progress or failed to claim
     */
    public boolean claimMigration(MigrationMessage message) {
        String migrationId = message.getMigrationId();
        String version = message.getVersion();
        String checksum = calculateChecksum(message.getPayload());
        String requestedBy = message.getMetadata() != null ? 
                message.getMetadata().get("requestedBy") : null;

        try {
            // Try to insert with IN_PROGRESS status - unique constraint ensures atomicity
            int rowsInserted = jdbcTemplate.update(
                "INSERT INTO migration_history " +
                "(migration_id, version, checksum, status, node_id, requested_by, started_at) " +
                "VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, ?)",
                migrationId, version, checksum, nodeId, requestedBy, OffsetDateTime.now()
            );
            
            if (rowsInserted == 1) {
                logger.info("Successfully claimed migration: {}", migrationId);
                return true;
            }
            
            return false;
        } catch (DuplicateKeyException e) {
            // Migration already exists, check its status
            String status = jdbcTemplate.queryForObject(
                "SELECT status FROM migration_history WHERE migration_id = ?",
                String.class,
                migrationId
            );
            
            if ("APPLIED".equals(status)) {
                logger.info("Migration {} already applied, skipping", migrationId);
                return false;
            } else {
                throw new RuntimeException("Migration " + migrationId + 
                    " is already in progress or failed: " + status);
            }
        }
    }

    /**
     * Execute the migration SQL payload within a transaction
     */
    @Transactional
    public void executeMigration(MigrationMessage message) {
        String migrationId = message.getMigrationId();
        String payload = message.getPayload();
        
        try {
            logger.info("Executing migration: {}", migrationId);
            
            // Execute the SQL payload
            jdbcTemplate.execute(payload);
            
            // Mark as applied
            int updated = jdbcTemplate.update(
                "UPDATE migration_history SET status = 'APPLIED', applied_at = ? " +
                "WHERE migration_id = ? AND status = 'IN_PROGRESS'",
                OffsetDateTime.now(), migrationId
            );
            
            if (updated == 1) {
                logger.info("Successfully applied migration: {}", migrationId);
            } else {
                throw new RuntimeException("Failed to update migration status for: " + migrationId);
            }
            
        } catch (Exception e) {
            logger.error("Failed to execute migration: {}", migrationId, e);
            
            // Mark as failed
            jdbcTemplate.update(
                "UPDATE migration_history SET status = 'FAILED', error = ? " +
                "WHERE migration_id = ? AND status = 'IN_PROGRESS'",
                e.getMessage(), migrationId
            );
            
            throw new RuntimeException("Migration execution failed: " + migrationId, e);
        }
    }

    /**
     * Calculate SHA-256 checksum of the payload
     */
    private String calculateChecksum(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to calculate checksum", e);
        }
    }
}
