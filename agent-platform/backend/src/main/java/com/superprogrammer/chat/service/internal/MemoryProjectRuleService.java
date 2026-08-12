package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryProjectRuleRequest;
import com.superprogrammer.chat.dto.MemoryProjectRuleVO;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryProjectRuleMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 记忆二期 P1 · 项目收录规则 service（FR-001）。
 * <p>
 * v1 每项目一条活规则（DB 部分唯一索引兜底）；保存时同步算 anchor（复用
 * {@link MemoryTagAnchorService} embed+jieba 管线，文本=rule_text+正例）。
 * <b>embed 失败 → 规则存库但 enabled 强制 false</b>（粗筛无锚点等于规则不生效，
 * 宁停勿错收），VO 以 anchorReady=false 告知前端提示。
 * <p>
 * 权边界：写=owner/admin（ACTIVE）；读=项目 ACTIVE 成员，但 negative_examples 仅 owner/admin 可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryProjectRuleService {

    private static final String ROLE_OWNER = "OWNER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String STATUS_ACTIVE = "ACTIVE";

    static final int MAX_RULE_TEXT_LEN = 2000;
    static final int MAX_EXAMPLES = 5;
    static final int MAX_EXAMPLE_LEN = 500;
    static final int MAX_FILENAME_PATTERNS = 10;
    static final int MAX_FILENAME_PATTERN_LEN = 100;

    private final MemoryProjectRuleMapper ruleMapper;
    private final MemoryProjectMemberMapper memberMapper;
    private final MemoryTagAnchorService anchorService;

    /** 读取规则（成员可见；negative_examples 仅 owner/admin）。无规则返 null。 */
    public MemoryProjectRuleVO getRule(Long projectId, Long userId) {
        MemoryProjectRule rule = findActiveRule(projectId);
        if (rule == null) {
            return null;
        }
        boolean privileged = isOwnerOrAdmin(projectId, userId);
        return toVO(rule, privileged);
    }

    /**
     * 保存规则（upsert：无则建、有则改）。仅 owner/admin。
     * <p>
     * anchor 同步重算（rule_text+正例）；embed 失败 → enabled 强制 false + anchorReady=false。
     *
     * @return 保存后的 VO（调用方已是 owner/admin，含 negative_examples）
     */
    public MemoryProjectRuleVO saveRule(Long projectId, MemoryProjectRuleRequest req, Long operatorId) {
        validate(req);
        MemoryProjectRule existing = findActiveRule(projectId);

        boolean enabled = req.getEnabled() == null || req.getEnabled();
        MemoryTagAnchorService.AnchorPayload anchor = anchorService.build(
                operatorId, null, null, req.getRuleText(), req.getPositiveExamples());
        boolean anchorReady = anchor != null;
        if (!anchorReady && enabled) {
            // 坑点规避：anchor 未生成时规则不生效——强制停用并提示，防「配了规则却永远收不到」。
            enabled = false;
            log.warn("收录规则 anchor 构建失败 projectId={} → enabled 强制 false", projectId);
        }

        MemoryProjectRule rule = existing != null ? existing : new MemoryProjectRule();
        rule.setProjectId(projectId);
        rule.setRuleText(req.getRuleText().trim());
        rule.setPositiveExamples(trimExamples(req.getPositiveExamples()));
        rule.setNegativeExamples(trimExamples(req.getNegativeExamples()));
        rule.setFilenamePatterns(trimExamples(req.getFilenamePatterns()));
        rule.setEnabled(enabled);
        rule.setAnchorTokens(anchorReady ? anchor.tokens() : null);

        if (existing == null) {
            rule.setCreatedBy(operatorId);
            rule.setUpdatedBy(operatorId);
            try {
                ruleMapper.insertWithAnchor(rule, anchorReady ? anchor.halfvec() : null);
            } catch (DuplicateKeyException e) {
                // 并发双建撞部分唯一索引 → 回查改走更新（同 MemoryTagResolver 并发范式）。
                MemoryProjectRule winner = findActiveRule(projectId);
                if (winner == null) {
                    throw e;
                }
                rule.setId(winner.getId());
                ruleMapper.updateWithAnchor(rule, anchorReady ? anchor.halfvec() : null);
            }
            log.info("收录规则新建 projectId={} operatorId={} enabled={} anchorReady={}", projectId, operatorId, enabled, anchorReady);
        } else {
            rule.setUpdatedBy(operatorId);
            ruleMapper.updateWithAnchor(rule, anchorReady ? anchor.halfvec() : null);
            log.info("收录规则更新 projectId={} ruleId={} operatorId={} enabled={} anchorReady={}",
                    projectId, rule.getId(), operatorId, enabled, anchorReady);
        }
        return toVO(rule, true);
    }

    /**
     * 负例滚动追加（FR-005 审核「弃」反哺）：追加到尾部，超 5 条先进先出。
     * 只改 negative_examples，不动 anchor（负例不参与粗筛锚点，避免锚点被负例带偏）。
     */
    public void appendNegativeExample(Long projectId, String example) {
        if (example == null || example.isBlank()) {
            return;
        }
        MemoryProjectRule rule = findActiveRule(projectId);
        if (rule == null) {
            return;
        }
        List<String> negatives = new java.util.ArrayList<>(
                rule.getNegativeExamples() != null ? rule.getNegativeExamples() : List.of());
        negatives.add(example.length() > MAX_EXAMPLE_LEN ? example.substring(0, MAX_EXAMPLE_LEN) : example);
        while (negatives.size() > MAX_EXAMPLES) {
            negatives.remove(0);
        }
        MemoryProjectRule update = new MemoryProjectRule();
        update.setId(rule.getId());
        update.setRuleText(rule.getRuleText());
        update.setPositiveExamples(rule.getPositiveExamples());
        update.setNegativeExamples(negatives);
        update.setEnabled(rule.getEnabled());
        // anchor 不动：updateWithAnchor 的 anchorHalfvec=null → COALESCE 保留旧值；anchorTokens 同理传 null。
        ruleMapper.updateWithAnchor(update, null);
        log.info("收录规则负例滚动 projectId={} ruleId={} negatives={}", projectId, rule.getId(), negatives.size());
    }

    /** 路由候选：一批项目内 enabled 且 anchor 就绪的活规则（Step 3 粗筛输入集）。 */
    public List<MemoryProjectRule> findRoutingCandidates(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return List.of();
        }
        return ruleMapper.findRoutingCandidates(projectIds);
    }

    /** 调用者是否为项目 ACTIVE 的 owner/admin（规则写权、负例可见性判据）。 */
    public boolean isOwnerOrAdmin(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            return false;
        }
        MemoryProjectMember m = memberMapper.selectOne(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, projectId)
                .eq(MemoryProjectMember::getUserId, userId));
        return m != null && STATUS_ACTIVE.equals(m.getStatus())
                && (ROLE_OWNER.equals(m.getRole()) || ROLE_ADMIN.equals(m.getRole()));
    }

    /** 查项目活规则（内部用）。 */
    MemoryProjectRule findActiveRule(Long projectId) {
        return ruleMapper.selectOne(new LambdaQueryWrapper<MemoryProjectRule>()
                .eq(MemoryProjectRule::getProjectId, projectId)
                .last("LIMIT 1"));
    }

    private MemoryProjectRuleVO toVO(MemoryProjectRule rule, boolean privileged) {
        return MemoryProjectRuleVO.builder()
                .id(rule.getId())
                .projectId(rule.getProjectId())
                .ruleText(rule.getRuleText())
                .positiveExamples(rule.getPositiveExamples())
                .negativeExamples(privileged ? rule.getNegativeExamples() : null)
                .filenamePatterns(rule.getFilenamePatterns())
                .enabled(rule.getEnabled())
                .anchorReady(rule.getAnchorTokens() != null)
                .updatedAt(rule.getUpdatedAt())
                .build();
    }

    private void validate(MemoryProjectRuleRequest req) {
        if (req == null || req.getRuleText() == null || req.getRuleText().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "规则文本必填");
        }
        if (req.getRuleText().length() > MAX_RULE_TEXT_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "规则文本超长（≤" + MAX_RULE_TEXT_LEN + "字）");
        }
        checkExamples(req.getPositiveExamples(), "正例");
        checkExamples(req.getNegativeExamples(), "负例");
        checkFilenamePatterns(req.getFilenamePatterns());
    }

    private void checkExamples(List<String> examples, String label) {
        if (examples == null) {
            return;
        }
        if (examples.size() > MAX_EXAMPLES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + "最多 " + MAX_EXAMPLES + " 条");
        }
        for (String ex : examples) {
            if (ex == null || ex.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, label + "含空条目");
            }
            if (ex.length() > MAX_EXAMPLE_LEN) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, label + "单条超长（≤" + MAX_EXAMPLE_LEN + "字）");
            }
        }
    }

    /** 文件名硬规则校验：≤10 条、单条 ≤100 字、无空条目（v1 子串包含）。 */
    private void checkFilenamePatterns(List<String> patterns) {
        if (patterns == null) {
            return;
        }
        if (patterns.size() > MAX_FILENAME_PATTERNS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "文件名规则最多 " + MAX_FILENAME_PATTERNS + " 条");
        }
        for (String p : patterns) {
            if (p == null || p.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名规则含空条目");
            }
            if (p.length() > MAX_FILENAME_PATTERN_LEN) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "文件名规则单条超长（≤" + MAX_FILENAME_PATTERN_LEN + "字）");
            }
        }
    }

    private List<String> trimExamples(List<String> examples) {
        if (examples == null) {
            return List.of();
        }
        return examples.stream().map(String::trim).toList();
    }
}
