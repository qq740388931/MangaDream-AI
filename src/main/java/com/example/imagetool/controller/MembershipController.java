package com.example.imagetool.controller;

import com.example.imagetool.common.Result;
import com.example.imagetool.repository.MembershipRequestRepository;
import com.example.imagetool.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MembershipController {

    private static final Logger log = LoggerFactory.getLogger(MembershipController.class);

    private final MembershipRequestRepository membershipRequestRepository;
    private final UserRepository userRepository;

    public MembershipController(MembershipRequestRepository membershipRequestRepository,
                                UserRepository userRepository) {
        this.membershipRequestRepository = membershipRequestRepository;
        this.userRepository = userRepository;
    }

    @PostMapping("/membership-request")
    public Result<Void> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String email = body != null ? (String) body.get("email") : null;
        if (email == null || email.trim().isEmpty()) {
            log.warn("membership-request: 缺少 email");
            return Result.error(400, "系统繁忙请稍后再试");
        }
        String planCode = body != null ? (String) body.get("planCode") : null;
        if (planCode == null || planCode.trim().isEmpty()) {
            planCode = "monthly_1000_points";
        }

        Long userId = null;
        String username = null;

        String token = request != null ? request.getHeader("X-Session-Token") : null;
        if (token != null && !token.isEmpty()) {
            Long uid = userRepository.findUserIdByToken(token);
            if (uid != null) {
                userId = uid;
                com.example.imagetool.entity.User u = userRepository.findById(uid);
                if (u != null) {
                    username = u.getName() != null ? u.getName() : u.getEmail();
                }
            }
        }

        if (username == null || username.trim().isEmpty()) {
            username = "匿名用户";
        }

        try {
            membershipRequestRepository.insert(userId, username, email.trim(), planCode);
        } catch (Exception e) {
            log.error("membership-request 写入失败: userId={}, email={}", userId, email, e);
            throw e;
        }
        return Result.success(null);
    }
}

