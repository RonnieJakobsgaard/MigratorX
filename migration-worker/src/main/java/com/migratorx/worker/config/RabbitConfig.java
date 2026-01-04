package com.migratorx.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
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
 * RabbitMQ configuration for migration queue topology
 */
@Configuration
public class RabbitConfig {

    @Value("${migrations.exchange:migrations.exchange}")
    private String migrationsExchange;

    @Value("${migrations.queue:migrations.worker.queue}")
    private String migrationsQueue;

    @Value("${migrations.retry.queue:migrations.retry.queue}")
    private String migrationsRetryQueue;

    @Value("${migrations.failed.queue:migrations.failed.queue}")
    private String migrationsFailedQueue;

    @Value("${migration.retry.ttl:30000}")
    private Integer retryTtl;

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        return factory;
    }

    @Bean
    public DirectExchange migrationsExchange() {
        return new DirectExchange(migrationsExchange, true, false);
    }

    @Bean
    public Queue migrationsQueue() {
        return QueueBuilder.durable(migrationsQueue).build();
    }

    @Bean
    public Queue migrationsRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", migrationsExchange);
        args.put("x-dead-letter-routing-key", migrationsQueue);
        args.put("x-message-ttl", retryTtl);
        return new Queue(migrationsRetryQueue, true, false, false, args);
    }

    @Bean
    public Queue migrationsFailedQueue() {
        return QueueBuilder.durable(migrationsFailedQueue).build();
    }

    @Bean
    public Binding migrationsBinding() {
        return BindingBuilder.bind(migrationsQueue())
                .to(migrationsExchange())
                .with(migrationsQueue);
    }

    @Bean
    public Binding retryBinding() {
        return BindingBuilder.bind(migrationsRetryQueue())
                .to(migrationsExchange())
                .with(migrationsRetryQueue);
    }

    @Bean
    public Binding failedBinding() {
        return BindingBuilder.bind(migrationsFailedQueue())
                .to(migrationsExchange())
                .with(migrationsFailedQueue);
    }
}
