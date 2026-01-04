package com.migratorx.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.cli.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Migration Dispatcher Application.
 * Publishes migration messages to RabbitMQ for workers to consume.
 */
@SpringBootApplication
public class DispatcherApplication implements CommandLineRunner {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    @Value("${migrations.exchange:migrations.exchange}")
    private String exchangeName;
    
    @Value("${migrations.routing.key:migrations.request}")
    private String routingKey;
    
    public static void main(String[] args) {
        SpringApplication.run(DispatcherApplication.class, args);
    }
    
    @Override
    public void run(String... args) throws Exception {
        Options options = new Options();
        
        options.addOption(Option.builder("f")
                .longOpt("file")
                .hasArg()
                .desc("SQL file to migrate")
                .build());
        
        options.addOption(Option.builder("m")
                .longOpt("migration-id")
                .hasArg()
                .required()
                .desc("Migration ID (required)")
                .build());
        
        options.addOption(Option.builder("v")
                .longOpt("version")
                .hasArg()
                .required()
                .desc("Migration version (required)")
                .build());
        
        options.addOption(Option.builder("t")
                .longOpt("type")
                .hasArg()
                .desc("Migration type (default: sql)")
                .build());
        
        options.addOption(Option.builder("r")
                .longOpt("requested-by")
                .hasArg()
                .desc("Requested by user")
                .build());
        
        options.addOption(Option.builder("s")
                .longOpt("sql")
                .hasArg()
                .desc("SQL string directly")
                .build());
        
        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("Show help")
                .build());
        
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        
        try {
            CommandLine cmd = parser.parse(options, args);
            
            if (cmd.hasOption("h")) {
                formatter.printHelp("migration-dispatcher", options);
                return;
            }
            
            String migrationId = cmd.getOptionValue("m");
            String version = cmd.getOptionValue("v");
            String type = cmd.getOptionValue("t", "sql");
            String requestedBy = cmd.getOptionValue("r", System.getProperty("user.name"));
            
            String sqlPayload;
            if (cmd.hasOption("f")) {
                String filePath = cmd.getOptionValue("f");
                sqlPayload = readSqlFile(filePath);
            } else if (cmd.hasOption("s")) {
                sqlPayload = cmd.getOptionValue("s");
            } else {
                System.err.println("Error: Either --file or --sql must be provided");
                formatter.printHelp("migration-dispatcher", options);
                System.exit(1);
                return;
            }
            
            // Create migration message
            Map<String, Object> message = new HashMap<>();
            message.put("migrationId", migrationId);
            message.put("version", version);
            message.put("type", type);
            message.put("payload", sqlPayload);
            
            Map<String, String> metadata = new HashMap<>();
            metadata.put("requestedBy", requestedBy);
            message.put("metadata", metadata);
            
            // Publish message
            rabbitTemplate.convertAndSend(exchangeName, routingKey, message);
            
            System.out.println("Migration dispatched successfully:");
            System.out.println("  Migration ID: " + migrationId);
            System.out.println("  Version: " + version);
            System.out.println("  Type: " + type);
            System.out.println("  Exchange: " + exchangeName);
            System.out.println("  Routing Key: " + routingKey);
            
        } catch (ParseException e) {
            System.err.println("Error parsing arguments: " + e.getMessage());
            formatter.printHelp("migration-dispatcher", options);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error dispatching migration: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private String readSqlFile(String filePath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(filePath)));
    }
}
