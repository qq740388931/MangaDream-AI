package com.example.imagetool.controller;

import com.example.imagetool.common.Result;
import com.example.imagetool.entity.Style;
import com.example.imagetool.entity.StylePrompt;
import com.example.imagetool.service.StyleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 风格相关 REST 接口（/api/v1）：风格列表、按风格 id 查提示词
 */
@RestController
@RequestMapping("/api/v1")
public class StyleController {

    private static final Logger log = LoggerFactory.getLogger(StyleController.class);

    private final StyleService styleService;

    public StyleController(StyleService styleService) {
        this.styleService = styleService;
    }

    /**
     * 获取风格列表：返回所有风格的 id、风格名、封面 URL，供其他端或管理使用
     */
    @GetMapping("/styles")
    public Result<List<Style>> getStyles() {
        List<Style> list = styleService.getAllStyles();
        return Result.success(list);
    }

    /**
     * 根据风格 ID 获取该风格对应的提示词（prompt），用于生成时的文案
     */
    @GetMapping("/styles/{styleId}/prompt")
    public Result<StylePrompt> getPromptByStyleId(@PathVariable Integer styleId) {
        String prompt = styleService.getPromptByStyleId(styleId);
        if (prompt == null) {
            log.warn("风格不存在或无提示词: styleId={}", styleId);
            return Result.error(404, "该风格不存在或暂无提示词（styleId=" + styleId + "）");
        }
        return Result.success(new StylePrompt(prompt));
    }
}
