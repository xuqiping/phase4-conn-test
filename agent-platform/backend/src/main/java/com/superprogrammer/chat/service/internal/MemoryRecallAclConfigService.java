package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryRecallAclRequest;
import com.superprogrammer.chat.dto.MemoryRecallAclVO;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryRecallAcl;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryRecallAclMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划12 · I2 · 项目记忆 ACL 配置 service（总体设计 §3.6 + §6 向量 14/15）。
 * <p>
 * 写侧：全量替换某 reader 在项目的可读 target 集（删旧+插新，{@code created_by}=操作人审计）。
 * 读侧：授权矩阵 VO（带 username，GET 端点返）。
 * <p>
 * <b>配置权边界</b>（向量 14）：仅 owner 或 {@code recall_admin=true} admin 可配（{@link #isConfigurable}），
 * controller 调本方法判，非权 → 403。recall_admin 仅多「配 ACL」权，读不扩（I1 resolver 契约）。
 * <p>
 * <b>target 校验</b>：{@code targetUserIds} 须为项目在册成员（含 DEPARTED，保交接）；非成员 target 静默滤掉
 * （防配错——授权读一个不在项目的人无意义）。空集 = 清权（合法）。
 *
 * @see MemoryRecallAclResolver 读侧 resolver（I1，召回/总结取数共用）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRecallAclConfigService {

    private static final String ROLE_OWNER = "OWNER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final MemoryRecallAclMapper recallAclMapper;
    private final MemoryProjectMemberMapper memberMapper;

    /**
     * 是否有 ACL 配置权（向量 14 边界）。
     * <p>
     * 须 ACTIVE 在职（DEPARTED 已离开 → 无配权，同 {@link MemoryRosterService#isMember} 读权判定）；
     * owner 兜底 true；admin 须 {@code recall_admin=true}；member / 非成员 false。
     *
     * @return true=可配（GET/PUT 矩阵）；false=无权（controller 抛 403）
     */
    public boolean isConfigurable(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            return false;
        }
        MemoryProjectMember m = memberMapper.selectOne(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, projectId)
                .eq(MemoryProjectMember::getUserId, userId));
        if (m == null || !STATUS_ACTIVE.equals(m.getStatus())) {
            return false;  // 非成员 / DEPARTED → 无配权
        }
        if (ROLE_OWNER.equals(m.getRole())) {
            return true;
        }
        return ROLE_ADMIN.equals(m.getRole()) && Boolean.TRUE.equals(m.getRecallAdmin());
    }

    /**
     * 全量替换 reader 在 projectId 的可读 target 集（@Transactional 删旧+插新原子，向量 15 审计）。
     * <p>
     * target ∩ 项目在册成员集过滤（含 DEPARTED）；reader 须为项目成员。返最终写入行数。
     *
     * @param operatorId 操作人（审计 created_by）
     */
    @Transactional(rollbackFor = Exception.class)
    public int replaceAll(Long projectId, Long readerUserId, MemoryRecallAclRequest req, Long operatorId) {
        if (projectId == null || readerUserId == null) {
            return 0;
        }
        // reader 须为项目成员（防配一个非成员读者的权）
        MemoryProjectMember reader = memberMapper.selectOne(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, projectId)
                .eq(MemoryProjectMember::getUserId, readerUserId));
        if (reader == null) {
            log.warn("replaceAll reader 非项目成员 projectId={} readerUserId={} operatorId={} → skip",
                    projectId, readerUserId, operatorId);
            return 0;
        }

        // 先删该 reader 全部旧授权（全量替换语义）
        int deleted = recallAclMapper.deleteByProjectAndReader(projectId, readerUserId);

        List<Long> targets = req == null ? null : req.getTargetUserIds();
        if (targets == null || targets.isEmpty()) {
            log.info("ACL 清权 projectId={} readerUserId={} operatorId={} deleted={}",
                    projectId, readerUserId, operatorId, deleted);
            return 0;  // 空集 = 清权（合法）
        }

        // target ∩ 项目在册成员（含 DEPARTED 保交接）——非成员静默滤
        Set<Long> memberIds = loadMemberUserIds(projectId);
        OffsetDateTime now = OffsetDateTime.now();
        int written = 0;
        for (Long targetId : targets.stream().distinct().toList()) {
            if (targetId == null || !memberIds.contains(targetId)) {
                continue;  // 非成员 target 跳过
            }
            MemoryRecallAcl row = new MemoryRecallAcl();
            row.setProjectId(projectId);
            row.setReaderUserId(readerUserId);
            row.setTargetUserId(targetId);
            row.setCreatedBy(operatorId);   // 审计（向量 15）
            row.setCreatedAt(now);
            recallAclMapper.insert(row);
            written++;
        }
        log.info("ACL 全量替换 projectId={} readerUserId={} operatorId={} deleted={} written={}",
                projectId, readerUserId, operatorId, deleted, written);
        return written;
    }

    /** 当前授权矩阵（带 reader/target username，GET 端点返；仅 owner/recall_admin 可见，controller 判）。 */
    public List<MemoryRecallAclVO> getMatrix(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        return recallAclMapper.findGrantedDetails(projectId);
    }

    /** 项目全部在册成员 user_id 集（含 DEPARTED，保交接，作 target 合法集）。 */
    private Set<Long> loadMemberUserIds(Long projectId) {
        List<MemoryProjectMember> all = memberMapper.selectList(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, projectId));
        Set<Long> ids = new HashSet<>();
        for (MemoryProjectMember m : all) {
            if (m.getUserId() != null) {
                ids.add(m.getUserId());
            }
        }
        return ids;
    }
}
