package com.example.migratorx.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Migration Worker Application
 * 
 * Consumes migration messages from RabbitMQ and applies them to PostgreSQL database.
 * Uses atomic INSERT to claim migrations and prevent duplicate execution.
 */
@SpringBootApplication
public class MigrationWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigrationWorkerApplication.class, args);
    }
}
