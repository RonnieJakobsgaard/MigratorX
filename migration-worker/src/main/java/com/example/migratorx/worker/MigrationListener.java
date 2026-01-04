package com.example.migratorx.worker;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Migration Listener
 * 
 * Listens to RabbitMQ queue and processes migration messages
 * Implements manual ack/nack with retry logic
 */
@Component
public class MigrationListener {

    private static final Logger log = LoggerFactory.getLogger(MigrationListener.class);
    private static final String X_RETRIES_HEADER = "x-retries";

    private final MigrationService migrationService;
    private final RabbitTemplate rabbitTemplate;

    @Value("${migration.retry.attempts:3}")
    private int maxRetries;

    @Value("${migrations.exchange:migrations.exchange}")
    private String exchangeName;

    public MigrationListener(MigrationService migrationService, RabbitTemplate rabbitTemplate) {
        this.migrationService = migrationService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = "${migrations.queue:migrations.worker.queue}", ackMode = "MANUAL")
    public void handleMigrationMessage(
            @Payload MigrationMessage message,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(value = X_RETRIES_HEADER, required = false) Integer retries,
            Message amqpMessage,
            Channel channel) {
        
        log.info("Received migration message: {}", message);
        
        int currentRetries = (retries != null) ? retries : 0;
        
        try {
            // Try to claim the migration
            boolean claimed = migrationService.claimMigration(message);
            
            if (!claimed) {
                // Migration already processed, acknowledge and skip
                log.info("Migration {} already processed, acknowledging", message.getMigrationId());
                channel.basicAck(deliveryTag, false);
                return;
            }
            
            // Apply the migration
            migrationService.applyMigration(message);
            
            // Acknowledge success
            channel.basicAck(deliveryTag, false);
            log.info("Successfully processed migration: {}", message.getMigrationId());
            
        } catch (Exception e) {
            log.error("Error processing migration: {}", message.getMigrationId(), e);
            
            try {
                if (currentRetries < maxRetries) {
                    // Send to retry queue with incremented retry count
                    retryMessage(message, currentRetries + 1);
                    channel.basicAck(deliveryTag, false); // Ack original message
                    log.info("Sent migration {} to retry queue (attempt {}/{})", 
                        message.getMigrationId(), currentRetries + 1, maxRetries);
                } else {
                    // Max retries exceeded, send to failed queue
                    log.error("Max retries exceeded for migration: {}, sending to failed queue", 
                        message.getMigrationId());
                    channel.basicNack(deliveryTag, false, false);
                }
            } catch (IOException ioException) {
                log.error("Failed to handle message acknowledgment", ioException);
            }
        }
    }

    private void retryMessage(MigrationMessage message, int retryCount) {
        rabbitTemplate.convertAndSend(
            exchangeName,
            "retry",
            message,
            msg -> {
                msg.getMessageProperties().setHeader(X_RETRIES_HEADER, retryCount);
                return msg;
            }
        );
    }
}
