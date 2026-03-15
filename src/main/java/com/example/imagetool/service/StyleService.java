package com.example.imagetool.service;

import com.example.imagetool.entity.InspirationTemplate;
import com.example.imagetool.entity.Style;
import com.example.imagetool.repository.InspirationTemplateRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 风格服务层 - 从 SQLite 表 inspiration_template 读取风格列表与提示词
 */
@Service
public class StyleService {

    private final InspirationTemplateRepository inspirationTemplateRepository;

    public StyleService(InspirationTemplateRepository inspirationTemplateRepository) {
        this.inspirationTemplateRepository = inspirationTemplateRepository;
    }

    /**
     * 获取所有风格列表（供首页风格卡片、/api/v1/styles 使用）
     */
    public List<Style> getAllStyles() {
        List<InspirationTemplate> list = inspirationTemplateRepository.findAllOrderBySortOrder();
        return list.stream()
                .map(t -> new Style(t.getId(), t.getTitle() != null ? t.getTitle() : "", t.getImageUrl() != null ? t.getImageUrl() : ""))
                .collect(Collectors.toList());
    }

    /**
     * 根据风格 ID 从数据库取该风格对应的 prompt，供「生成此风格图片」使用
     */
    public String getPromptByStyleId(Integer styleId) {
        InspirationTemplate t = inspirationTemplateRepository.findById(styleId);
        return t != null ? t.getPrompt() : null;
    }
}
