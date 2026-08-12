package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeBaseRequest;
import com.superprogrammer.knowledge.dto.KnowledgeBaseVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgePermission;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.mapper.KnowledgePermissionMapper;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    /** Phase1 单租户 */
    private static final Long TENANT_ID = 1L;
    /** L0 摘要生成模式（v6 §3.1，阶段2 解析）。非法/空 → PER_SECTION。 */
    private static final String DEFAULT_SUMMARY_STRATEGY = "PER_SECTION";
    private static final Set<String> SUMMARY_STRATEGIES = Set.of("PER_SECTION", "BATCH", "HYBRID");

    /** 校验 summary_strategy：合法值原样返回，null/blank/非法 → PER_SECTION（容错 warn，不抛 400）。 */
    private String normalizeStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SUMMARY_STRATEGY;
        }
        String upper = raw.trim().toUpperCase();
        if (!SUMMARY_STRATEGIES.contains(upper)) {
            log.warn("非法 summary_strategy={}, 回退 {}", raw, DEFAULT_SUMMARY_STRATEGY);
            return DEFAULT_SUMMARY_STRATEGY;
        }
        return upper;
    }

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgePermissionMapper permissionMapper;
    /**
     * 可见集服务（USER+ROLE+DEPT 三层并集，KB/DIRECTORY/DOCUMENT 三级 target）。
     * canRead 委托它作单一权限权威（B1 修复）：DIRECTORY/DOCUMENT/ROLE/DEPT 授权可达。
     * 无循环依赖（VisibilitySetService 仅依赖 mapper，不回调本 service）。
     */
    private final VisibilitySetService visibilitySetService;
    private final SystemSettingService systemSettingService;

    public List<KnowledgeBaseVO> list(Long userId, boolean admin) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getTenantId, TENANT_ID)
                .eq(KnowledgeBase::getStatus, "ACTIVE")
                .orderByDesc(KnowledgeBase::getCreatedAt);
        return baseMapper.selectList(wrapper).stream()
                .filter(kb -> canRead(kb, userId, admin))
                .sorted(Comparator.comparing(KnowledgeBase::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(kb -> toVO(kb, userId, admin))
                .toList();
    }

    public KnowledgeBaseVO get(Long id, Long userId, boolean admin) {
        KnowledgeBase kb = ensure(id);
        if (!canRead(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该知识库");
        }
        return toVO(kb, userId, admin);
    }

    @Transactional
    public KnowledgeBaseVO create(KnowledgeBaseRequest request, Long userId) {
        // 主动查重名，给精准友好提示（而非让唯一约束抛 500/409 通用话术）
        LambdaQueryWrapper<KnowledgeBase> dup = new LambdaQueryWrapper<>();
        dup.eq(KnowledgeBase::getTenantId, TENANT_ID)
           .eq(KnowledgeBase::getName, request.getName());
        if (baseMapper.selectCount(dup) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "同名知识库已存在，请更换名称");
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(TENANT_ID);
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        kb.setVisibility(request.getVisibility() == null || request.getVisibility().isBlank()
                ? "PRIVATE" : request.getVisibility());
        String embeddingModel = request.getEmbeddingModel();
        if (embeddingModel == null || embeddingModel.isBlank()) {
            embeddingModel = systemSettingService.getDefaultEmbeddingModel();
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "未选择向量模型，且管理员未配置默认向量模型");
        }
        kb.setEmbeddingModel(embeddingModel.trim());
        kb.setRerankModel(request.getRerankModel());
        kb.setSummaryStrategy(normalizeStrategy(request.getSummaryStrategy()));
        kb.setStatus("ACTIVE");
        kb.setCreatedBy(userId);
        baseMapper.insert(kb);
        return toVO(kb, userId, true);
    }

    @Transactional
    public KnowledgeBaseVO update(Long id, KnowledgeBaseRequest request, Long userId, boolean admin) {
        KnowledgeBase kb = ensure(id);
        if (!canManage(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或知识库创建者可修改");
        }
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        if (request.getVisibility() != null && !request.getVisibility().isBlank()) {
            kb.setVisibility(request.getVisibility());
        }
        if (request.getEmbeddingModel() != null && !request.getEmbeddingModel().isBlank()) {
            kb.setEmbeddingModel(request.getEmbeddingModel());
        }
        if (request.getSummaryStrategy() != null && !request.getSummaryStrategy().isBlank()) {
            kb.setSummaryStrategy(normalizeStrategy(request.getSummaryStrategy()));
        }
        kb.setRerankModel(request.getRerankModel());
        baseMapper.updateById(kb);
        return toVO(kb, userId, admin);
    }

    @Transactional
    public void delete(Long id, Long userId, boolean admin) {
        KnowledgeBase kb = ensure(id);
        if (!canManage(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或知识库创建者可删除");
        }
        kb.setStatus("ARCHIVED");
        baseMapper.updateById(kb);
        // MyBatis-Plus @TableLogic: 逻辑删除由 deleteById 触发；此处先置 ARCHIVED 再逻辑删
        baseMapper.deleteById(id);
    }

    /** canManage = admin 或 owner。供 Permission/Document service 复用。 */
    public boolean canManage(KnowledgeBase kb, Long userId, boolean admin) {
        return admin || (userId != null && userId.equals(kb.getCreatedBy()));
    }

    public boolean canManage(Long kbId, Long userId, boolean admin) {
        return canManage(ensure(kbId), userId, admin);
    }

    public boolean canWrite(KnowledgeBase kb, Long userId, boolean admin) {
        return canManage(kb, userId, admin) || hasGrant(kb.getId(), userId, true);
    }

    public boolean canRead(KnowledgeBase kb, Long userId, boolean admin) {
        // B1 修复：cheap prefix（admin/owner/PUBLIC）先判，避免 KB 列表 N 次可见集计算；
        // 再委托可见集作单一权限权威 —— DIRECTORY/DOCUMENT/ROLE/DEPT 授权可达。
        if (canManage(kb, userId, admin)
                || "PUBLIC".equalsIgnoreCase(kb.getVisibility())) {
            return true;
        }
        com.superprogrammer.knowledge.service.internal.VisibleDocSet vds =
                visibilitySetService.getVisibleDocs(kb.getId(), userId, admin);
        return vds.isAll() || !vds.docsOrEmpty().isEmpty();
    }

    public KnowledgeBase ensure(Long id) {
        KnowledgeBase kb = baseMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        return kb;
    }

    /** 是否存在该用户对 KB 的直接授权（Phase1：USER 直接授权；ROLE/DEPT 聚合在阶段4 可见集）。 */
    private boolean hasGrant(Long kbId, Long userId, boolean requireWrite) {
        if (kbId == null || userId == null) {
            return false;
        }
        LambdaQueryWrapper<KnowledgePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgePermission::getTargetType, "KB")
                .eq(KnowledgePermission::getTargetId, kbId)
                .eq(KnowledgePermission::getSubjectType, "USER")
                .eq(KnowledgePermission::getSubjectId, userId);
        List<KnowledgePermission> perms = permissionMapper.selectList(wrapper);
        return perms.stream().anyMatch(p -> Boolean.TRUE.equals(p.getCanRead())
                || (requireWrite && Boolean.TRUE.equals(p.getCanWrite())));
    }

    private KnowledgeBaseVO toVO(KnowledgeBase kb, Long userId, boolean admin) {
        return KnowledgeBaseVO.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .visibility(kb.getVisibility())
                .embeddingModel(kb.getEmbeddingModel())
                .rerankModel(kb.getRerankModel())
                .summaryStrategy(kb.getSummaryStrategy())
                .status(kb.getStatus())
                .createdBy(kb.getCreatedBy())
                .createdAt(kb.getCreatedAt())
                .canManage(canManage(kb, userId, admin))
                .canRead(canRead(kb, userId, admin))
                .build();
    }
}
