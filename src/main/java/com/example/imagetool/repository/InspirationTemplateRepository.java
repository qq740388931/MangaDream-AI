package com.example.imagetool.repository;

import com.example.imagetool.entity.InspirationTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 从 SQLite 表 inspiration_template 读写灵感模板（风格列表 + 提示词）
 */
@Repository
public class InspirationTemplateRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<InspirationTemplate> ROW_MAPPER = new RowMapper<InspirationTemplate>() {
        @Override
        public InspirationTemplate mapRow(ResultSet rs, int rowNum) throws SQLException {
            InspirationTemplate t = new InspirationTemplate();
            t.setId(rs.getInt("id"));
            t.setTitle(rs.getString("title"));
            t.setImageUrl(rs.getString("image_url"));
            t.setDescription(rs.getString("description"));
            t.setPrompt(rs.getString("prompt"));
            t.setSortOrder(rs.getObject("sort_order") != null ? rs.getInt("sort_order") : null);
            t.setCreatedAt(rs.getString("created_at"));
            t.setUpdatedAt(rs.getString("updated_at"));
            return t;
        }
    };

    public InspirationTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按 sort_order 排序查询所有模板 */
    public List<InspirationTemplate> findAllOrderBySortOrder() {
        String sql = "SELECT id, title, image_url, description, prompt, sort_order, created_at, updated_at FROM inspiration_template ORDER BY sort_order ASC, id ASC";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }

    /** 根据 id 查询一条，用于取提示词 */
    public InspirationTemplate findById(Integer id) {
        String sql = "SELECT id, title, image_url, description, prompt, sort_order, created_at, updated_at FROM inspiration_template WHERE id = ?";
        List<InspirationTemplate> list = jdbcTemplate.query(sql, ROW_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }
}
