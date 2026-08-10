package com.superprogrammer.asset.service;

import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.file.service.FileSharedAccessGrantor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 资产项目文件共享放行：文件必须仍被请求者可读的存量资产引用。
 * 具体 owner/member/public ACL 条件集中在 mapper 单 SQL 中，异常由文件咽喉点 fail-closed。
 */
@Component
@RequiredArgsConstructor
public class AssetFileAccessGrantor implements FileSharedAccessGrantor {

    private final AssetMapper assetMapper;

    @Override
    public boolean canAccess(String fileId, Long userId) {
        if (fileId == null || fileId.isBlank() || userId == null) {
            return false;
        }
        return assetMapper.countAccessibleFileReferences(fileId, userId) > 0;
    }
}
