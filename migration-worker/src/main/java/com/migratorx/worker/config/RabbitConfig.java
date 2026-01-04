package com.migratorx.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ configuration for migration worker.
 * Defines exchange, queues, bindings, and retry/dead-letter queue setup.
 */
@Configuration
public class RabbitConfig {
    
    @Value("${migrations.exchange:migrations.exchange}")
    private String exchangeName;
    
    @Value("${migrations.queue:migrations.worker.queue}")
    private String workerQueueName;
    
    @Value("${migrations.retry.queue:migrations.retry.queue}")
    private String retryQueueName;
    
    @Value("${migrations.failed.queue:migrations.failed.queue}")
    private String failedQueueName;
    
    @Value("${migrations.routing.key:migrations.request}")
    private String routingKey;
    
    @Value("${migrations.retry.routing.key:migrations.retry}")
    private String retryRoutingKey;
    
    @Value("${migrations.failed.routing.key:migrations.failed}")
    private String failedRoutingKey;
    
    @Value("${migration.retry.ttl:30000}")
    private Integer retryTtl; // 30 seconds default
    
    // Exchange
    @Bean
    public DirectExchange migrationsExchange() {
        return ExchangeBuilder.directExchange(exchangeName)
                .durable(true)
                .build();
    }
    
    // Worker Queue with DLX for failed messages
    @Bean
    public Queue workerQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", exchangeName);
        args.put("x-dead-letter-routing-key", retryRoutingKey);
        
        return QueueBuilder.durable(workerQueueName)
                .withArguments(args)
                .build();
    }
    
    // Retry Queue with TTL and DLX
    @Bean
    public Queue retryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", exchangeName);
        args.put("x-dead-letter-routing-key", routingKey);
        args.put("x-message-ttl", retryTtl);
        
        return QueueBuilder.durable(retryQueueName)
                .withArguments(args)
                .build();
    }
    
    // Failed Queue
    @Bean
    public Queue failedQueue() {
        return QueueBuilder.durable(failedQueueName)
                .build();
    }
    
    // Bindings
    @Bean
    public Binding workerBinding(Queue workerQueue, DirectExchange migrationsExchange) {
        return BindingBuilder.bind(workerQueue)
                .to(migrationsExchange)
                .with(routingKey);
    }
    
    @Bean
    public Binding retryBinding(Queue retryQueue, DirectExchange migrationsExchange) {
        return BindingBuilder.bind(retryQueue)
                .to(migrationsExchange)
                .with(retryRoutingKey);
    }
    
    @Bean
    public Binding failedBinding(Queue failedQueue, DirectExchange migrationsExchange) {
        return BindingBuilder.bind(failedQueue)
                .to(migrationsExchange)
                .with(failedRoutingKey);
    }
    
    // JSON Message Converter
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
    
    // RabbitTemplate with JSON converter
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
    
    // Container factory for listeners with manual acks
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
