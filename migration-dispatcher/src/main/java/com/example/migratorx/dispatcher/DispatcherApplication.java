package com.example.migratorx.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Migration Dispatcher CLI
 * 
 * Publishes migration messages to RabbitMQ for worker consumption
 */
@SpringBootApplication
public class DispatcherApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DispatcherApplication.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${migrations.exchange:migrations.exchange}")
    private String exchangeName;

    public DispatcherApplication(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    public static void main(String[] args) {
        SpringApplication.run(DispatcherApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        Options options = new Options();
        
        options.addOption("m", "migration-id", true, "Migration ID (required)");
        options.addOption("v", "version", true, "Version (required)");
        options.addOption("f", "file", true, "SQL file path (required)");
        options.addOption("s", "sql", true, "SQL string (alternative to -f)");
        options.addOption("r", "requested-by", true, "Requested by (optional)");
        options.addOption("h", "help", false, "Show help");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h") || args.length == 0) {
                formatter.printHelp("migration-dispatcher", options);
                return;
            }

            // Validate required options
            if (!cmd.hasOption("m") || !cmd.hasOption("v")) {
                log.error("Missing required options: -m (migration-id) and -v (version)");
                formatter.printHelp("migration-dispatcher", options);
                System.exit(1);
            }

            if (!cmd.hasOption("f") && !cmd.hasOption("s")) {
                log.error("Either -f (file) or -s (sql) must be provided");
                formatter.printHelp("migration-dispatcher", options);
                System.exit(1);
            }

            String migrationId = cmd.getOptionValue("m");
            String version = cmd.getOptionValue("v");
            String requestedBy = cmd.getOptionValue("r", "cli-user");

            // Read SQL payload
            String sqlPayload;
            if (cmd.hasOption("f")) {
                String filePath = cmd.getOptionValue("f");
                sqlPayload = Files.readString(Paths.get(filePath));
                log.info("Read SQL from file: {}", filePath);
            } else {
                sqlPayload = cmd.getOptionValue("s");
            }

            // Create message
            Map<String, Object> message = new HashMap<>();
            message.put("migrationId", migrationId);
            message.put("version", version);
            message.put("type", "sql");
            message.put("payload", sqlPayload);

            Map<String, String> metadata = new HashMap<>();
            metadata.put("requestedBy", requestedBy);
            message.put("metadata", metadata);

            // Publish to RabbitMQ
            rabbitTemplate.convertAndSend(exchangeName, "worker", message);

            String messageJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(message);
            log.info("Successfully dispatched migration message:\n{}", messageJson);
            
            log.info("Migration message sent to exchange: {}, routing key: worker", exchangeName);

        } catch (ParseException e) {
            log.error("Failed to parse command line arguments", e);
            formatter.printHelp("migration-dispatcher", options);
            System.exit(1);
        } catch (Exception e) {
            log.error("Failed to dispatch migration", e);
            System.exit(1);
        }
    }
}
