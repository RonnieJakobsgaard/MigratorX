package com.migratorx.worker.amqp;

import com.migratorx.worker.dto.MigrationMessage;
import com.migratorx.worker.service.MigrationService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * RabbitMQ listener for migration messages.
 * Receives messages, delegates to MigrationService, and handles ack/nack with retry logic.
 */
@Component
public class MigrationListener {
    
    private static final Logger logger = LoggerFactory.getLogger(MigrationListener.class);
    private static final String X_DEATH_HEADER = "x-death";
    
    private final MigrationService migrationService;
    private final RabbitTemplate rabbitTemplate;
    
    @Value("${migration.retry.attempts:3}")
    private int maxRetryAttempts;
    
    @Value("${migrations.exchange:migrations.exchange}")
    private String exchangeName;
    
    @Value("${migrations.retry.queue:migrations.retry.queue}")
    private String retryQueueName;
    
    @Value("${migrations.routing.key:migrations.request}")
    private String routingKey;
    
    @Value("${migrations.failed.routing.key:migrations.failed}")
    private String failedRoutingKey;
    
    public MigrationListener(MigrationService migrationService, RabbitTemplate rabbitTemplate) {
        this.migrationService = migrationService;
        this.rabbitTemplate = rabbitTemplate;
    }
    
    @RabbitListener(queues = "${migrations.queue:migrations.worker.queue}")
    public void handleMigrationMessage(MigrationMessage message, Message amqpMessage, Channel channel) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        
        logger.info("Received migration message: {}", message);
        
        try {
            boolean success = migrationService.claimAndApplyMigration(message);
            
            if (success) {
                // Acknowledge the message
                channel.basicAck(deliveryTag, false);
                logger.info("Migration {} completed successfully", message.getMigrationId());
            } else {
                // Migration was already claimed or applied
                channel.basicAck(deliveryTag, false);
                logger.info("Migration {} was already handled", message.getMigrationId());
            }
            
        } catch (Exception e) {
            logger.error("Error processing migration {}: {}", message.getMigrationId(), e.getMessage(), e);
            
            // Check retry count
            int retryCount = getRetryCount(amqpMessage);
            
            if (retryCount < maxRetryAttempts) {
                logger.info("Retrying migration {} (attempt {}/{})", 
                    message.getMigrationId(), retryCount + 1, maxRetryAttempts);
                
                // Send to retry queue
                channel.basicNack(deliveryTag, false, false);
                rabbitTemplate.convertAndSend(exchangeName, routingKey, message);
                
            } else {
                logger.error("Migration {} exceeded max retry attempts, sending to failed queue", 
                    message.getMigrationId());
                
                // Send to failed queue
                channel.basicNack(deliveryTag, false, false);
                rabbitTemplate.convertAndSend(exchangeName, failedRoutingKey, message);
            }
        }
    }
    
    /**
     * Extract retry count from x-death header.
     */
    private int getRetryCount(Message message) {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        
        if (headers != null && headers.containsKey(X_DEATH_HEADER)) {
            Object xDeath = headers.get(X_DEATH_HEADER);
            
            if (xDeath instanceof List) {
                List<?> deaths = (List<?>) xDeath;
                if (!deaths.isEmpty() && deaths.get(0) instanceof Map) {
                    Map<?, ?> deathInfo = (Map<?, ?>) deaths.get(0);
                    Object count = deathInfo.get("count");
                    if (count instanceof Long) {
                        return ((Long) count).intValue();
                    } else if (count instanceof Integer) {
                        return (Integer) count;
                    }
                }
            }
        }
        
        return 0;
    }
}
