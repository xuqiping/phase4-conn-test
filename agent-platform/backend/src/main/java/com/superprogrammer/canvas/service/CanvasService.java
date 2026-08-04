package com.superprogrammer.canvas.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.canvas.dto.CanvasSaveRequest;
import com.superprogrammer.canvas.dto.CanvasVO;
import com.superprogrammer.canvas.entity.Canvas;
import com.superprogrammer.canvas.mapper.CanvasMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 无限画布快照 CRUD（写+读侧），ownership 硬过滤。
 *
 * <p>归属规则：普通用户只能看/编/删自己的画布（{@code WHERE user_id=current}）；admin 旁路看全量。
 * 快照整存整取 JSONB，产出物走 stored_files（{@code SOURCE_CANVAS}），快照只存 fileId 引用（plan R-5）。
 *
 * <p>可观测性：保存/删除打日志（canvasId/userId/nodeCount），复用 media traceId 风格。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasService {

    /** 默认画布名。 */
    public static final String DEFAULT_NAME = "未命名画布";

    private final CanvasMapper canvasMapper;
    private final ObjectMapper objectMapper;

    /** 新建画布（空快照）。 */
    public Canvas create(Long userId, String name) {
        Canvas c = new Canvas();
        c.setUserId(userId);
        c.setName(name == null || name.isBlank() ? DEFAULT_NAME : name.trim());
        c.setSnapshot("{}");
        canvasMapper.insert(c);
        log.info("canvas created: id={} userId={} name={}", c.getId(), userId, c.getName());
        return c;
    }

    /** 列表（摘要，省略 snapshot 重字段）。admin 看全量。 */
    public List<CanvasVO> list(Long userId, boolean admin) {
        LambdaQueryWrapper<Canvas> w = new LambdaQueryWrapper<>();
        if (!admin) {
            w.eq(Canvas::getUserId, userId);
        }
        w.orderByDesc(Canvas::getUpdatedAt);
        return canvasMapper.selectList(w).stream()
                .map(c -> toVO(c, false))
                .collect(Collectors.toList());
    }

    /** 详情（带 snapshot）。 */
    public CanvasVO get(Long id, Long userId, boolean admin) {
        Canvas c = loadOwned(id, userId, admin);
        return toVO(c, true);
    }

    /** 全量保存（name + snapshot）。 */
    public Canvas save(Long id, Long userId, boolean admin, CanvasSaveRequest req) {
        Canvas c = loadOwned(id, userId, admin);
        c.setName(req.getName().trim());
        // snapshot 可空（空画布首次保存兜底 "{}"）
        String snap = req.getSnapshot() == null || req.getSnapshot().isBlank() ? "{}" : req.getSnapshot();
        c.setSnapshot(snap);
        canvasMapper.updateById(c);
        log.info("canvas saved: id={} userId={} nodeCount={}", id, userId, nodeCountOf(snap));
        return c;
    }

    /** 仅重命名。 */
    public Canvas rename(Long id, Long userId, boolean admin, String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "画布名不能为空");
        }
        Canvas c = loadOwned(id, userId, admin);
        c.setName(name.trim());
        canvasMapper.updateById(c);
        return c;
    }

    /** 软删（不级联清 stored_files 产出物，plan 联动清单）。 */
    public void delete(Long id, Long userId, boolean admin) {
        Canvas c = loadOwned(id, userId, admin);
        canvasMapper.deleteById(c.getId());
        log.info("canvas deleted: id={} userId={}", id, userId);
    }

    /** 载入并校验 ownership（admin 旁路）。 */
    private Canvas loadOwned(Long id, Long userId, boolean admin) {
        Canvas c = canvasMapper.selectById(id);
        if (c == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "画布不存在");
        }
        if (!admin && (userId == null || !userId.equals(c.getUserId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该画布");
        }
        return c;
    }

    private CanvasVO toVO(Canvas c, boolean withSnapshot) {
        return CanvasVO.builder()
                .id(c.getId())
                .name(c.getName())
                .snapshot(withSnapshot ? c.getSnapshot() : null)
                .nodeCount(nodeCountOf(c.getSnapshot()))
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    /** 从 snapshot.nodes 数组长度派生节点数；解析失败返回 0（容错，不阻断列表）。 */
    private int nodeCountOf(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(snapshot);
            JsonNode nodes = root.path("nodes");
            return nodes.isArray() ? nodes.size() : 0;
        } catch (Exception e) {
            log.warn("nodeCount 解析失败 canvasId snapshotLen={}: {}", snapshot.length(), e.getMessage());
            return 0;
        }
    }
}
