package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.dto.AssetVO;
import com.superprogrammer.asset.service.AssetService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 项目资产库·资产上传 REST API（plan §S4 / FR-004，设计方案 §三/§九）。
 *
 * <p>文件类资产（图片/视频/音频）上传入口：落 {@code stored_files}(source={@code SOURCE_ASSET})，
 * 类型↔资产类型匹配校验，技术元数据提取入 gen_meta。
 *
 * <p>端点：POST /api/assets/projects/{id}/upload（multipart：file + mediaType + name + 可选 roleKeys/description）
 */
@Slf4j
@RestController
@RequestMapping("/api/assets/projects/{id}/upload")
@RequiredArgsConstructor
public class AssetUploadController {

    private final AssetService assetService;

    @PostMapping
    @RequirePermission("asset:write")
    public ResponseEntity<R<AssetVO>> upload(@PathVariable("id") Long id,
                                             @RequestParam("file") MultipartFile file,
                                             @RequestParam("mediaType") String mediaType,
                                             @RequestParam(value = "name", required = false) String name,
                                             @RequestParam(value = "description", required = false) String description,
                                             @RequestParam(value = "roleKeys", required = false) List<String> roleKeys) {
        return ResponseEntity.ok(R.ok("资产已上传",
                assetService.upload(id, getCurrentUserId(), isAdmin(), file, mediaType, name, description, roleKeys)));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_admin".equalsIgnoreCase(a) || "ROLE_ADMIN".equalsIgnoreCase(a));
    }
}
