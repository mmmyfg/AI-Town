package com.cybertown.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class WebSocketPushConfig {
    @Bean("webSocketPushExecutor")
    public ThreadPoolTaskExecutor webSocketPushExecutor(
            @Value("${chat.websocket.core-threads:2}") int core,
            @Value("${chat.websocket.max-threads:4}") int max,
            @Value("${chat.websocket.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("chat-ws-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
