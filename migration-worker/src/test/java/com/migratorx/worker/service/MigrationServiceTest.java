package com.migratorx.worker.service;

import com.migratorx.worker.dto.MigrationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MigrationServiceTest {
    
    @Mock
    private JdbcTemplate jdbcTemplate;
    
    private MigrationService migrationService;
    
    @BeforeEach
    void setUp() {
        migrationService = new MigrationService(jdbcTemplate);
    }
    
    @Test
    void testClaimAndApplyMigration_Success() {
        // Arrange
        Map<String, String> metadata = new HashMap<>();
        metadata.put("requestedBy", "testuser");
        
        MigrationMessage message = new MigrationMessage(
            "test-migration-1",
            "1.0.0",
            "sql",
            "CREATE TABLE test (id SERIAL PRIMARY KEY);",
            metadata
        );
        
        // Mock: migration doesn't exist yet
        when(jdbcTemplate.queryForObject(
            contains("SELECT COUNT(*)"),
            eq(Integer.class),
            eq("test-migration-1")
        )).thenReturn(0);
        
        // Mock: successful insert (claim)
        when(jdbcTemplate.update(
            contains("INSERT INTO migration_history"),
            eq("test-migration-1"),
            eq("1.0.0"),
            anyString(), // checksum
            anyString(), // node_id
            eq("testuser"),
            any() // timestamp
        )).thenReturn(1);
        
        // Act
        boolean result = migrationService.claimAndApplyMigration(message);
        
        // Assert
        assertTrue(result);
        
        // Verify insert was called (claim)
        verify(jdbcTemplate).update(
            contains("INSERT INTO migration_history"),
            eq("test-migration-1"),
            eq("1.0.0"),
            anyString(), // checksum
            anyString(), // node_id
            eq("testuser"),
            any() // timestamp
        );
        
        // Verify SQL execution
        verify(jdbcTemplate).execute("CREATE TABLE test (id SERIAL PRIMARY KEY);");
        
        // Verify status update to APPLIED
        verify(jdbcTemplate).update(
            contains("UPDATE migration_history SET status = 'APPLIED'"),
            any(),
            eq("test-migration-1")
        );
    }
    
    @Test
    void testClaimAndApplyMigration_AlreadyApplied() {
        // Arrange
        MigrationMessage message = new MigrationMessage(
            "test-migration-2",
            "1.0.0",
            "sql",
            "CREATE TABLE test2 (id SERIAL PRIMARY KEY);",
            new HashMap<>()
        );
        
        // Mock: migration exists with APPLIED status
        when(jdbcTemplate.queryForObject(
            contains("SELECT COUNT(*)"),
            eq(Integer.class),
            eq("test-migration-2")
        )).thenReturn(1);
        
        when(jdbcTemplate.queryForObject(
            contains("SELECT status FROM migration_history"),
            eq(String.class),
            eq("test-migration-2")
        )).thenReturn("APPLIED");
        
        // Act
        boolean result = migrationService.claimAndApplyMigration(message);
        
        // Assert
        assertTrue(result); // Returns true because it's already applied
        
        // Verify no insertion or execution happened
        verify(jdbcTemplate, never()).execute(anyString());
    }
    
    @Test
    void testClaimAndApplyMigration_InProgress() {
        // Arrange
        MigrationMessage message = new MigrationMessage(
            "test-migration-3",
            "1.0.0",
            "sql",
            "CREATE TABLE test3 (id SERIAL PRIMARY KEY);",
            new HashMap<>()
        );
        
        // Mock: migration exists with IN_PROGRESS status
        when(jdbcTemplate.queryForObject(
            contains("SELECT COUNT(*)"),
            eq(Integer.class),
            eq("test-migration-3")
        )).thenReturn(1);
        
        when(jdbcTemplate.queryForObject(
            contains("SELECT status FROM migration_history"),
            eq(String.class),
            eq("test-migration-3")
        )).thenReturn("IN_PROGRESS");
        
        // Act
        boolean result = migrationService.claimAndApplyMigration(message);
        
        // Assert
        assertFalse(result); // Returns false because another worker is processing it
        
        // Verify no insertion or execution happened
        verify(jdbcTemplate, never()).execute(anyString());
    }
    
    @Test
    void testClaimAndApplyMigration_FailedToExecute() {
        // Arrange
        MigrationMessage message = new MigrationMessage(
            "test-migration-4",
            "1.0.0",
            "sql",
            "INVALID SQL STATEMENT;",
            new HashMap<>()
        );
        
        // Mock: migration doesn't exist
        when(jdbcTemplate.queryForObject(
            contains("SELECT COUNT(*)"),
            eq(Integer.class),
            eq("test-migration-4")
        )).thenReturn(0);
        
        // Mock: successful claim
        when(jdbcTemplate.update(
            contains("INSERT INTO migration_history"),
            eq("test-migration-4"),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any()
        )).thenReturn(1);
        
        // Mock: SQL execution fails
        doThrow(new RuntimeException("SQL syntax error"))
            .when(jdbcTemplate).execute("INVALID SQL STATEMENT;");
        
        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            migrationService.claimAndApplyMigration(message);
        });
        
        assertTrue(exception.getMessage().contains("Failed to apply migration"));
        
        // Verify status was updated to FAILED
        verify(jdbcTemplate).update(
            contains("UPDATE migration_history SET status = 'FAILED'"),
            contains("SQL syntax error"),
            eq("test-migration-4")
        );
    }
    
    @Test
    void testClaimAndApplyMigration_FailedToClaim() {
        // Arrange
        MigrationMessage message = new MigrationMessage(
            "test-migration-5",
            "1.0.0",
            "sql",
            "CREATE TABLE test5 (id SERIAL PRIMARY KEY);",
            new HashMap<>()
        );
        
        // Mock: migration doesn't exist
        when(jdbcTemplate.queryForObject(
            contains("SELECT COUNT(*)"),
            eq(Integer.class),
            eq("test-migration-5")
        )).thenReturn(0);
        
        // Mock: claim fails (returns 0 rows inserted)
        when(jdbcTemplate.update(
            contains("INSERT INTO migration_history"),
            eq("test-migration-5"),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any()
        )).thenReturn(0);
        
        // Act
        boolean result = migrationService.claimAndApplyMigration(message);
        
        // Assert
        assertFalse(result); // Failed to claim
        
        // Verify SQL was not executed
        verify(jdbcTemplate, never()).execute(anyString());
    }
}
