package com.superprogrammer.common.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2 示范 · file 域：A 上传的文件，B 凭 fileId 读取 → 403（load 咽喉点 owner 校验）。
 * 回归意义：若 load 的归属校验被去掉，本 IT 立刻红。
 */
class FilePrivilegeIT extends AbstractPrivilegeIT {

    private static final String USER_A = "priv_file_a";
    private static final String USER_B = "priv_file_b";

    /** 最小合法 PNG（1x1）。 */
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82
    };

    @AfterAll
    void cleanup() {
        deleteUser(USER_A);
        deleteUser(USER_B);
    }

    @Test
    void crossUserFileReadForbidden() throws Exception {
        String tokenA = createUserAndLogin(USER_A);
        String tokenB = createUserAndLogin(USER_B);

        // given：A 上传一个文件
        MvcResult uploaded = mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "pixel.png", MediaType.IMAGE_PNG_VALUE, PNG))
                        .with(bearer(tokenA)))
                .andExpect(status().isOk())
                .andReturn();
        String fileId = objectMapper.readTree(uploaded.getResponse().getContentAsString())
                .path("data").path("fileId").asText();

        try {
            // when/then：B 读 → 403（非 404/500）
            assertForbidden(mockMvc.perform(get("/api/files/{fileId}", fileId).with(bearer(tokenB))));
            // 正向对照：owner A 可读
            mockMvc.perform(get("/api/files/{fileId}", fileId).with(bearer(tokenA)))
                    .andExpect(status().isOk());
        } finally {
            jdbc.update("DELETE FROM stored_files WHERE file_id = ?", fileId);
        }
    }
}
