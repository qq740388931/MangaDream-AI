package com.example.imagetool.controller;

import com.example.imagetool.common.Result;
import com.example.imagetool.repository.FeedbackRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class FeedbackController {

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
            return Result.error(400, "意见内容不能为空");
        }
        if (username == null || username.trim().isEmpty()) {
            username = "匿名用户";
        }
        feedbackRepository.insert(username, email, content.trim());
        return Result.success(null);
    }
}

