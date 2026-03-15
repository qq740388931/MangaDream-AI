package com.example.imagetool.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * SQLite 数据源配置（Spring Boot 不内置 SQLite，需手动提供 DataSource）
 */
@Configuration
public class DataSourceConfig {

    @Value("${spring.datasource.url:jdbc:sqlite:./data/mangadream.db}")
    private String url;

    @Bean
    public DataSource dataSource() {
        return DataSourceBuilder.create()
                .driverClassName("org.sqlite.JDBC")
                .url(url)
                .build();
    }
}
