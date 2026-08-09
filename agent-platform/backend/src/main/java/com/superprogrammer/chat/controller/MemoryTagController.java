package com.superprogrammer.chat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryTagCreateRequest;
import com.superprogrammer.chat.dto.MemoryTagEditRequest;
import com.superprogrammer.chat.dto.MemoryTagVO;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.service.internal.MemoryTagAnchorService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划12 B：标签库对外端点（owner 自助 + 可见性收敛）。
 * <p>
 * <b>端点</b>：列本人标签 / owner 改 label 补 aliases / <b>P3a 用户主动新建标签</b>。
 * <b>刻意不提供 merge/split/re-extract</b>——误并不可逆，已生成 summary 的 tag_id 会漂移
 * （L12 边界；单测反射断言 MemoryTagController 无此类端点）。P3a 新建不走归并/拆分，
 * 仅在 (user,subject,topic) 已存在时把 label 滚进既有 aliases（同路径①同义，铁律不破）。
 * <p>
 * 向量 4：VO 只露 label + subject + topic + usage_count（+ id 寻址），
 * aliases / anchor_embedding / anchor_tokens 一律不返。
 * <p>
 * 向量 7：每个 {id} 端点 ownership 校验，非 owner → 403。
 * 向量 15：改 label / 补 aliases 留审计日志（userId + tagId + 动作）。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory/tags")
@RequiredArgsConstructor
public class MemoryTagController {

    private final MemoryTagMapper tagMapper;
    private final MemoryTagAnchorService anchorService;
    private final com.superprogrammer.chat.mapper.MemoryNotificationMapper notificationMapper;
    private final com.superprogrammer.chat.service.internal.MemoryTagRepairService repairService;

    /** 列本人全部标签（按 usage_count 倒序）。只露 VO 字段（向量 4）。 */
    @GetMapping
    public ResponseEntity<R<List<MemoryTagVO>>> list() {
        Long uid = getCurrentUserId();
        List<MemoryTag> tags = tagMapper.selectList(new LambdaQueryWrapper<MemoryTag>()
                .eq(MemoryTag::getUserId, uid)
                .orderByDesc(MemoryTag::getUsageCount)
                .orderByDesc(MemoryTag::getCreatedAt));
        return ResponseEntity.ok(R.ok(tags.stream().map(MemoryTagController::toVO).toList()));
    }

    /**
     * 二期 P3a：用户主动新建标签（主动建；写时归一被动建不变）。
     * <p>
     * 用户选定大类 topic + 自填 label（+可选别名），写入 {@code needs_review=false}（显式选定 = 已裁决）。
     * <b>归一兜底</b>：若 (user, subject, topic) 已有标签（UNIQUE）→ 把 label 滚进既有 aliases 并复用，
     * 同时清掉既有 needs_review（用户主动建此 topic 即认可）——与写时路径①同义，铁律不破。
     */
    @PostMapping
    public ResponseEntity<R<MemoryTagVO>> create(@Valid @RequestBody MemoryTagCreateRequest req) {
        Long uid = getCurrentUserId();
        String subject = (req.getSubject() == null || req.getSubject().isBlank()) ? "我" : req.getSubject().trim();
        String topic = req.getTopic().trim();
        String label = req.getLabel().trim();
        List<String> aliases = req.getAliases() == null ? List.of()
                : req.getAliases().stream().filter(a -> a != null && !a.isBlank()).map(String::trim).toList();

        // 路径①同义兜底：同 (user, subject, topic) 已有标签 → label 滚进 aliases 复用
        MemoryTag exist = tagMapper.findByUserSubjectTopic(uid, subject, topic);
        if (exist != null) {
            if (!label.equals(exist.getLabel())) {
                tagMapper.appendAlias(exist.getId(), label);
            }
            for (String a : aliases) {
                tagMapper.appendAlias(exist.getId(), a);
            }
            if (Boolean.TRUE.equals(exist.getNeedsReview())) {
                tagMapper.clearNeedsReview(exist.getId());
                resolveTagNeedsReviewNotification(exist.getId());
            }
            log.info("主动建标签命中既有 (subject,topic) → 复用+滚别名 userId={} tagId={} label={}",
                    uid, exist.getId(), label);
            MemoryTag fresh = tagMapper.selectById(exist.getId());
            return ResponseEntity.ok(R.ok("标签已存在，已并入", toVO(fresh)));
        }

        // 全 miss 新建（needs_review=false：用户显式选大类 = 已裁决；不发 TAG_NEEDS_REVIEW 通知）
        MemoryTagAnchorService.AnchorPayload anchor = anchorService.build(uid, subject, topic, label, aliases);
        MemoryTag m = new MemoryTag();
        m.setUserId(uid);
        m.setSubject(subject);
        m.setTopic(topic);
        m.setLabel(label);
        m.setUsageCount(0);
        m.setAliases(aliases.isEmpty() ? List.of() : new ArrayList<>(aliases));
        m.setNeedsReview(false);
        m.setCreatedBy(uid);
        m.setUpdatedBy(uid);
        try {
            tagMapper.insertWithAnchor(m,
                    anchor != null ? anchor.halfvec() : null,
                    anchor != null ? anchor.tokens() : null);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 并发兜底（同路径④）：UNIQUE 拦截 → 复用已建行
            log.info("主动建标签并发撞 UNIQUE userId={} subject={} topic={} → 复用", uid, subject, topic);
            MemoryTag winner = tagMapper.findByUserSubjectTopic(uid, subject, topic);
            if (winner != null) {
                if (!label.equals(winner.getLabel())) {
                    tagMapper.appendAlias(winner.getId(), label);
                }
                MemoryTag fresh = tagMapper.selectById(winner.getId());
                return ResponseEntity.ok(R.ok("标签已存在，已并入", toVO(fresh)));
            }
            throw dup;
        }
        log.info("主动新建标签 userId={} tagId={} subject={} topic={} label={}", uid, m.getId(), subject, topic, label);
        return ResponseEntity.ok(R.ok("标签已创建", toVO(m)));
    }

