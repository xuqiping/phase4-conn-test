package com.superprogrammer.media.edit.service;

import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 剪辑渲染产物存储：本地渲染输出文件 → stored_files(source=EDIT)。
 *
 * <p>复用 {@link FileStorageService#storeStream} 单一存储咽喉点（防路径穿越 + 登记 owner）。
 * 与 {@code MediaStorageService}（Ark URL→落盘）区别：输入是本地文件路径而非远程 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaEditStorageService {

    private final FileStorageService fileStorageService;

    /**
     * 把渲染输出文件落 stored_files(source=EDIT)。
     *
     * @return fileId（写入 media_edit_tasks.result_file_id）
     */
    public String store(Path output, Long userId, Long taskId) throws IOException {
        long size = Files.size(output);
        String name = "edit-" + taskId + ".mp4";
        try (InputStream in = Files.newInputStream(output)) {
            return fileStorageService.storeStream(in, name, "video/mp4", size, userId, StoredFileEntity.SOURCE_EDIT);
        }
    }
}
