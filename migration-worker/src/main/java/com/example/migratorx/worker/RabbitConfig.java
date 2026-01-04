package com.example.migratorx.worker;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ Configuration
 * 
 * Sets up exchanges, queues, bindings, and retry/dead-letter infrastructure
 */
@Configuration
public class RabbitConfig {

    @Value("${migrations.exchange:migrations.exchange}")
    private String exchangeName;

    @Value("${migrations.queue:migrations.worker.queue}")
    private String queueName;

    @Value("${migrations.retry.queue:migrations.retry.queue}")
    private String retryQueueName;

    @Value("${migrations.failed.queue:migrations.failed.queue}")
    private String failedQueueName;

    @Value("${migration.retry.ttl:30000}")
    private Integer retryTtl;

    // Main exchange
    @Bean
    public DirectExchange migrationsExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    // Main worker queue
    @Bean
    public Queue workerQueue() {
        Map<String, Object> args = new HashMap<>();
        // Configure dead-letter exchange for failed messages
        args.put("x-dead-letter-exchange", exchangeName);
        args.put("x-dead-letter-routing-key", "failed");
        return new Queue(queueName, true, false, false, args);
    }

    // Retry queue with TTL and dead-letter back to main queue
    @Bean
    public Queue retryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", retryTtl); // 30 seconds default
        args.put("x-dead-letter-exchange", exchangeName);
        args.put("x-dead-letter-routing-key", "worker");
        return new Queue(retryQueueName, true, false, false, args);
    }

    // Failed queue for messages that exceeded retry limit
    @Bean
    public Queue failedQueue() {
        return new Queue(failedQueueName, true);
    }

    // Binding: main queue to exchange
    @Bean
    public Binding workerBinding(Queue workerQueue, DirectExchange migrationsExchange) {
        return BindingBuilder.bind(workerQueue).to(migrationsExchange).with("worker");
    }

    // Binding: retry queue to exchange
    @Bean
    public Binding retryBinding(Queue retryQueue, DirectExchange migrationsExchange) {
        return BindingBuilder.bind(retryQueue).to(migrationsExchange).with("retry");
    }

    // Binding: failed queue to exchange
    @Bean
    public Binding failedBinding(Queue failedQueue, DirectExchange migrationsExchange) {
        return BindingBuilder.bind(failedQueue).to(migrationsExchange).with("failed");
    }

    // JSON message converter
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // RabbitTemplate with JSON converter
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
