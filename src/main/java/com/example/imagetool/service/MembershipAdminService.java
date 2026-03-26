package com.example.imagetool.service;

import com.example.imagetool.entity.MembershipRequest;
import com.example.imagetool.entity.User;
import com.example.imagetool.repository.MembershipRequestRepository;
import com.example.imagetool.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台处理会员试用申请：确认后为用户充值固定积分。
 */
@Service
public class MembershipAdminService {

    private static final int TRIAL_POINTS = 50;
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MembershipRequestRepository membershipRequestRepository;
    private final UserRepository userRepository;

    public MembershipAdminService(MembershipRequestRepository membershipRequestRepository, UserRepository userRepository) {
        this.membershipRequestRepository = membershipRequestRepository;
        this.userRepository = userRepository;
    }

    public Map<String, Object> listMembershipRequests(int page, int size, String status) {
        if (page < 0) {
            page = 0;
        }
        size = Math.min(Math.max(size, 1), 200);
        long total = membershipRequestRepository.countWithFilter(status);
        int offset = page * size;
        List<Map<String, Object>> rows = membershipRequestRepository.listForAdmin(offset, size, status);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        out.put("rows", rows);
        return out;
    }

    public Map<String, Object> pendingCountMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("pendingCount", membershipRequestRepository.countPending());
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> creditTrial(Long requestId) {
        MembershipRequest m = membershipRequestRepository.findById(requestId);
        if (m == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
        }
        if (!"pending".equals(m.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already credited or not pending");
        }
        if (m.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No user_id (submitted without login); cannot auto-credit");
        }
        String now = LocalDateTime.now().format(F);
        String comment = "trial +" + TRIAL_POINTS + " points";
        int claimed = membershipRequestRepository.markCreditedAtomic(requestId, now, comment);
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already processed");
        }
        int u = userRepository.addPoints(m.getUserId(), TRIAL_POINTS);
        if (u == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        User after = userRepository.findById(m.getUserId());
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("pointsAdded", TRIAL_POINTS);
        out.put("userPointsAfter", after != null ? after.getPoints() : null);
        return out;
    }
}
