package com.cybertown.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String CHAT_EXCHANGE = "chat.exchange";
    public static final String CHAT_DLX = "chat.dlx";

    public static final String CHAT_TASK_QUEUE = "chat_task_queue";
    public static final String CHAT_RESULT_QUEUE = "chat_result_queue";
    public static final String CHAT_TASK_DLQ = "chat_task_dlq";
    public static final String CHAT_RESULT_DLQ = "chat_result_dlq";

    public static final String TASK_ROUTING_KEY = "chat.task";
    public static final String RESULT_ROUTING_KEY = "chat.result";
    public static final String TASK_DEAD_ROUTING_KEY = "chat.task.dead";
    public static final String RESULT_DEAD_ROUTING_KEY = "chat.result.dead";

    public static final String[] TASK_RETRY_QUEUES = {
            "chat_task_retry_5s", "chat_task_retry_15s", "chat_task_retry_45s"
    };
    public static final String[] TASK_RETRY_ROUTING_KEYS = {
            "chat.task.retry.1", "chat.task.retry.2", "chat.task.retry.3"
    };
    private static final int[] TASK_RETRY_TTLS = {5_000, 15_000, 45_000};

    @Bean
    public DirectExchange chatExchange() {
        return new DirectExchange(CHAT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange chatDeadLetterExchange() {
        return new DirectExchange(CHAT_DLX, true, false);
    }

    @Bean
    public Queue chatTaskQueue() {
        return QueueBuilder.durable(CHAT_TASK_QUEUE)
                .deadLetterExchange(CHAT_DLX)
                .deadLetterRoutingKey(TASK_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue chatResultQueue() {
        return QueueBuilder.durable(CHAT_RESULT_QUEUE)
                .deadLetterExchange(CHAT_DLX)
                .deadLetterRoutingKey(RESULT_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue chatTaskDlq() { return QueueBuilder.durable(CHAT_TASK_DLQ).build(); }

    @Bean
    public Queue chatResultDlq() { return QueueBuilder.durable(CHAT_RESULT_DLQ).build(); }

    @Bean
    public Queue chatTaskRetry5s() { return retryQueue(0); }

    @Bean
    public Queue chatTaskRetry15s() { return retryQueue(1); }

    @Bean
    public Queue chatTaskRetry45s() { return retryQueue(2); }

    private Queue retryQueue(int index) {
        return QueueBuilder.durable(TASK_RETRY_QUEUES[index])
                .ttl(TASK_RETRY_TTLS[index])
                .deadLetterExchange(CHAT_EXCHANGE)
                .deadLetterRoutingKey(TASK_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding taskBinding(Queue chatTaskQueue, DirectExchange chatExchange) {
        return BindingBuilder.bind(chatTaskQueue).to(chatExchange).with(TASK_ROUTING_KEY);
    }

    @Bean
    public Binding resultBinding(Queue chatResultQueue, DirectExchange chatExchange) {
        return BindingBuilder.bind(chatResultQueue).to(chatExchange).with(RESULT_ROUTING_KEY);
    }

    @Bean
    public Binding taskDlqBinding(Queue chatTaskDlq, DirectExchange chatDeadLetterExchange) {
        return BindingBuilder.bind(chatTaskDlq).to(chatDeadLetterExchange).with(TASK_DEAD_ROUTING_KEY);
    }

    @Bean
    public Binding resultDlqBinding(Queue chatResultDlq, DirectExchange chatDeadLetterExchange) {
        return BindingBuilder.bind(chatResultDlq).to(chatDeadLetterExchange).with(RESULT_DEAD_ROUTING_KEY);
    }

    @Bean
    public Binding retry5sBinding(Queue chatTaskRetry5s, DirectExchange chatExchange) {
        return BindingBuilder.bind(chatTaskRetry5s).to(chatExchange).with(TASK_RETRY_ROUTING_KEYS[0]);
    }

    @Bean
    public Binding retry15sBinding(Queue chatTaskRetry15s, DirectExchange chatExchange) {
        return BindingBuilder.bind(chatTaskRetry15s).to(chatExchange).with(TASK_RETRY_ROUTING_KEYS[1]);
    }

    @Bean
    public Binding retry45sBinding(Queue chatTaskRetry45s, DirectExchange chatExchange) {
        return BindingBuilder.bind(chatTaskRetry45s).to(chatExchange).with(TASK_RETRY_ROUTING_KEYS[2]);
    }
}
