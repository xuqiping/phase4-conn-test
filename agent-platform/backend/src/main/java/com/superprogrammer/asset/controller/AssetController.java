package com.superprogrammer.asset.controller;

import com.superprogrammer.asset.dto.AssetCreateRequest;
import com.superprogrammer.asset.dto.AssetUpdateRequest;
import com.superprogrammer.asset.dto.AssetVO;
import com.superprogrammer.asset.dto.ConsistencyPackRequest;
import com.superprogrammer.asset.dto.MatrixCountVO;
import com.superprogrammer.asset.dto.ScriptBreakdownRequest;
import com.superprogrammer.asset.dto.ScriptBreakdownVO;
import com.superprogrammer.asset.dto.VersionCreateRequest;
import com.superprogrammer.asset.dto.VersionVO;
import com.superprogrammer.asset.service.AssetScriptService;
import com.superprogrammer.asset.service.AssetService;
import com.superprogrammer.asset.service.AssetVersionService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目资产库·资产 REST API（plan §S4 / FR-003/004/005，设计方案 §九）。
 *
 * <p>权限：第一层 {@code @RequirePermission("asset:write")}；第二层 service 层 ACL。
 *
 * <p>端点（前缀 /api/assets）：
 * <pre>
 * POST   /projects/{id}/assets          新建文本类资产（PROMPT/SCRIPT）
 * GET    /projects/{id}/assets          矩阵筛选/搜索列表（type/role/q/status，分页）
 * GET    /projects/{id}/assets/count    矩阵每格计数（徽标）
 * GET    /assets/{id}                   详情
 * PUT    /assets/{id}                   更新 meta+分类
 * DELETE /assets/{id}                   软删
 * </pre>
 * 文件类资产（图片/视频/音频）走上传端点（AssetUploadController，S4b）。
 */
@Slf4j
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final AssetVersionService versionService;
    private final AssetScriptService scriptService;

    @PostMapping("/projects/{id}/assets")
    @RequirePermission("asset:write")
    public ResponseEntity<R<AssetVO>> create(@PathVariable("id") Long id, @RequestBody AssetCreateRequest req) {
        return ResponseEntity.ok(R.ok("资产已创建", assetService.create(id, getCurrentUserId(), isAdmin(), req)));
    }

    @GetMapping("/projects/{id}/assets")
    @RequirePermission("asset:write")
    public ResponseEntity<R<PageResult<AssetVO>>> list(@PathVariable("id") Long id,
                                                       @RequestParam(required = false) String type,
                                                       @RequestParam(required = false) String role,
                                                       @RequestParam(required = false) String q,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(R.ok(assetService.list(id, getCurrentUserId(), isAdmin(), type, role, q, status, page, size)));
    }

    @GetMapping("/projects/{id}/assets/count")
    @RequirePermission("asset:write")
    public ResponseEntity<R<MatrixCountVO>> count(@PathVariable("id") Long id) {
        return ResponseEntity.ok(R.ok(assetService.countMatrix(id, getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/assets/{id}")
    @RequirePermission("asset:write")
    public ResponseEntity<R<AssetVO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(assetService.get(id, getCurrentUserId(), isAdmin())));
    }

    @PutMapping("/assets/{id}")
    @RequirePermission("asset:write")
    public ResponseEntity<R<AssetVO>> update(@PathVariable Long id, @RequestBody AssetUpdateRequest req) {
        return ResponseEntity.ok(R.ok("资产已更新", assetService.update(id, getCurrentUserId(), isAdmin(), req)));
    }

    @DeleteMapping("/assets/{id}")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        assetService.delete(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("资产已删除", null));
    }

    // ---------- 版本（plan §S5 / FR-006） ----------

    @GetMapping("/assets/{id}/versions")
    @RequirePermission("asset:write")
    public ResponseEntity<R<java.util.List<VersionVO>>> listVersions(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(versionService.listVersions(id, getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/assets/{id}/versions/{ver}")
    @RequirePermission("asset:write")
    public ResponseEntity<R<VersionVO>> getVersion(@PathVariable Long id, @PathVariable Integer ver) {
        return ResponseEntity.ok(R.ok(versionService.getVersion(id, ver, getCurrentUserId(), isAdmin())));
    }

    @PostMapping("/assets/{id}/versions")
    @RequirePermission("asset:write")
    public ResponseEntity<R<Integer>> createVersion(@PathVariable Long id, @RequestBody VersionCreateRequest req) {
        int newVer = versionService.createVersion(id, getCurrentUserId(), isAdmin(), req);
        return ResponseEntity.ok(R.ok("已创建版本 v" + newVer, newVer));
    }

    // ---------- 状态机（plan §S5 / FR-006，L2/L3） ----------

    @PostMapping("/assets/{id}/lock")
    @RequirePermission("asset:write")
    public ResponseEntity<R<AssetVO>> lock(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok("已定稿", assetService.lock(id, getCurrentUserId(), isAdmin())));
    }

    @PostMapping("/assets/{id}/unlock")
    @RequirePermission("asset:write")
    public ResponseEntity<R<AssetVO>> unlock(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok("已回退草稿", assetService.unlock(id, getCurrentUserId(), isAdmin())));
    }

    @PostMapping("/assets/{id}/archive")
    @RequirePermission("asset:write")
    public ResponseEntity<R<AssetVO>> archive(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok("已归档", assetService.archive(id, getCurrentUserId(), isAdmin())));
    }

    @PostMapping("/assets/{id}/unarchive")
    @RequirePermission("asset:write")
    public ResponseEntity<R<AssetVO>> unarchive(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok("已恢复", assetService.unarchive(id, getCurrentUserId(), isAdmin())));
    }

    // ---------- 一致性包（plan §S5 / FR-007，设计方案 §五） ----------

    @PutMapping("/assets/{id}/consistency-pack")
    @RequirePermission("asset:write")
    public ResponseEntity<R<AssetVO>> saveConsistencyPack(@PathVariable Long id,
                                                          @RequestBody ConsistencyPackRequest req) {
        return ResponseEntity.ok(R.ok("一致性包已保存",
                assetService.saveConsistencyPack(id, getCurrentUserId(), isAdmin(), req)));
    }

    // ---------- 剧本拆分场（plan §S6 / FR-010） ----------

    @PostMapping("/assets/{id}/breakdown")
    @RequirePermission("asset:write")
    public ResponseEntity<R<ScriptBreakdownVO>> breakdown(@PathVariable Long id,
                                                          @RequestBody(required = false) ScriptBreakdownRequest req) {
        return ResponseEntity.ok(R.ok("分场完成",
                scriptService.breakdown(id, getCurrentUserId(), isAdmin(), req)));
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
