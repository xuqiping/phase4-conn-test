package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeBaseRequest;
import com.superprogrammer.knowledge.dto.KnowledgeBaseVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgePermission;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
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
    /**
     * 安全体系 S5 · SEC-FR-027（C8 枚举残点）：visibility 服务端白名单（V17 契约 PRIVATE/TEAM/PUBLIC）。
     * 原实现自由串直写库——脏值会让 canRead 的 "PUBLIC".equalsIgnoreCase 分支永不命中，
     * 下游可见集语义也依赖该字段，脏值=权限判定不可预期。
     */
    private static final Set<String> ALLOWED_VISIBILITY = Set.of("PRIVATE", "TEAM", "PUBLIC");

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

    /** 校验 visibility：null/blank → 默认 PRIVATE；非法值 → 400（对齐 UserController ALLOWED_STATUS 范式）。 */
    private String normalizeVisibility(String raw) {
        if (raw == null || raw.isBlank()) {
            return "PRIVATE";
        }
        String v = raw.trim().toUpperCase();
        if (!ALLOWED_VISIBILITY.contains(v)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法 visibility，仅支持 PRIVATE/TEAM/PUBLIC");
        }
        return v;
    }

    /**
     * 14x#3：保密开关与可见性互斥校验——PUBLIC 库禁开保密
     * （公开库任何人可读，保密语义自相矛盾；spec §5.3 边界）。
     */
    private static void assertConfidentialCompatible(String visibility, Boolean confidential) {
        if (Boolean.TRUE.equals(confidential) && "PUBLIC".equalsIgnoreCase(visibility)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "公开（PUBLIC）知识库不允许开启保密；请先调整为私有/团队可见性");
        }
    }

    /**
     * 14x#1：校验 answer_model——null/blank → null（跟随全局默认）；
     * 超长 400；须在启用 CHAT 模型列表内（防任意串写库导致问答路由 422）。
     */
    private String normalizeAnswerModel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String m = raw.trim();
        if (m.length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "问答模型名过长（≤128）");
        }
        if (!llmProviderService.listActiveModels("CHAT").contains(m)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "问答模型未启用或不存在：" + m + "，请在 LLM 供应商管理中确认已启用");
        }
        return m;
    }

    /**
     * 14x#1（L5 边界）：检索期解析 per-KB 问答模型。空/未启用（模型已下线）→ null，
     * 调用方不 set model → LlmGateway 走全局默认回退，问答不因下线中断。
     */
    public String resolveAnswerModel(KnowledgeBase kb) {
        if (kb == null || kb.getAnswerModel() == null || kb.getAnswerModel().isBlank()) {
            return null;
        }
        String m = kb.getAnswerModel().trim();
        return llmProviderService.listActiveModels("CHAT").contains(m) ? m : null;
    }

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgePermissionMapper permissionMapper;
    private final KnowledgeDocumentMapper documentMapper;
    /** 14x#1：answer_model 保存校验（须在启用 CHAT 列表）与解析期 active 过滤共用 */
    private final com.superprogrammer.llm.service.LlmProviderService llmProviderService;
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
        kb.setVisibility(normalizeVisibility(request.getVisibility()));
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
        kb.setAnswerModel(normalizeAnswerModel(request.getAnswerModel()));
        // 14x#3：保密开关（默认关；PUBLIC 互斥校验）
        assertConfidentialCompatible(kb.getVisibility(), request.getConfidential());
        kb.setConfidential(Boolean.TRUE.equals(request.getConfidential()));
        kb.setSummaryStrategy(normalizeStrategy(request.getSummaryStrategy()));
        kb.setStatus("ACTIVE");
        kb.setCreatedBy(userId);
        baseMapper.insert(kb);
        return toVO(kb, userId, true);
    }

    @Transactional
    public KnowledgeBaseVO update(Long id, KnowledgeBaseRequest request, Long userId, boolean admin) {
        KnowledgeBase kb = ensure(id);
        // 14x#2：改名/改模型/可见性属库级治理，仅 owner/admin（canManage 授予位不含销毁与改名）
        if (!isOwnerOrAdmin(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或知识库创建者可修改");
        }
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        if (request.getVisibility() != null && !request.getVisibility().isBlank()) {
            kb.setVisibility(normalizeVisibility(request.getVisibility()));
        }
        // 14x#3：保密切换（null=不动既有开关；对「本次生效后的可见性」做 PUBLIC 互斥）
        if (request.getConfidential() != null) {
            assertConfidentialCompatible(kb.getVisibility(), request.getConfidential());
            kb.setConfidential(request.getConfidential());
        }
        String embeddingWarning = null;
        if (request.getEmbeddingModel() != null && !request.getEmbeddingModel().isBlank()) {
            String newEmbedding = request.getEmbeddingModel().trim();
            // 14x#1（L4）：换向量模型且库内已有文档 → 存量向量仍是旧模型空间，混空间检索质量劣化，
            // 返回 warning 强提示重建索引（空库无存量向量，无需提示）
            if (!newEmbedding.equalsIgnoreCase(kb.getEmbeddingModel()) && hasDocuments(kb.getId())) {
                embeddingWarning = "向量模型已变更，存量向量仍为旧模型空间；请到「索引运维」重建索引后再检索，否则召回质量会劣化";
            }
            kb.setEmbeddingModel(newEmbedding);
        }
        if (request.getSummaryStrategy() != null && !request.getSummaryStrategy().isBlank()) {
            kb.setSummaryStrategy(normalizeStrategy(request.getSummaryStrategy()));
        }
        kb.setRerankModel(request.getRerankModel());
        kb.setAnswerModel(normalizeAnswerModel(request.getAnswerModel()));
        baseMapper.updateById(kb);
        KnowledgeBaseVO vo = toVO(kb, userId, admin);
        vo.setWarning(embeddingWarning);
        return vo;
    }

    /** 库内是否已有未删除文档（L4 换 embedding 提示条件；@TableLogic 自动滤删除行）。 */
    private boolean hasDocuments(Long kbId) {
        LambdaQueryWrapper<com.superprogrammer.knowledge.entity.KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(com.superprogrammer.knowledge.entity.KnowledgeDocument::getKbId, kbId);
        return documentMapper.selectCount(wrapper) > 0;
    }

    @Transactional
    public void delete(Long id, Long userId, boolean admin) {
        KnowledgeBase kb = ensure(id);
        // 14x#2：删库仅 owner/admin（canManage 授予位不含销毁库）
        if (!isOwnerOrAdmin(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或知识库创建者可删除");
        }
        kb.setStatus("ARCHIVED");
        baseMapper.updateById(kb);
        // MyBatis-Plus @TableLogic: 逻辑删除由 deleteById 触发；此处先置 ARCHIVED 再逻辑删
        baseMapper.deleteById(id);
    }

    /** 库级身份判定：admin 或库创建者。改名/删除等销毁类操作仅此判定（14x#2：授予位不含销毁库）。 */
    public boolean isOwnerOrAdmin(KnowledgeBase kb, Long userId, boolean admin) {
        return admin || (userId != null && userId.equals(kb.getCreatedBy()));
    }

    /**
     * canManage = admin ‖ owner ‖ 直接授予 canManage 位（14x#2：授权弹窗「管理」勾选生效，
     * 可管理该库授权与治理；但 KB 改名/删除走 isOwnerOrAdmin，授予位不含销毁库）。
     */
    public boolean canManage(KnowledgeBase kb, Long userId, boolean admin) {
        return isOwnerOrAdmin(kb, userId, admin) || hasGrantLevel(kb.getId(), userId, GrantLevel.MANAGE);
    }

    public boolean canManage(Long kbId, Long userId, boolean admin) {
        return canManage(ensure(kbId), userId, admin);
    }

    /** canWrite = canManage ‖ 直接授予 canWrite 位（canRead 单独授权不再放行写——越权修复核心）。 */
    public boolean canWrite(KnowledgeBase kb, Long userId, boolean admin) {
        return canManage(kb, userId, admin) || hasGrantLevel(kb.getId(), userId, GrantLevel.WRITE);
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

    /** 授权位语义档位：高位隐含低位（MANAGE>WRITE>READ）。 */
    private enum GrantLevel { READ, WRITE, MANAGE }

    /**
     * 是否存在该用户对 KB 的直接授权且达到要求档位（Phase1：USER 直接授权；ROLE/DEPT 聚合在可见集）。
     * 14x#2 谓词修复：原实现 canRead ‖ (requireWrite && canWrite)——只读授权也能通过写检查。
     * 修复后按档位严格判定：READ=任一位；WRITE=canWrite/canManage；MANAGE=仅 canManage。
     */
    private boolean hasGrantLevel(Long kbId, Long userId, GrantLevel level) {
        return hasLevel(userGrants(kbId, userId), level);
    }

    /** 纯函数档位判定（高位隐含低位），供单次拉取后多处复用免 N+1。 */
    private static boolean hasLevel(List<KnowledgePermission> perms, GrantLevel level) {
        if (perms == null || perms.isEmpty()) {
            return false;
        }
        return perms.stream().anyMatch(p -> switch (level) {
            case MANAGE -> Boolean.TRUE.equals(p.getCanManage());
            case WRITE -> Boolean.TRUE.equals(p.getCanWrite()) || Boolean.TRUE.equals(p.getCanManage());
            case READ -> Boolean.TRUE.equals(p.getCanRead())
                    || Boolean.TRUE.equals(p.getCanWrite())
                    || Boolean.TRUE.equals(p.getCanManage());
        });
    }

    /** 拉取该用户对 KB 的 USER 直接授权行（kbId/userId 任一为空返空表，不查库）。 */
    private List<KnowledgePermission> userGrants(Long kbId, Long userId) {
        if (kbId == null || userId == null) {
            return List.of();
        }
        LambdaQueryWrapper<KnowledgePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgePermission::getTargetType, "KB")
                .eq(KnowledgePermission::getTargetId, kbId)
                .eq(KnowledgePermission::getSubjectType, "USER")
                .eq(KnowledgePermission::getSubjectId, userId);
        return permissionMapper.selectList(wrapper);
    }

    private KnowledgeBaseVO toVO(KnowledgeBase kb, Long userId, boolean admin) {
        // owner/admin 短路免查授权表；否则单次拉取授权行派生 manage/write 两态（免 N+1）
        boolean owner = isOwnerOrAdmin(kb, userId, admin);
        boolean manage = owner;
        boolean write = owner;
        if (!owner) {
            List<KnowledgePermission> grants = userGrants(kb.getId(), userId);
            manage = hasLevel(grants, GrantLevel.MANAGE);
            write = manage || hasLevel(grants, GrantLevel.WRITE);
        }
        return KnowledgeBaseVO.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .visibility(kb.getVisibility())
                .embeddingModel(kb.getEmbeddingModel())
                .rerankModel(kb.getRerankModel())
                .answerModel(kb.getAnswerModel())
                .confidential(Boolean.TRUE.equals(kb.getConfidential()))
                .summaryStrategy(kb.getSummaryStrategy())
                .status(kb.getStatus())
                .createdBy(kb.getCreatedBy())
                .createdAt(kb.getCreatedAt())
                .canManage(manage)
                .canWrite(write)
                .canRead(canRead(kb, userId, admin))
                .build();
    }
}
