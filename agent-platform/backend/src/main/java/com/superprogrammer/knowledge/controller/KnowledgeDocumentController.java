package com.superprogrammer.knowledge.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVO;
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

    @PostMapping("/upload")
    @RequirePermission("knowledge:write")
    public ResponseEntity<R<KnowledgeDocumentVO>> upload(@RequestParam("kbId") Long kbId,
                                                         @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(R.ok("上传成功",
                knowledgeDocumentService.upload(kbId, file, getCurrentUserId(), isAdmin())));
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
