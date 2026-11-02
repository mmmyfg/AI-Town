package com.cybertown;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling; // ✨ 引入定时任务依赖

@SpringBootApplication
@EnableScheduling // ✨ 极其关键：开启小镇的“时间齿轮”，驱动 NPC 状态机的定时流转！
@MapperScan("com.cybertown.mapper") // 👈 告诉 Spring 去哪里找数据库的“管理员”接口
public class CybertownApplication {

	public static void main(String[] args) {
		SpringApplication.run(CybertownApplication.class, args);
	}

}