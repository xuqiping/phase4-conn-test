package com.superprogrammer.knowledge.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVO;
import com.superprogrammer.knowledge.dto.SheetPreviewVO;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVersionVO;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVersionActivateRequest;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentUpdateRequest;
import com.superprogrammer.knowledge.service.KnowledgeDocumentService;
import com.superprogrammer.knowledge.service.KnowledgeDocumentVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/documents")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeDocumentVersionService versionService;

    /** 阶段1：预读 Excel sheet 名（picker）。存文件 + POI 只读名，不建文档行。 */
    @PostMapping("/sheets/preview")
    @RequirePermission("knowledge:write")
    // 安全体系 S4 · SEC-FR-124：上传频率限制（L5 补齐）
    @com.superprogrammer.common.ratelimit.RateLimit(action = "upload_file", max = 10, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public ResponseEntity<R<SheetPreviewVO>> previewSheets(@RequestParam("kbId") Long kbId,
                                                           @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(R.ok(knowledgeDocumentService.previewSheets(
                kbId, file, getCurrentUserId(), isAdmin())));
    }

    /** 阶段2：上传。Excel 可带 tempFileRef + selectedSheets；其他类型走原 file 路径。
     *  docType/indexMode/manualIndexText/visionModel 为图片/文件知识库扩展（空=后端按后缀推断 + AUTO 默认）。 */
    @PostMapping("/upload")
    @AuditLog(module = "kb", action = "document_upload", targetType = "document")
    @RequirePermission("knowledge:write")
    // 安全体系 S4 · SEC-FR-124：上传频率限制（L5 补齐）
    @com.superprogrammer.common.ratelimit.RateLimit(action = "upload_file", max = 10, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public ResponseEntity<R<KnowledgeDocumentVO>> upload(
            @RequestParam("kbId") Long kbId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "tempFileRef", required = false) String tempFileRef,
            @RequestParam(value = "selectedSheets", required = false) List<String> selectedSheets,
            @RequestParam(value = "docType", required = false) String docType,
            @RequestParam(value = "indexMode", required = false) String indexMode,
            @RequestParam(value = "manualIndexText", required = false) String manualIndexText,
            @RequestParam(value = "visionModel", required = false) String visionModel) {
        return ResponseEntity.ok(R.ok("上传成功",
                knowledgeDocumentService.upload(kbId, file, tempFileRef, selectedSheets,
                        docType, indexMode, manualIndexText, visionModel,
                        getCurrentUserId(), isAdmin())));
    }

    /** 安全体系 S3 · SEC-FR-051：解除文档注入隔离（复核通过），置回 PENDING 重新解析入库。 */
    @PostMapping("/{id}/unquarantine")
    @AuditLog(module = "kb", action = "document_unquarantine", targetType = "document")
    @RequirePermission("knowledge:manage")
    public ResponseEntity<R<Void>> unquarantine(@PathVariable Long id) {
        knowledgeDocumentService.unquarantine(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("已解除隔离，重新解析已触发", null));
    }

    /** 取图片/文件原件（KB 成员可读，跨用户）。docType=IMAGE → inline；FILE → attachment 下载。 */
    @GetMapping("/{id}/asset")
    @RequirePermission("knowledge:read")
    public ResponseEntity<org.springframework.core.io.Resource> asset(@PathVariable Long id) {
        return knowledgeDocumentService.streamAsset(id, getCurrentUserId(), isAdmin());
    }

    @GetMapping
    @RequirePermission("knowledge:read")
    public ResponseEntity<R<List<KnowledgeDocumentVO>>> list(@RequestParam Long kbId) {
        return ResponseEntity.ok(R.ok(
                knowledgeDocumentService.list(kbId, getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/{id}")
    @RequirePermission("knowledge:read")
    public ResponseEntity<R<KnowledgeDocumentVO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(knowledgeDocumentService.get(id, getCurrentUserId(), isAdmin())));
    }

    @PutMapping("/{id}/metadata")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "document_metadata_update", targetType = "document")
    public ResponseEntity<R<KnowledgeDocumentVO>> updateMetadata(
            @PathVariable Long id, @Valid @RequestBody KnowledgeDocumentUpdateRequest request) {
        return ResponseEntity.ok(R.ok(knowledgeDocumentService.updateMetadata(
                id, request, getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/{id}/versions")
    @RequirePermission("knowledge:read")
    public ResponseEntity<R<List<KnowledgeDocumentVersionVO>>> versions(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(versionService.listHistoryVO(id, getCurrentUserId(), isAdmin())));
    }

    @PostMapping("/{id}/versions")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "document_version_create", targetType = "document")
    // 安全体系 S4 · SEC-FR-124：上传频率限制（版本上传同走 store 咽喉）
    @com.superprogrammer.common.ratelimit.RateLimit(action = "upload_file", max = 10, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public ResponseEntity<R<KnowledgeDocumentVersionVO>> createVersion(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam("expectedCurrentVersionId") Long expectedCurrentVersionId,
            @RequestParam(value = "changeNote", required = false) String changeNote) {
        return ResponseEntity.ok(R.ok(knowledgeDocumentService.createVersion(
                id, file, expectedCurrentVersionId, changeNote, getCurrentUserId(), isAdmin())));
    }

    @PutMapping("/{id}/versions/{versionId}/activate")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "document_version_activate", targetType = "document_version")
    public ResponseEntity<R<Void>> activateVersion(@PathVariable Long id, @PathVariable Long versionId,
                                                    @Valid @RequestBody KnowledgeDocumentVersionActivateRequest request) {
        versionService.activate(id, versionId, request.getExpectedCurrentVersionId(), getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("版本已生效", null));
    }

    @PutMapping("/{id}/versions/{versionId}/revoke")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "document_version_revoke", targetType = "document_version")
    public ResponseEntity<R<Void>> revokeVersion(@PathVariable Long id, @PathVariable Long versionId) {
        versionService.revoke(id, versionId, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("版本已撤销", null));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("knowledge:write")
    @AuditLog(module = "kb", action = "document_delete", targetType = "document")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        knowledgeDocumentService.delete(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("删除成功", null));
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