    /**
     * owner 改 label / 补 aliases（tag_id 不变；改 label 时 anchor 重生属预期纠错）。
     * <p>
     * 两字段皆可选，但至少一项非空。无 merge/split/re-extract 语义。
     */
    @PutMapping("/{id}")
    public ResponseEntity<R<MemoryTagVO>> edit(@PathVariable Long id,
                                               @Valid @RequestBody MemoryTagEditRequest req) {
        Long uid = getCurrentUserId();
        boolean hasLabel = req.getLabel() != null && !req.getLabel().isBlank();
        boolean hasAliases = req.getAddAliases() != null && !req.getAddAliases().isEmpty();
        boolean accept = Boolean.TRUE.equals(req.getAccept());
        if (!hasLabel && !hasAliases && !accept) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "label/addAliases/accept 至少填一项");
        }

        MemoryTag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "标签不存在");
        }
        // 向量 7：非 owner 拒绝（不泄露存在性差异，统一 403 语义）
        if (!tag.getUserId().equals(uid)) {
            log.info("标签越权编辑拦截 userId={} tagId={} ownerId={}", uid, id, tag.getUserId());
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该标签");
        }

        // 改 label → anchor 重生（纠错预期）
        if (hasLabel) {
            String newLabel = req.getLabel().trim();
            MemoryTagAnchorService.AnchorPayload anchor = anchorService.build(
                    uid, tag.getSubject(), tag.getTopic(), newLabel, tag.getAliases());
            tagMapper.updateLabelAndAnchor(id, newLabel,
                    anchor != null ? anchor.halfvec() : null,
                    anchor != null ? anchor.tokens() : null);
            log.info("标签改 label userId={} tagId={} old={} new={}", uid, id, tag.getLabel(), newLabel);
        }

        // 补 aliases（去重在 SQL 内：alias 已在集内则不动）
        if (hasAliases) {
            int appended = 0;
            for (String a : req.getAddAliases()) {
                if (a != null && !a.isBlank()) {
                    tagMapper.appendAlias(id, a.trim());
                    appended++;
                }
            }
            log.info("标签补 aliases userId={} tagId={} 条数={}", uid, id, appended);
        }

        // V77：owner 改名/补别名/接受 → 清 needs_review + 消解 TAG_NEEDS_REVIEW 通知（裁决完成）
        if (Boolean.TRUE.equals(tag.getNeedsReview()) && (hasLabel || hasAliases || accept)) {
            tagMapper.clearNeedsReview(id);
            resolveTagNeedsReviewNotification(id);
            log.info("标签 needs_review 已清（用户裁决）userId={} tagId={}", uid, id);
        }

        MemoryTag fresh = tagMapper.selectById(id);
        return ResponseEntity.ok(R.ok("标签已更新", toVO(fresh)));
    }

    /** 实体 → VO（收敛字段，向量 4：aliases/anchor 不外露）。 */
    private static MemoryTagVO toVO(MemoryTag t) {
        return MemoryTagVO.builder()
                .id(t.getId())
                .subject(t.getSubject())
                .topic(t.getTopic())
                .label(t.getLabel())
                .usageCount(t.getUsageCount())
                .needsReview(Boolean.TRUE.equals(t.getNeedsReview()))
                .build();
    }

    /**
     * V77 管理员：大类重映射 / 孤儿锚点回填（一次性 repair 工具）。
     * <p>
     * dryRun=true（默认）→ 仅返回报告（孤儿数/归并组/loser 列表）不落库；
     * dryRun=false → 单事务执行：孤儿锚点重生 + 细标签按 大类 归并（6 表 tag_id 重指 + loser 软删）。
     * <b>不可逆</b>，务必先 dryRun 审报告再 execute。权限：{@code memory:manage}。
     */
    @org.springframework.web.bind.annotation.PostMapping("/repair")
    @com.superprogrammer.auth.security.RequirePermission("memory:manage")
    public ResponseEntity<R<com.superprogrammer.chat.service.internal.MemoryTagRepairService.RepairReport>>
            repair(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(R.ok(repairService.repair(dryRun)));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }

    /** V77：消解该标签关联的未处理 TAG_NEEDS_REVIEW 通知（resolved_at=now）。MP wrapper 走 BaseMapper。 */
    private void resolveTagNeedsReviewNotification(Long tagId) {
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.superprogrammer.chat.entity.MemoryNotification> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        w.eq(com.superprogrammer.chat.entity.MemoryNotification::getType, "TAG_NEEDS_REVIEW")
                .eq(com.superprogrammer.chat.entity.MemoryNotification::getRefId, tagId)
                .isNull(com.superprogrammer.chat.entity.MemoryNotification::getResolvedAt)
                .set(com.superprogrammer.chat.entity.MemoryNotification::getResolvedAt, java.time.OffsetDateTime.now());
        notificationMapper.update(null, w);
    }
}
