package com.superprogrammer.feedback;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V141/V142 迁移冒烟 IT（真 PG，it 库启动即 migrate）：
 * CHECK 拒非法状态/slug、slug 唯一、权限码落库并授 admin。
 */
@SpringBootTest
@Tag("integration")
@ActiveProfiles("it")
class FeedbackMigrationIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 建议状态CHECK_拒非法值() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO feedback_suggestions (user_id, username, title, content, status) "
                        + "VALUES (1, 'it', 't', 'c', 'HACK')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 提问状态CHECK_拒非法值() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO feedback_questions (user_id, username, title, content, status) "
                        + "VALUES (1, 'it', 't', 'c', 'PENDING')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 文章slug_CHECK与唯一索引() {
        // 非法字符 CHECK 拒
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO help_articles (slug, title, content_md) VALUES ('Bad Slug', 't', 'c')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 唯一：同 slug 第二行撞 uk
        jdbc.update("INSERT INTO help_articles (slug, title, content_md) VALUES ('it-slug-dup', 't', 'c')");
        try {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO help_articles (slug, title, content_md) VALUES ('it-slug-dup', 't2', 'c2')"))
                    .isInstanceOf(DuplicateKeyException.class);
        } finally {
            jdbc.update("DELETE FROM help_articles WHERE slug = 'it-slug-dup'");
        }
    }

    @Test
    void 通知类型CHECK_拒非法值() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO feedback_notifications (user_id, type, ref_id, message) "
                        + "VALUES (1, 'HACK', 1, 'm')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 权限码落库_且admin默认持有() {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM permissions WHERE code IN ('feedback:manage','help:manage')",
                Integer.class);
        assertThat(cnt).isEqualTo(2);
        Integer granted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permissions rp "
                        + "JOIN roles r ON r.id = rp.role_id AND r.code = 'admin' "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE p.code IN ('feedback:manage','help:manage')",
                Integer.class);
        assertThat(granted).isEqualTo(2);
    }
}
