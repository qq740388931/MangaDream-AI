package com.example.imagetool.entity;

/**
 * 风格提示词返回实体类
 * 用于 /api/v1/styles/{styleId}/prompt 接口返回
 */
public class StylePrompt {

    /** 该风格对应的 AI 生成提示词 */
    private String prompt;

    public StylePrompt() {
    }

    public StylePrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }
}
