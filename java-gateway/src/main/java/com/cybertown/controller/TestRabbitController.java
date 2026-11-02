package com.cybertown.controller;

import com.cybertown.config.RabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestRabbitController {

    // 引入官方帮我们写好的超级快递员：RabbitTemplate
    private final RabbitTemplate rabbitTemplate;

    public TestRabbitController(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    // 暴露一个 HTTP 测试接口
    @GetMapping("/testSend")
    public String testSend(@RequestParam(defaultValue = "Hello from Spring Boot!") String msg) {

        // 核心寄信代码：把 msg 这句话，扔进 chat_task_queue 信箱！
        rabbitTemplate.convertAndSend(RabbitConfig.CHAT_TASK_QUEUE, msg);

        System.out.println("✅ 成功寄出信件: " + msg);
        return "投递成功！快去 RabbitMQ 面板看看吧！";
    }
}
