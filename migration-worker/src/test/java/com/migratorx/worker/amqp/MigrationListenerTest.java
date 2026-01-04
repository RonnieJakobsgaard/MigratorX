package com.migratorx.worker.amqp;

import com.migratorx.worker.dto.MigrationMessage;
import com.migratorx.worker.service.MigrationService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MigrationListenerTest {
    
    @Mock
    private MigrationService migrationService;
    
    @Mock
    private RabbitTemplate rabbitTemplate;
    
    @Mock
    private Channel channel;
    
    @Mock
    private Message amqpMessage;
    
    private MigrationListener migrationListener;
    
    @BeforeEach
    void setUp() throws Exception {
        migrationListener = new MigrationListener(migrationService, rabbitTemplate);
        
        // Set private fields using reflection for testing
        setField(migrationListener, "maxRetryAttempts", 3);
        setField(migrationListener, "exchangeName", "migrations.exchange");
        setField(migrationListener, "retryQueueName", "migrations.retry.queue");
        setField(migrationListener, "routingKey", "migrations.request");
        setField(migrationListener, "failedRoutingKey", "migrations.failed");
    }
    
    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
    
    @Test
    void testHandleMigrationMessage_Success() throws IOException {
        // Arrange
        MigrationMessage message = new MigrationMessage(
            "test-migration-1",
            "1.0.0",
            "sql",
            "CREATE TABLE test (id SERIAL PRIMARY KEY);",
            new HashMap<>()
        );
        
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(123L);
        
        when(amqpMessage.getMessageProperties()).thenReturn(messageProperties);
        when(migrationService.claimAndApplyMigration(message)).thenReturn(true);
        
        // Act
        migrationListener.handleMigrationMessage(message, amqpMessage, channel);
        
        // Assert
        verify(migrationService).claimAndApplyMigration(message);
        verify(channel).basicAck(123L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }
    
    @Test
    void testHandleMigrationMessage_AlreadyHandled() throws IOException {
        // Arrange
        MigrationMessage message = new MigrationMessage(
            "test-migration-2",
            "1.0.0",
            "sql",
            "CREATE TABLE test2 (id SERIAL PRIMARY KEY);",
            new HashMap<>()
        );
        
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(124L);
        
        when(amqpMessage.getMessageProperties()).thenReturn(messageProperties);
        when(migrationService.claimAndApplyMigration(message)).thenReturn(false);
        
        // Act
        migrationListener.handleMigrationMessage(message, amqpMessage, channel);
        
        // Assert
        verify(migrationService).claimAndApplyMigration(message);
        verify(channel).basicAck(124L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }
    
    @Test
    void testHandleMigrationMessage_FailureWithRetry() throws IOException {
        // Arrange
        MigrationMessage message = new MigrationMessage(
            "test-migration-3",
            "1.0.0",
            "sql",
            "INVALID SQL;",
            new HashMap<>()
        );
        
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(125L);
        Map<String, Object> headers = new HashMap<>();
        messageProperties.setHeaders(headers);
        
        when(amqpMessage.getMessageProperties()).thenReturn(messageProperties);
        when(migrationService.claimAndApplyMigration(message))
            .thenThrow(new RuntimeException("SQL error"));
        
        // Act
        migrationListener.handleMigrationMessage(message, amqpMessage, channel);
        
        // Assert
        verify(migrationService).claimAndApplyMigration(message);
        verify(channel).basicNack(125L, false, false);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), eq(message));
    }
}
