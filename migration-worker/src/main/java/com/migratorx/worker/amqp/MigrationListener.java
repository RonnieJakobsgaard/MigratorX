package com.migratorx.worker.amqp;

import com.migratorx.worker.dto.MigrationMessage;
import com.migratorx.worker.service.MigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AMQP listener for migration messages with retry logic
 */
@Component
public class MigrationListener {

    private static final Logger logger = LoggerFactory.getLogger(MigrationListener.class);
    private static final String RETRY_COUNT_HEADER = "x-retry-count";

    private final MigrationService migrationService;
    private final RabbitTemplate rabbitTemplate;

    @Value("${migrations.exchange:migrations.exchange}")
    private String migrationsExchange;

    @Value("${migrations.retry.queue:migrations.retry.queue}")
    private String migrationsRetryQueue;

    @Value("${migrations.failed.queue:migrations.failed.queue}")
    private String migrationsFailedQueue;

    @Value("${migration.retry.attempts:3}")
    private int maxRetryAttempts;

    public MigrationListener(MigrationService migrationService, RabbitTemplate rabbitTemplate) {
        this.migrationService = migrationService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = "${migrations.queue:migrations.worker.queue}")
    public void handleMigrationMessage(MigrationMessage migrationMessage, Message message) {
        logger.info("Received migration message: {}", migrationMessage);

        try {
            // Attempt to claim the migration
            boolean claimed = migrationService.claimMigration(migrationMessage);
            
            if (!claimed) {
                // Migration already applied, acknowledge and skip
                logger.info("Migration {} already applied, skipping", migrationMessage.getMigrationId());
                return;
            }

            // Execute the migration
            migrationService.executeMigration(migrationMessage);
            logger.info("Successfully processed migration: {}", migrationMessage.getMigrationId());

        } catch (Exception e) {
            logger.error("Error processing migration: {}", migrationMessage.getMigrationId(), e);
            handleFailure(migrationMessage, message, e);
            throw e; // Re-throw to trigger message requeue/rejection
        }
    }

    /**
     * Handle migration failure with retry logic
     */
    private void handleFailure(MigrationMessage migrationMessage, Message message, Exception error) {
        Integer retryCount = (Integer) message.getMessageProperties().getHeaders().get(RETRY_COUNT_HEADER);
        if (retryCount == null) {
            retryCount = 0;
        }

        retryCount++;
        logger.info("Migration {} failed, retry count: {}/{}", 
            migrationMessage.getMigrationId(), retryCount, maxRetryAttempts);

        if (retryCount < maxRetryAttempts) {
            // Republish to retry queue with incremented retry count
            Message retryMessage = org.springframework.amqp.core.MessageBuilder
                .fromMessage(message)
                .setHeader(RETRY_COUNT_HEADER, retryCount)
                .build();
            
            rabbitTemplate.send(migrationsExchange, migrationsRetryQueue, retryMessage);
            logger.info("Republished migration {} to retry queue (attempt {}/{})", 
                migrationMessage.getMigrationId(), retryCount, maxRetryAttempts);
        } else {
            // Max retries exceeded, move to failed queue
            Message failedMessage = org.springframework.amqp.core.MessageBuilder
                .fromMessage(message)
                .setHeader(RETRY_COUNT_HEADER, retryCount)
                .setHeader("x-error", error.getMessage())
                .build();
            
            rabbitTemplate.send(migrationsExchange, migrationsFailedQueue, failedMessage);
            logger.error("Migration {} moved to failed queue after {} attempts", 
                migrationMessage.getMigrationId(), retryCount);
        }
    }
}
