package com.example.migratorx.provider.json;


import com.example.migratorx.provider.MigrationTask;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

@RunWith(MockitoJUnitRunner.class)
public class TestJsonMigrationProvider {

    @Test
    public void testGetSingleTask() {
        // Arrange
        JsonMigrationProvider jsonMigrationProvider = new JsonMigrationProvider(Objects.requireNonNull(readJsonFilePathFromResource("/provider/json/single-task.json")));

        // Act
        List<MigrationTask> migrationTasks = jsonMigrationProvider.get();

        // Assert
        Assertions.assertEquals(1, migrationTasks.size());
        Assertions.assertEquals("test-uri", migrationTasks.getFirst().getUri());
        Assertions.assertEquals("username-test", migrationTasks.getFirst().getUsername());
        Assertions.assertEquals("password-test", migrationTasks.getFirst().getPassword());
        Assertions.assertEquals("migrationScriptLocation-test.json", migrationTasks.getFirst().getMigrationScriptLocation());
    }

    @Test
    public void testGetMultipleTasks() {
        // Arrange
        JsonMigrationProvider jsonMigrationProvider = new JsonMigrationProvider(Objects.requireNonNull(readJsonFilePathFromResource("/provider/json/multiple-tasks.json")));

        // Act
        List<MigrationTask> migrationTasks = jsonMigrationProvider.get();

        // Assert
        Assertions.assertEquals(2, migrationTasks.size());

        Assertions.assertEquals("test-uri-1", migrationTasks.getFirst().getUri());
        Assertions.assertEquals("username-test-1", migrationTasks.getFirst().getUsername());
        Assertions.assertEquals("password-test-1", migrationTasks.getFirst().getPassword());
        Assertions.assertEquals("migrationScriptLocation-test-1.json", migrationTasks.getFirst().getMigrationScriptLocation());

        Assertions.assertEquals("test-uri-2", migrationTasks.get(1).getUri());
        Assertions.assertEquals("username-test-2", migrationTasks.get(1).getUsername());
        Assertions.assertEquals("password-test-2", migrationTasks.get(1).getPassword());
        Assertions.assertEquals("migrationScriptLocation-test-2.json", migrationTasks.get(1).getMigrationScriptLocation());
    }

    private File readJsonFilePathFromResource(String resourcePath) {
        try {
            URL resourceUrl = this.getClass().getResource(resourcePath);
            if (resourceUrl == null) {
                throw new RuntimeException("Resource not found: " + resourcePath);
            }
            Path resourceFilePath = Paths.get(resourceUrl.toURI());
            return resourceFilePath.toFile();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Error converting URL to URI: " + resourcePath, e);
        }
    }
}
