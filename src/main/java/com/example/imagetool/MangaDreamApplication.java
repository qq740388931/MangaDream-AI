package com.example.imagetool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MangaDream-AI 漫展照片 AI 处理 - 启动类
 */
@SpringBootApplication
@EnableScheduling
public class MangaDreamApplication {

    public static void main(String[] args) {
        SpringApplication.run(MangaDreamApplication.class, args);
    }
}
