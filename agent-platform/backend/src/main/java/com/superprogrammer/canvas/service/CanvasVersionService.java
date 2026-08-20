package com.superprogrammer.canvas.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.canvas.dto.CanvasVO;
import com.superprogrammer.canvas.dto.CanvasVersionVO;
import com.superprogrammer.canvas.entity.Canvas;
import com.superprogrammer.canvas.entity.CanvasVersion;
import com.superprogrammer.canvas.mapper.CanvasVersionMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 画布版本快照 CRUD（canvas_versions，V135，2x 五轮「版本保存」）。
 *
 * <p>ownership：所有方法先经 {@link CanvasService#loadOwned}（画布归属咽喉点），版本必挂归属画布下。
 * 每画布保留最近 {@link #KEEP_PER_CANVAS} 个（插入后修剪，防长期使用撑爆表）。
 *
 * <p>恢复语义：把版本 snapshot 覆盖回画布当前快照（canvases.snapshot），产生新版本历史不影响——
 * 恢复本身也自动存一个「恢复前」版本，防恢复误操作后无从回退（双保险：前端撤销栈 + 恢复前自动存版）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasVersionService {

    /** 每画布保留版本数上限（超出按 id 升序修剪最旧）。 */
    static final int KEEP_PER_CANVAS = 30;

    private static final DateTimeFormatter DEFAULT_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CanvasService canvasService;
    private final CanvasVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    /** 存版本：snapshot 空则定格服务端画布当前快照；插入后修剪超出上限的旧版本。 */
    @Transactional
    public CanvasVersionVO create(Long canvasId, Long userId, boolean admin,
                                   String label, String snapshot) {
        Canvas canvas = canvasService.loadOwned(canvasId, userId, admin);
        String snap = (snapshot == null || snapshot.isBlank())
                ? canvas.getSnapshot()
                : snapshot;
        if (snap == null || snap.isBlank()) {
            snap = "{}";
        }

        CanvasVersion v = new CanvasVersion();
        v.setCanvasId(canvasId);
        v.setLabel(label == null || label.isBlank()
                ? "版本 " + LocalDateTime.now().format(DEFAULT_LABEL)
                : label.trim());
        v.setSnapshot(snap);
        v.setNodeCount(nodeCountOf(snap));
        versionMapper.insert(v);
        prune(canvasId);
        log.info("canvas version created: canvasId={} versionId={} label={} userId={} nodeCount={}",
                canvasId, v.getId(), v.getLabel(), userId, v.getNodeCount());
        return toVO(v, false);
    }

    /** 版本列表（摘要，无 snapshot 重字段，新→旧）。 */
    public List<CanvasVersionVO> list(Long canvasId, Long userId, boolean admin) {
        canvasService.loadOwned(canvasId, userId, admin);
        LambdaQueryWrapper<CanvasVersion> w = new LambdaQueryWrapper<>();
        w.eq(CanvasVersion::getCanvasId, canvasId);
        w.orderByDesc(CanvasVersion::getId);
        return versionMapper.selectList(w).stream()
                .map(v -> toVO(v, false))
                .collect(Collectors.toList());
    }

    /** 版本详情（带 snapshot，恢复前预览用）。 */
    public CanvasVersionVO get(Long canvasId, Long versionId, Long userId, boolean admin) {
        canvasService.loadOwned(canvasId, userId, admin);
        return toVO(loadVersion(canvasId, versionId), true);
    }

    /**
     * 恢复版本：先把「恢复前」当前快照自动存为一版（防误恢复无从回退），再覆盖画布快照。
     */
    @Transactional
    public CanvasVO restore(Long canvasId, Long versionId, Long userId, boolean admin) {
        Canvas canvas = canvasService.loadOwned(canvasId, userId, admin);
        CanvasVersion v = loadVersion(canvasId, versionId);

        // 恢复前自动存版（不占用户命名：固定「恢复前自动存」前缀+时间）
        CanvasVersion backup = new CanvasVersion();
        backup.setCanvasId(canvasId);
        backup.setLabel("恢复前自动存 " + LocalDateTime.now().format(DEFAULT_LABEL));
        String current = canvas.getSnapshot() == null || canvas.getSnapshot().isBlank()
                ? "{}" : canvas.getSnapshot();
        backup.setSnapshot(current);
        backup.setNodeCount(nodeCountOf(current));
        versionMapper.insert(backup);

        canvas.setSnapshot(v.getSnapshot());
        canvasService.saveEntity(canvas);

        prune(canvasId);
        log.info("canvas version restored: canvasId={} versionId={} backupVersionId={} userId={}",
                canvasId, versionId, backup.getId(), userId);
        return CanvasVO.builder()
                .id(canvas.getId())
                .name(canvas.getName())
                .snapshot(canvas.getSnapshot())
                .nodeCount(nodeCountOf(canvas.getSnapshot()))
                .createdAt(canvas.getCreatedAt())
                .updatedAt(canvas.getUpdatedAt())
                .build();
    }

    /** 删版本（软删）。 */
    @Transactional
    public void delete(Long canvasId, Long versionId, Long userId, boolean admin) {
        canvasService.loadOwned(canvasId, userId, admin);
        CanvasVersion v = loadVersion(canvasId, versionId);
        versionMapper.deleteById(v.getId());
        log.info("canvas version deleted: canvasId={} versionId={} userId={}", canvasId, versionId, userId);
    }

    /** 修剪：每画布只留最近 KEEP_PER_CANVAS 个（按 id 升序软删超出部分）。 */
    private void prune(Long canvasId) {
        LambdaQueryWrapper<CanvasVersion> w = new LambdaQueryWrapper<>();
        w.eq(CanvasVersion::getCanvasId, canvasId);
        w.orderByDesc(CanvasVersion::getId);
        w.last("LIMIT " + (KEEP_PER_CANVAS + 1));
        List<CanvasVersion> newest = versionMapper.selectList(w);
        if (newest.size() <= KEEP_PER_CANVAS) {
            return;
        }
        newest.stream()
                .skip(KEEP_PER_CANVAS)
                .forEach(v -> versionMapper.deleteById(v.getId()));
        log.info("canvas versions pruned: canvasId={} removed={}", canvasId, newest.size() - KEEP_PER_CANVAS);
    }

    /** 载入版本并校验归属画布（版本 id 跨画布错配 → NOT_FOUND，不泄露存在性）。 */
    private CanvasVersion loadVersion(Long canvasId, Long versionId) {
        if (versionId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "版本 id 缺失");
        }
        CanvasVersion v = versionMapper.selectById(versionId);
        if (v == null || !canvasId.equals(v.getCanvasId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "版本不存在");
        }
        return v;
    }

    private int nodeCountOf(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(snapshot);
            JsonNode nodes = root.path("nodes");
            return nodes.isArray() ? nodes.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private CanvasVersionVO toVO(CanvasVersion v, boolean withSnapshot) {
        return CanvasVersionVO.builder()
                .id(v.getId())
                .canvasId(v.getCanvasId())
                .label(v.getLabel())
                .nodeCount(v.getNodeCount())
                .snapshot(withSnapshot ? v.getSnapshot() : null)
                .createdAt(v.getCreatedAt())
                .build();
    }
}
