package com.migratorx.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class DispatcherApplication {

    private static final Logger logger = LoggerFactory.getLogger(DispatcherApplication.class);

    @Value("${migrations.exchange:migrations.exchange}")
    private String migrationsExchange;

    @Value("${migrations.queue:migrations.worker.queue}")
    private String migrationsQueue;

    public static void main(String[] args) {
        SpringApplication.run(DispatcherApplication.class, args);
    }

    @Bean
    public CommandLineRunner run(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        return args -> {
            if (args.length == 0) {
                logger.error("Usage: java -jar migration-dispatcher.jar <sql-file-path> [migration-id] [version] [requested-by]");
                System.exit(1);
            }

            String sqlFilePath = args[0];
            String migrationId = args.length > 1 ? args[1] : generateMigrationId(sqlFilePath);
            String version = args.length > 2 ? args[2] : extractVersion(sqlFilePath);
            String requestedBy = args.length > 3 ? args[3] : System.getProperty("user.name");

            logger.info("Dispatching migration from file: {}", sqlFilePath);
            logger.info("Migration ID: {}", migrationId);
            logger.info("Version: {}", version);

            // Read SQL file
            Path path = Paths.get(sqlFilePath);
            if (!Files.exists(path)) {
                logger.error("SQL file not found: {}", sqlFilePath);
                System.exit(1);
            }

            String sqlContent = Files.readString(path);
            
            // Build migration message
            Map<String, Object> message = new HashMap<>();
            message.put("migrationId", migrationId);
            message.put("version", version);
            message.put("type", "SQL");
            message.put("payload", sqlContent);
            
            Map<String, String> metadata = new HashMap<>();
            metadata.put("requestedBy", requestedBy);
            metadata.put("sourceFile", sqlFilePath);
            metadata.put("dispatchedAt", java.time.Instant.now().toString());
            message.put("metadata", metadata);

            // Convert to JSON and publish
            String messageJson = objectMapper.writeValueAsString(message);
            logger.info("Publishing migration message to exchange: {}, routing key: {}", 
                migrationsExchange, migrationsQueue);
            
            rabbitTemplate.convertAndSend(migrationsExchange, migrationsQueue, message);
            
            logger.info("Migration message dispatched successfully!");
            logger.info("Message: {}", messageJson);
            
            System.exit(0);
        };
    }

    private String generateMigrationId(String filePath) {
        // Extract filename without extension
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private String extractVersion(String filePath) {
        // Try to extract version from filename (e.g., V1.0, 20260104, etc.)
        String fileName = Paths.get(filePath).getFileName().toString();
        
        // Pattern: YYYYMMDD-description.sql
        if (fileName.matches("\\d{8}-.*")) {
            return fileName.substring(0, 8);
        }
        
        // Pattern: V1.0__description.sql
        if (fileName.startsWith("V") && fileName.contains("__")) {
            int endIndex = fileName.indexOf("__");
            return fileName.substring(1, endIndex);
        }
        
        // Default to filename without extension
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }
}
