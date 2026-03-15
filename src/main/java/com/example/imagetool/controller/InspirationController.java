package com.example.imagetool.controller;

import com.example.imagetool.common.Result;
import com.example.imagetool.entity.Style;
import com.example.imagetool.service.StyleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 灵感/风格列表接口，供首页「选择你想要的风格」卡片使用
 */
@RestController
@RequestMapping("/api")
public class InspirationController {

    private final StyleService styleService;

    public InspirationController(StyleService styleService) {
        this.styleService = styleService;
    }

    /**
     * 获取灵感风格列表：返回所有风格的 id、标题、封面图、提示词等，用于渲染风格选择卡片
     */
    @GetMapping("/inspiration")
    public Result<Map<String, Object>> getInspiration(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        List<Style> styles = styleService.getAllStyles();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Style s : styles) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", s.getId());
            item.put("templateId", s.getId());
            item.put("title", s.getStyleName());
            item.put("imageUrl", s.getCoverUrl());
            list.add(item);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("hasMore", false);
        return Result.success(data);
    }
}
