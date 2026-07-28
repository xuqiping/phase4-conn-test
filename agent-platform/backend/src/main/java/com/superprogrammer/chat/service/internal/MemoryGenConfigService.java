package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryGenMatrixItemVO;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectSetting;
import com.superprogrammer.chat.entity.MemoryProjectUserSetting;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryProjectSettingMapper;
import com.superprogrammer.chat.mapper.MemoryProjectUserSettingMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 计划12 · F · gen 开关矩阵读写（总体设计 §3.1 + §5 开关矩阵）。
 * <p>
 * 与 {@link MemoryGenToggleService}（运行时判定 resolveGenEnabled）成对：本 service 负责矩阵展示 + 写入。
 * <p>
 * <b>权边界</b>：
 * <ul>
 *   <li>owner 项目级开关：仅项目 OWNER 可改（{@link #setOwnerToggle}）。</li>
 *   <li>会员覆写开关：会员本人可改自己的（{@link #setMemberOverride}，非成员拒）。</li>
 * </ul>
 * 默认开（无行 / null = true，未显式关 = 开）。
 *
 * @see MemoryGenToggleService 运行时判定
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryGenConfigService {

    private final MemoryProjectMemberMapper memberMapper;
    private final MemoryProjectSettingMapper projectSettingMapper;
    private final MemoryProjectUserSettingMapper userSettingMapper;

    /** 列当前用户 gen 矩阵（我所在的 ACTIVE 项目 + 双开关 + effective）。 */
    public List<MemoryGenMatrixItemVO> getMatrix(Long userId) {
        List<MemoryGenMatrixItemVO> rows = memberMapper.findMyGenMatrix(userId);
        for (MemoryGenMatrixItemVO row : rows) {
            boolean ownerOn = row.getOwnerEnabled() == null || row.getOwnerEnabled();
            boolean memberOn = row.getMemberEnabled() == null || row.getMemberEnabled();
            row.setOwnerEnabled(ownerOn);
            row.setMemberEnabled(memberOn);
            row.setEffective(ownerOn && memberOn);
        }
        return rows;
    }

    /**
     * 设 owner 项目级开关（仅项目 OWNER；否则 403）。
     */
    @Transactional
    public void setOwnerToggle(Long operatorId, Long projectId, boolean genEnabled) {
        requireOwner(projectId, operatorId);
        MemoryProjectSetting row = projectSettingMapper.selectOne(
                new LambdaQueryWrapper<MemoryProjectSetting>()
                        .eq(MemoryProjectSetting::getProjectId, projectId));
        if (row == null) {
            MemoryProjectSetting insert = new MemoryProjectSetting();
            insert.setProjectId(projectId);
            insert.setGenEnabled(genEnabled);
            projectSettingMapper.insert(insert);
        } else {
            row.setGenEnabled(genEnabled);
            projectSettingMapper.updateById(row);
        }
        log.info("gen owner toggle operatorId={} projectId={} genEnabled={}", operatorId, projectId, genEnabled);
    }

    /**
     * 设本人会员覆写开关（任意项目成员可改自己；非成员拒）。
     */
    @Transactional
    public void setMemberOverride(Long userId, Long projectId, boolean genEnabled) {
        requireMember(projectId, userId);
        MemoryProjectUserSetting row = userSettingMapper.selectOne(
                new LambdaQueryWrapper<MemoryProjectUserSetting>()
                        .eq(MemoryProjectUserSetting::getProjectId, projectId)
                        .eq(MemoryProjectUserSetting::getUserId, userId));
        if (row == null) {
            MemoryProjectUserSetting insert = new MemoryProjectUserSetting();
            insert.setProjectId(projectId);
            insert.setUserId(userId);
            insert.setGenEnabled(genEnabled);
            userSettingMapper.insert(insert);
        } else {
            row.setGenEnabled(genEnabled);
            userSettingMapper.updateById(row);
        }
        log.info("gen member override userId={} projectId={} genEnabled={}", userId, projectId, genEnabled);
    }

    // ---- 权边界 helpers ----

    private void requireOwner(Long projectId, Long userId) {
        MemoryProjectMember m = memberMapper.selectOne(
                new LambdaQueryWrapper<MemoryProjectMember>()
                        .eq(MemoryProjectMember::getProjectId, projectId)
                        .eq(MemoryProjectMember::getUserId, userId));
        if (m == null || !"ACTIVE".equals(m.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非项目成员");
        }
        if (!"OWNER".equals(m.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅 owner 可改项目级 gen 开关");
        }
    }

    private void requireMember(Long projectId, Long userId) {
        MemoryProjectMember m = memberMapper.selectOne(
                new LambdaQueryWrapper<MemoryProjectMember>()
                        .eq(MemoryProjectMember::getProjectId, projectId)
                        .eq(MemoryProjectMember::getUserId, userId));
        if (m == null || !"ACTIVE".equals(m.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非项目成员");
        }
    }
}
