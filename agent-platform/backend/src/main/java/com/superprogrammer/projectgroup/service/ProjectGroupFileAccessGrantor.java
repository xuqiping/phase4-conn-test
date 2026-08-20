package com.superprogrammer.projectgroup.service;

import com.superprogrammer.file.service.FileSharedAccessGrantor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 项目组产物文件共享放行（17x#1，V138）。
 * <p>组内成员按「组可见性设置」互读媒体产物文件（图片/视频），
 * ACL 判定集中在 {@link ProjectGroupVisibilityService#canAccessGroupFile}；
 * 异常由文件咽喉点 fail-closed（FileStorageService 钩子约定）。
 */
@Component
@RequiredArgsConstructor
public class ProjectGroupFileAccessGrantor implements FileSharedAccessGrantor {

    private final ProjectGroupVisibilityService visibilityService;

    @Override
    public boolean canAccess(String fileId, Long userId) {
        if (fileId == null || fileId.isBlank() || userId == null) {
            return false;
        }
        return visibilityService.canAccessGroupFile(fileId, userId);
    }
}
