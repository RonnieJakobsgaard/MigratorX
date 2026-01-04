package com.example.migratorx.provider.direct;

import com.example.migratorx.provider.MigrationTask;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(org.mockito.junit.MockitoJUnitRunner.class)
public class TestDirectMigrationProvider {

    @Test
    public void testGet_ReturnListWithOneObject() {
        // Arrange
        DirectMigrationProvider directMigrationProvider = new DirectMigrationProvider("jdbc:h2:mem:test", "sa", "", "classpath:db/migration");

        // Act
        List<MigrationTask> migrationTasks = directMigrationProvider.get();

        // Assert
        Assertions.assertEquals(1, migrationTasks.size());
        Assertions.assertEquals("jdbc:h2:mem:test", migrationTasks.get(0).getUri());
        Assertions.assertEquals("sa", migrationTasks.get(0).getUsername());
        Assertions.assertEquals("", migrationTasks.get(0).getPassword());
        Assertions.assertEquals("classpath:db/migration", migrationTasks.get(0).getMigrationScriptLocation());
    }
}
