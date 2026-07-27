package com.superprogrammer.chat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryRawBatchDeleteRequest;
import com.superprogrammer.chat.dto.MemoryRawView;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计划12 · C · 流水账对外端点（raw 透明化 + 遗忘权，总体设计 §3.1 + §3.8）。
 * <p>
 * <b>仅本人可访问</b>（向量 7 IDOR：每个端点 ownership 校验；向量 13 批量 ownership 过滤返实际有权条数）。
 * <ul>
 *   <li>{@code GET /raw} —— 列本人 raw 流水账（gen_done=false），在线查看，<b>无导出/下载</b>。</li>
 *   <li>{@code DELETE /{id}} —— 软删单条（作者本人）。<b>注</b>：12h 规则 + summary 级联
 *       （设计 §3.8）留 E 迭代——summary 层未建时无可波及引用，先做 ownership 软删。</li>
 *   <li>{@code POST /raw/batch-delete} —— 批量软删，wrapper 强制 user_id=self，返实际删除条数。</li>
 * </ul>
 * <p>
 * <b>偏离 plan</b>：plan 列「改 MemoryController」——legacy MemoryController 是 {@code /api/chat/memories}
 * （plural，user_memories 旧表，H 收尾 404）。新流水账走 {@code /api/chat/memory/turns}（同 MemoryTagController
 * 命名空间），独立控制器避免新旧纠缠（同 MemoryGenerationService 隔离裁决）。
 *
 * @see MemoryTurnMapper 数据出口
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory/turns")
@RequiredArgsConstructor
public class MemoryTurnController {

    /** raw 列表上限（用户本人 raw 受 90 天 TTL 自限规模，500 防极端）。 */
    private static final int RAW_LIST_CAP = 500;

    private final MemoryTurnMapper turnMapper;

    /** 列本人 raw 流水账（gen_done=false，按时间倒序）。无导出/下载（向量 13）。 */
    @GetMapping("/raw")
    public ResponseEntity<R<List<MemoryRawView>>> listRaw() {
        Long uid = getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        List<MemoryTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<MemoryTurn>()
                .eq(MemoryTurn::getUserId, uid)
                .eq(MemoryTurn::getGenDone, false)
                .orderByDesc(MemoryTurn::getCreatedAt)
                .last("LIMIT " + RAW_LIST_CAP));
        return ResponseEntity.ok(R.ok(turns.stream().map(MemoryTurnController::toVO).toList()));
    }

    /** 软删单条流水账（作者本人，向量 7）。12h + summary 级联留 E。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        Long uid = getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        MemoryTurn t = turnMapper.selectById(id);
        if (t == null) {
            // 不区分「不存在」与「无权」（防存在性探测），统一 NOT_FOUND
            throw new BusinessException(ErrorCode.NOT_FOUND, "流水账不存在或无权操作");
        }
        if (!t.getUserId().equals(uid)) {
            log.info("流水账越权删除拦截 userId={} turnId={} ownerId={}", uid, id, t.getUserId());
            throw new BusinessException(ErrorCode.NOT_FOUND, "流水账不存在或无权操作");
        }
        turnMapper.deleteById(id);  // @TableLogic 软删
        log.info("流水账删除 userId={} turnId={} dir={}", uid, id, t.getDirection());
        return ResponseEntity.ok(R.<Void>ok("已删除", null));
    }

    /** 批量软删 raw（向量 13：wrapper 强制 user_id=self，返实际有权删除条数）。 */
    @PostMapping("/raw/batch-delete")
    public ResponseEntity<R<Integer>> batchDeleteRaw(@Valid @RequestBody MemoryRawBatchDeleteRequest req) {
        Long uid = getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        int deleted = turnMapper.delete(new LambdaQueryWrapper<MemoryTurn>()
                .eq(MemoryTurn::getUserId, uid)
                .in(MemoryTurn::getId, req.getIds()));
        log.info("流水账批量删除 userId={} 请求={} 实删={}", uid, req.getIds().size(), deleted);
        return ResponseEntity.ok(R.ok("已删除 " + deleted + " 条", deleted));
    }

    private static MemoryRawView toVO(MemoryTurn t) {
        return MemoryRawView.builder()
                .id(t.getId())
                .sessionId(t.getSessionId())
                .direction(t.getDirection())
                .rawContent(t.getRawContent())
                .bornPersonal(t.getBornPersonal())
                .projectIds(t.getProjectIds())
                .createdAt(t.getCreatedAt())
                .build();
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return null;
        }
        return (Long) auth.getPrincipal();
    }
}
