package com.cybertown.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cybertown.entity.TaskOutbox;
import com.cybertown.mapper.TaskOutboxMapper;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxPublisher {
    @Autowired private TaskOutboxMapper outboxMapper;
    @Autowired private RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelayString = "${chat.outbox.publish-interval-ms:500}")
    public void publishPendingEvents() {
        List<TaskOutbox> events = outboxMapper.selectPublishable(50);
        for (TaskOutbox event : events) {
            if (event.getId() == null || outboxMapper.claim(event.getId()) != 1) continue;
            publishOne(event);
        }
    }

    private void publishOne(TaskOutbox event) {
        try {
            CorrelationData correlation = new CorrelationData(event.getEventId());
            rabbitTemplate.convertAndSend(
                    event.getExchangeName(), event.getRoutingKey(), event.getPayload(), message -> {
                        message.getMessageProperties().setMessageId(event.getTaskId());
                        message.getMessageProperties().setCorrelationId(event.getEventId());
                        message.getMessageProperties().setHeader("task_id", event.getTaskId());
                        return message;
                    }, correlation);

            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("Broker NACK: " + confirm.getReason());
            }

            outboxMapper.update(null, new LambdaUpdateWrapper<TaskOutbox>()
                    .eq(TaskOutbox::getId, event.getId())
                    .eq(TaskOutbox::getStatus, "SENDING")
                    .set(TaskOutbox::getStatus, "PUBLISHED")
                    .set(TaskOutbox::getPublishedAt, LocalDateTime.now(ZoneOffset.UTC))
                    .set(TaskOutbox::getLastError, null));
        } catch (Exception exception) {
            int retry = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
            long backoffSeconds = Math.min(60, 1L << Math.min(retry, 6));
            outboxMapper.update(null, new LambdaUpdateWrapper<TaskOutbox>()
                    .eq(TaskOutbox::getId, event.getId())
                    .eq(TaskOutbox::getStatus, "SENDING")
                    .set(TaskOutbox::getStatus, "FAILED")
                    .set(TaskOutbox::getRetryCount, retry)
                    .set(TaskOutbox::getLastError, limit(exception.getMessage()))
                    .set(TaskOutbox::getNextRetryAt,
                            LocalDateTime.now(ZoneOffset.UTC).plusSeconds(backoffSeconds)));
        }
    }

    private String limit(String message) {
        String value = message == null ? "未知投递错误" : message;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
