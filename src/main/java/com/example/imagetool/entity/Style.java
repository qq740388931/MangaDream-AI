package com.example.imagetool.entity;

/**
 * 风格实体类（无数据库，纯 POJO）
 * 用于风格列表接口返回
 */
public class Style {

    /** 风格 ID */
    private Integer id;
    /** 风格名称 */
    private String styleName;
    /** 风格封面图 URL */
    private String coverUrl;

    public Style() {
    }

    public Style(Integer id, String styleName, String coverUrl) {
        this.id = id;
        this.styleName = styleName;
        this.coverUrl = coverUrl;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getStyleName() {
        return styleName;
    }

    public void setStyleName(String styleName) {
        this.styleName = styleName;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }
}
