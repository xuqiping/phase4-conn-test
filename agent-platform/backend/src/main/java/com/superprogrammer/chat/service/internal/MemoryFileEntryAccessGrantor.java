package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.file.service.FileSharedAccessGrantor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 记忆二期 P3 · Step 4（FR-204）· 项目 FILE 条目成员下载放行。
 * <p>
 * {@code FileStorageService.loadPath} 归属咽喉点的共享放行分支实现：
 * 文件被某 ACTIVE FILE 项目记忆条目引用 且 请求者是该条目项目 ACTIVE 成员 → 放行下载
 * （单 SQL join 裁决，非成员/PENDING_REVIEW 条目/已删条目均不放行，维持 403）。
 * <p>
 * 安全检查：放行仅走本已有 ACL 路径（条目 ACTIVE + 成员 ACTIVE 双条件），不新开旁路。
 */
@Component
@RequiredArgsConstructor
public class MemoryFileEntryAccessGrantor implements FileSharedAccessGrantor {

    private final MemoryProjectEntryMapper entryMapper;

    @Override
    public boolean canAccess(String fileId, Long userId) {
        if (fileId == null || fileId.isBlank() || userId == null) {
            return false;
        }
        return entryMapper.countAccessibleFileEntries(fileId, userId) > 0;
    }
}
