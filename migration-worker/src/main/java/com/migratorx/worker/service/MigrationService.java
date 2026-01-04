package com.migratorx.worker.service;

import com.migratorx.worker.dto.MigrationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Service for claiming and applying migrations.
 * Uses migration_history table for idempotency and tracking.
 */
@Service
public class MigrationService {
    
    private static final Logger logger = LoggerFactory.getLogger(MigrationService.class);
    
    private final JdbcTemplate jdbcTemplate;
    private final String nodeId;
    
    public MigrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.nodeId = getNodeId();
    }
    
    /**
     * Attempts to claim and apply a migration.
     * Returns true if successfully applied, false if already applied or claimed by another worker.
     */
    @Transactional
    public boolean claimAndApplyMigration(MigrationMessage message) {
        String migrationId = message.getMigrationId();
        
        // Check if migration already exists
        Integer existingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM migration_history WHERE migration_id = ?",
            Integer.class,
            migrationId
        );
        
        if (existingCount != null && existingCount > 0) {
            // Migration already exists, check status
            String status = jdbcTemplate.queryForObject(
                "SELECT status FROM migration_history WHERE migration_id = ?",
                String.class,
                migrationId
            );
            
            if ("APPLIED".equals(status)) {
                logger.info("Migration {} already applied, skipping", migrationId);
                return true; // Already applied successfully
            } else if ("IN_PROGRESS".equals(status)) {
                logger.warn("Migration {} is currently in progress by another worker", migrationId);
                return false; // Another worker is handling it
            }
        }
        
        // Try to claim the migration
        try {
            String checksum = calculateChecksum(message.getPayload());
            String requestedBy = message.getMetadata().get("requestedBy");
            
            jdbcTemplate.update(
                "INSERT INTO migration_history (migration_id, version, checksum, status, node_id, requested_by, started_at) " +
                "VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, ?) " +
                "ON CONFLICT (migration_id) DO NOTHING",
                migrationId,
                message.getVersion(),
                checksum,
                nodeId,
                requestedBy,
                Timestamp.from(Instant.now())
            );
            
            // Verify we successfully claimed it
            Integer rowsAffected = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM migration_history WHERE migration_id = ? AND node_id = ? AND status = 'IN_PROGRESS'",
                Integer.class,
                migrationId,
                nodeId
            );
            
            if (rowsAffected == null || rowsAffected == 0) {
                logger.warn("Failed to claim migration {}, another worker claimed it", migrationId);
                return false;
            }
            
            logger.info("Claimed migration {} on node {}", migrationId, nodeId);
            
        } catch (DuplicateKeyException e) {
            logger.warn("Migration {} already claimed by another worker", migrationId);
            return false;
        }
        
        // Apply the migration
        try {
            logger.info("Applying migration {}: {}", migrationId, message.getVersion());
            
            jdbcTemplate.execute(message.getPayload());
            
            // Mark as applied
            jdbcTemplate.update(
                "UPDATE migration_history SET status = 'APPLIED', applied_at = ? WHERE migration_id = ?",
                Timestamp.from(Instant.now()),
                migrationId
            );
            
            logger.info("Successfully applied migration {}", migrationId);
            return true;
            
        } catch (Exception e) {
            logger.error("Error applying migration {}: {}", migrationId, e.getMessage(), e);
            
            // Record error
            jdbcTemplate.update(
                "UPDATE migration_history SET status = 'FAILED', error = ? WHERE migration_id = ?",
                e.getMessage(),
                migrationId
            );
            
            throw new RuntimeException("Failed to apply migration " + migrationId, e);
        }
    }
    
    /**
     * Calculate SHA-256 checksum of the SQL payload.
     */
    private String calculateChecksum(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to calculate checksum", e);
        }
    }
    
    /**
     * Get node identifier (hostname or default).
     */
    private String getNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            logger.warn("Could not determine hostname, using default node ID");
            return "worker-" + System.currentTimeMillis();
        }
    }
}
