package com.superprogrammer.file.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.mapper.StoredFileMapper;
import com.superprogrammer.knowledge.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FileStorageService 归属强制 IT（V40 咽喉点）——根治 GET /api/files/{id} authenticated IDOR。
 * 真 PG（stored_files 表）+ 真磁盘：store 记 owner → load 强校验 → mismatch FORBIDDEN / admin 越权 / 不存在抛。
 * 这是 Excel tempFileRef 安全模型的治本依据（Excel多Sheet导入设计 §10.3/§10.4）。
 */
class FileStorageOwnershipIT extends AbstractIntegrationTest {

    @Autowired private FileStorageService fileStorageService;
    @Autowired private StoredFileMapper storedFileMapper;
    @Autowired private JdbcTemplate jdbc;

    private static final Long OWNER_A = 7001L;
    private static final Long OWNER_B = 7002L;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.execute("TRUNCATE stored_files");
    }

    @Test
    void store_recordsOwnerAndOwnerCanLoad() {
        StoredFile stored = fileStorageService.store(file("a.xlsx"), OWNER_A, StoredFileEntity.SOURCE_KB);

        StoredFileEntity row = storedFileMapper.selectById(stored.fileId());
        assertThat(row).isNotNull();
        assertThat(row.getOwnerUserId()).isEqualTo(OWNER_A);
        assertThat(row.getSource()).isEqualTo(StoredFileEntity.SOURCE_KB);
        assertThat(row.getStatus()).isEqualTo(StoredFileEntity.STATUS_ACTIVE);

        Resource res = fileStorageService.load(stored.fileId(), OWNER_A, false);
        assertThat(res.exists()).isTrue();
    }

    @Test
    void load_forbiddenWhenOwnerMismatch() {
        StoredFile stored = fileStorageService.store(file("secret.xlsx"), OWNER_A, StoredFileEntity.SOURCE_KB);

        // OWNER_B 拿 A 的 fileId（IDOR 尝试）→ 死在咽喉点
        assertThatThrownBy(() -> fileStorageService.load(stored.fileId(), OWNER_B, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问")
                .extracting("code").isEqualTo(403);
    }

    @Test
    void load_adminOverrideAllowsCrossUser() {
        StoredFile stored = fileStorageService.store(file("ops.xlsx"), OWNER_A, StoredFileEntity.SOURCE_KB);

        Resource res = fileStorageService.load(stored.fileId(), OWNER_B, true);   // admin 越权读
        assertThat(res.exists()).isTrue();
    }

    @Test
    void load_notFoundWhenFileUnknown() {
        assertThatThrownBy(() -> fileStorageService.load("does-not-exist.xlsx", OWNER_A, false))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(404);
    }

    private MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/octet-stream", new byte[]{1, 2, 3, 4});
    }
}
