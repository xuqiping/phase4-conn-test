package com.superprogrammer.canvas.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.canvas.dto.CanvasCreateRequest;
import com.superprogrammer.canvas.dto.CanvasSaveRequest;
import com.superprogrammer.canvas.dto.CanvasVO;
import com.superprogrammer.canvas.entity.Canvas;
import com.superprogrammer.canvas.service.CanvasService;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 无限画布 REST API（plan IC-14 快照持久化）。
 *
 * <p>权限 gated：所有端点 {@code @RequirePermission("canvas:write")}（切面 403 兜底）。
 * ownership：service 层硬过滤（普通用户只能操作自己的画布，admin 旁路看全量）。
 * 快照整存 JSONB，产出物走 stored_files(SOURCE_CANVAS)，端点不暴露 fileId 之外信息。
 */
@Slf4j
@RestController
@RequestMapping("/api/canvas")
@RequiredArgsConstructor
public class CanvasController {

    private final CanvasService canvasService;

    @PostMapping
    @RequirePermission("canvas:write")
    public ResponseEntity<R<CanvasVO>> create(@Valid @RequestBody(required = false) CanvasCreateRequest req) {
        Long userId = getCurrentUserId();
        String name = req == null ? null : req.getName();
        Canvas c = canvasService.create(userId, name);
        return ResponseEntity.ok(R.ok("画布已创建", toVO(c, true)));
    }

    @GetMapping
    @RequirePermission("canvas:write")
    public ResponseEntity<R<List<CanvasVO>>> list() {
        return ResponseEntity.ok(R.ok(canvasService.list(getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/{id}")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<CanvasVO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(canvasService.get(id, getCurrentUserId(), isAdmin())));
    }

    @PutMapping("/{id}")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<CanvasVO>> save(@PathVariable Long id,
                                            @Valid @RequestBody CanvasSaveRequest req) {
        Canvas c = canvasService.save(id, getCurrentUserId(), isAdmin(), req);
        return ResponseEntity.ok(R.ok("已保存", toVO(c, true)));
    }

    @PatchMapping("/{id}/rename")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<CanvasVO>> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Canvas c = canvasService.rename(id, getCurrentUserId(), isAdmin(), body.get("name"));
        return ResponseEntity.ok(R.ok("已重命名", toVO(c, false)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        canvasService.delete(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("已删除", null));
    }

    private CanvasVO toVO(Canvas c, boolean withSnapshot) {
        return CanvasVO.builder()
                .id(c.getId())
                .name(c.getName())
                .snapshot(withSnapshot ? c.getSnapshot() : null)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
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
