package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVO;
import com.superprogrammer.knowledge.dto.SheetPreviewVO;
import com.superprogrammer.knowledge.service.KnowledgeDocumentService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/documents")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService knowledgeDocumentService;

    /** 阶段1：预读 Excel sheet 名（picker）。存文件 + POI 只读名，不建文档行。 */
    @PostMapping("/sheets/preview")
    @RequirePermission("knowledge:write")
    public ResponseEntity<R<SheetPreviewVO>> previewSheets(@RequestParam("kbId") Long kbId,
                                                           @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(R.ok(knowledgeDocumentService.previewSheets(
                kbId, file, getCurrentUserId(), isAdmin())));
    }

    /** 阶段2：上传。Excel 可带 tempFileRef + selectedSheets；其他类型走原 file 路径。
     *  docType/indexMode/manualIndexText/visionModel 为图片/文件知识库扩展（空=后端按后缀推断 + AUTO 默认）。 */
    @PostMapping("/upload")
    @RequirePermission("knowledge:write")
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

    @DeleteMapping("/{id}")
    @RequirePermission("knowledge:write")
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
