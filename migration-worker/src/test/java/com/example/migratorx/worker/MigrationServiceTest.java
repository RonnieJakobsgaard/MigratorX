package com.example.migratorx.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class MigrationServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MigrationService migrationService;

    @BeforeEach
    void setUp() {
        migrationService = new MigrationService(jdbcTemplate);
        // Clean up test data
        jdbcTemplate.execute("DELETE FROM migration_history");
    }

    @Test
    void testClaimMigration_Success() {
        // Given
        Map<String, String> metadata = new HashMap<>();
        metadata.put("requestedBy", "test-user");
        
        MigrationMessage message = new MigrationMessage(
            "test-migration-1",
            "1.0.0",
            "sql",
            "SELECT 1",
            metadata
        );

        // When
        boolean claimed = migrationService.claimMigration(message);

        // Then
        assertTrue(claimed);
        
        // Verify database entry
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM migration_history WHERE migration_id = ?",
            Integer.class,
            "test-migration-1"
        );
        assertEquals(1, count);
    }

    @Test
    void testClaimMigration_AlreadyClaimed() {
        // Given
        Map<String, String> metadata = new HashMap<>();
        metadata.put("requestedBy", "test-user");
        
        MigrationMessage message = new MigrationMessage(
            "test-migration-2",
            "1.0.0",
            "sql",
            "SELECT 1",
            metadata
        );

        // When
        boolean firstClaim = migrationService.claimMigration(message);
        boolean secondClaim = migrationService.claimMigration(message);

        // Then
        assertTrue(firstClaim);
        assertFalse(secondClaim);
    }

    @Test
    void testApplyMigration_Success() {
        // Given
        Map<String, String> metadata = new HashMap<>();
        metadata.put("requestedBy", "test-user");
        
        MigrationMessage message = new MigrationMessage(
            "test-migration-3",
            "1.0.0",
            "sql",
            "CREATE TABLE IF NOT EXISTS test_table (id SERIAL PRIMARY KEY, name TEXT)",
            metadata
        );

        migrationService.claimMigration(message);

        // When
        migrationService.applyMigration(message);

        // Then
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM migration_history WHERE migration_id = ?",
            String.class,
            "test-migration-3"
        );
        assertEquals("APPLIED", status);
        
        // Verify table was created
        Integer tableExists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
            Integer.class,
            "test_table"
        );
        assertTrue(tableExists > 0);
    }

    @Test
    void testMarkMigrationFailed() {
        // Given
        Map<String, String> metadata = new HashMap<>();
        metadata.put("requestedBy", "test-user");
        
        MigrationMessage message = new MigrationMessage(
            "test-migration-4",
            "1.0.0",
            "sql",
            "SELECT 1",
            metadata
        );

        migrationService.claimMigration(message);

        // When
        migrationService.markMigrationFailed("test-migration-4", "Test error message");

        // Then
        Map<String, Object> result = jdbcTemplate.queryForMap(
            "SELECT status, error FROM migration_history WHERE migration_id = ?",
            "test-migration-4"
        );
        
        assertEquals("FAILED", result.get("status"));
        assertEquals("Test error message", result.get("error"));
    }
}
