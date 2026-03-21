package com.example.imagetool.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域：仅允许 localhost 时，线上域名（或前后端不同端口）会因 CORS 被拒绝，浏览器常表现为 HTTP 403。
 * 默认允许任意来源访问 /api；若需收紧，可在 application.yml 配置 app.cors.allowed-origin-patterns。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 逗号分隔，例如：https://yourdomain.com,https://www.yourdomain.com
     * 为空或未配置时使用 *（任意来源）
     */
    @Value("${app.cors.allowed-origin-patterns:}")
    private String allowedOriginPatternsConfig;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] patterns;
        if (allowedOriginPatternsConfig != null && !allowedOriginPatternsConfig.trim().isEmpty()) {
            patterns = allowedOriginPatternsConfig.split("\\s*,\\s*");
        } else {
            patterns = new String[]{"*"};
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
