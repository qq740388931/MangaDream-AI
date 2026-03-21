package com.example.imagetool.controller;

import com.example.imagetool.common.Result;
import com.example.imagetool.repository.FeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final FeedbackRepository feedbackRepository;

    public FeedbackController(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    @PostMapping("/feedback")
    public Result<Void> submit(@RequestBody Map<String, Object> body) {
        String content = body != null ? (String) body.get("content") : null;
        String username = body != null ? (String) body.get("username") : null;
        String email = body != null ? (String) body.get("email") : null;
        if (content == null || content.trim().isEmpty()) {
            log.warn("feedback: 内容为空");
            return Result.error(400, "系统繁忙请稍后再试");
        }
        if (username == null || username.trim().isEmpty()) {
            username = "Anonymous";
        }
        try {
            feedbackRepository.insert(username, email, content.trim());
        } catch (Exception e) {
            log.error("feedback 写入失败: username={}", username, e);
            throw e;
        }
        return Result.success(null);
    }
}

