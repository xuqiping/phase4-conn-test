package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.projectgroup.dto.ProjectGroupDetailVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupMemberVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupMineVO;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupWalletEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupWalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目组管理服务（计划5 Step2/Step3）：建组/改名/删除/成员增删/限额/used 重置 + mine 列表/详情/候选。
 * <p>权限双层：Controller {@code @RequirePermission("project-group:manage")}（平台码，V134）+
 * 本类 {@code requireOwner}（组长级；admin 越组长代管放行，审计在 Controller @AuditLog）。
 * <p>删除=软删且组池必须 balance=0（先回收再删；wallet 表无软删列，组软删后孤儿行无害——
 * group_id 由 IDENTITY 发号不复用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGroupService {

    private final ProjectGroupMapper groupMapper;
    private final ProjectGroupMemberMapper memberMapper;
    private final ProjectGroupWalletMapper walletMapper;
    private final com.superprogrammer.projectgroup.mapper.ProjectGroupLedgerMapper ledgerMapper;
    private final UserMapper userMapper;

    /**
     * 建组：组行 + 组长成员行（quota NULL=不限）+ 组池 0 行，三写同事务。
     *
     * @return 组 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createGroup(Long ownerUserId, String name, String description) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组名不能为空");
        }
        if (name.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组名最长 64 字");
        }
        ProjectGroupEntity g = new ProjectGroupEntity();
        g.setName(name);
        g.setOwnerUserId(ownerUserId);
        g.setDescription(description);
        groupMapper.insert(g);

        ProjectGroupMemberEntity ownerRow = new ProjectGroupMemberEntity();
        ownerRow.setGroupId(g.getId());
        ownerRow.setUserId(ownerUserId);
        ownerRow.setQuotaLimitPoints(null);
        memberMapper.insert(ownerRow);

        ProjectGroupWalletEntity w = new ProjectGroupWalletEntity();
        w.setGroupId(g.getId());
        w.setBalancePoints(BigDecimal.ZERO);
        walletMapper.insert(w);

        log.info("建组 groupId={} owner={} name={}", g.getId(), ownerUserId, name);
        return g.getId();
    }

    /** 改名（组长/admin）。 */
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long groupId, Long actorUserId, boolean admin, String name) {
        ProjectGroupEntity g = requireOwner(groupId, actorUserId, admin);
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组名不能为空且最长 64 字");
        }
        g.setName(name);
        groupMapper.updateById(g);
    }

    /**
     * 删除（软删，组长/admin）：组池 balance≠0 拒删（先回收）。
     * 成员行随软删（对账视角组已终局）；组流水 append-only 永留。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(Long groupId, Long actorUserId, boolean admin) {
        requireOwner(groupId, actorUserId, admin);
        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        if (w == null || w.getBalancePoints().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组池余额非 0，请先回收全部积分再删除");
        }
        memberMapper.delete(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                .eq(ProjectGroupMemberEntity::getGroupId, groupId));   // @TableLogic → UPDATE deleted=1
        groupMapper.deleteById(groupId);
        log.info("删组(软) groupId={} actor={}", groupId, actorUserId);
    }

    /** 加成员（组长/admin）：quota null=不限。用户须存在；重复入组 CONFLICT。 */
    @Transactional(rollbackFor = Exception.class)
    public void addMember(Long groupId, Long actorUserId, boolean admin, Long memberUserId, BigDecimal quotaLimitPoints) {
        ProjectGroupEntity g = requireOwner(groupId, actorUserId, admin);
        if (memberUserId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员用户不能为空");
        }
        if (quotaLimitPoints != null && quotaLimitPoints.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员限额不能为负");
        }
        if (userMapper.selectById(memberUserId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (memberMapper.selectByGroupUser(groupId, memberUserId) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "该用户已是组成员");
        }
        ProjectGroupMemberEntity m = new ProjectGroupMemberEntity();
        m.setGroupId(groupId);
        m.setUserId(memberUserId);
        m.setQuotaLimitPoints(quotaLimitPoints);
        memberMapper.insert(m);
        log.info("加成员 groupId={} member={} quota={} actor={}", groupId, memberUserId, quotaLimitPoints, actorUserId);
    }

    /** 移除成员（组长/admin）：组长自身不可移除；used>0 照移（历史流水留痕，对账看流水不看行）。 */
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long groupId, Long actorUserId, boolean admin, Long memberUserId) {
        ProjectGroupEntity g = requireOwner(groupId, actorUserId, admin);
        if (g.getOwnerUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组长不可移除，请先删除组");
        }
        ProjectGroupMemberEntity m = requireMember(groupId, memberUserId);
        memberMapper.deleteById(m.getId());
        log.info("移除成员 groupId={} member={} actor={}", groupId, memberUserId, actorUserId);
    }

    /** 调整成员限额（组长/admin）：null=改为不限；调低不追偿（V133 列注释口径），仅约束后续消耗。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateQuota(Long groupId, Long actorUserId, boolean admin, Long memberUserId, BigDecimal quotaLimitPoints) {
        requireOwner(groupId, actorUserId, admin);
        if (quotaLimitPoints != null && quotaLimitPoints.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员限额不能为负");
        }
        ProjectGroupMemberEntity m = requireMember(groupId, memberUserId);
        m.setQuotaLimitPoints(quotaLimitPoints);
        memberMapper.updateById(m);
        log.info("调限额 groupId={} member={} quota={} actor={}", groupId, memberUserId, quotaLimitPoints, actorUserId);
    }

    /**
     * 重置成员 used（组长/admin）：used→0 + 组流水 ADMIN_ADJUST（delta=0，balance_after=组池现值，
     * remark 记前后值留痕）。quota 不动。重置后若有迟到退款，GREATEST 落 0，
     * Σ(CONSUME−REFUND) 与 used 会偏差——罕见，对账黄灯人工核（V133 模板注）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetUsed(Long groupId, Long actorUserId, boolean admin, Long memberUserId) {
        requireOwner(groupId, actorUserId, admin);
        ProjectGroupMemberEntity m = requireMember(groupId, memberUserId);
        BigDecimal before = m.getUsedPoints();
        m.setUsedPoints(BigDecimal.ZERO);
        memberMapper.updateById(m);

        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity l = new com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity();
        l.setGroupId(groupId);
        l.setActorUserId(actorUserId);
        l.setType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.TYPE_ADMIN_ADJUST);
        l.setDeltaPoints(BigDecimal.ZERO);
        l.setBalanceAfter(w != null ? w.getBalancePoints() : BigDecimal.ZERO);
        l.setRefType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.REF_ADMIN);
        l.setRefId(String.valueOf(memberUserId));
        l.setRemark("重置成员已用: " + before + "→0");
        ledgerMapper.insert(l);
        log.info("重置used groupId={} member={} before={} actor={}", groupId, memberUserId, before, actorUserId);
    }

    // ==================== Step3：读侧 + 候选 ====================

    /**
     * 我的组列表（前端选择器数据源）：我建的 + 我在的，含各组余额/我的身份/我的限额与已用/成员数。
     */
    public List<ProjectGroupMineVO> listMine(Long userId) {
        List<ProjectGroupEntity> mine = groupMapper.selectList(new LambdaQueryWrapper<ProjectGroupEntity>()
                .eq(ProjectGroupEntity::getOwnerUserId, userId)
                .orderByDesc(ProjectGroupEntity::getId));
        List<ProjectGroupMemberEntity> myRows = memberMapper.selectList(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                .eq(ProjectGroupMemberEntity::getUserId, userId));
        Set<Long> memberGroupIds = myRows.stream()
                .map(ProjectGroupMemberEntity::getGroupId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> seen = new LinkedHashSet<>();
        List<ProjectGroupEntity> all = new ArrayList<>(mine);
        if (!memberGroupIds.isEmpty()) {
            groupMapper.selectList(new LambdaQueryWrapper<ProjectGroupEntity>()
                            .in(ProjectGroupEntity::getId, memberGroupIds))
                    .forEach(all::add);
        }
        Map<Long, ProjectGroupMemberEntity> myRowByGroup = myRows.stream()
                .collect(Collectors.toMap(ProjectGroupMemberEntity::getGroupId, Function.identity(), (a, b) -> a));

        List<ProjectGroupMineVO> result = new ArrayList<>();
        for (ProjectGroupEntity g : all) {
            if (g.getDeleted() != null && g.getDeleted() != 0) {
                continue;
            }
            if (!seen.add(g.getId())) {
                continue;
            }
            boolean owner = g.getOwnerUserId().equals(userId);
            ProjectGroupMemberEntity myRow = myRowByGroup.get(g.getId());
            ProjectGroupWalletEntity w = walletMapper.selectByGroupId(g.getId());
            Long memberCount = memberMapper.selectCount(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                    .eq(ProjectGroupMemberEntity::getGroupId, g.getId()));
            result.add(new ProjectGroupMineVO(
                    g.getId(), g.getName(), g.getDescription(), g.getOwnerUserId(),
                    owner ? "OWNER" : "MEMBER",
                    w != null ? w.getBalancePoints() : BigDecimal.ZERO,
                    myRow != null ? myRow.getQuotaLimitPoints() : null,
                    myRow != null ? myRow.getUsedPoints() : BigDecimal.ZERO,
                    memberCount.intValue(), g.getCreatedAt()));
        }
        return result;
    }

    /**
     * 组详情（组长/admin，管理页）：组基本信息 + 组池余额/在途占用 + 成员列表（含 username/used/quota）。
     */
    public ProjectGroupDetailVO getDetail(Long groupId, Long actorUserId, boolean admin) {
        ProjectGroupEntity g = requireOwner(groupId, actorUserId, admin);
        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        BigDecimal inflight = walletMapper.sumInflightEstimated(groupId);

        List<ProjectGroupMemberEntity> rows = memberMapper.selectList(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                .eq(ProjectGroupMemberEntity::getGroupId, groupId)
                .orderByAsc(ProjectGroupMemberEntity::getId));
        List<Long> userIds = rows.stream().map(ProjectGroupMemberEntity::getUserId).toList();
        Map<Long, User> users = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        List<ProjectGroupMemberVO> members = rows.stream().map(m -> {
            User u = users.get(m.getUserId());
            return new ProjectGroupMemberVO(
                    m.getUserId(),
                    u != null ? u.getUsername() : null,
                    u != null && u.getName() != null ? u.getName() : (u != null ? u.getUsername() : null),
                    m.getUserId().equals(g.getOwnerUserId()),
                    m.getQuotaLimitPoints(),
                    m.getUsedPoints(),
                    m.getCreatedAt());
        }).toList();

        User owner = users.get(g.getOwnerUserId());
        return new ProjectGroupDetailVO(
                g.getId(), g.getName(), g.getDescription(),
                g.getOwnerUserId(), owner != null ? owner.getUsername() : null,
                w != null ? w.getBalancePoints() : BigDecimal.ZERO,
                inflight,
                members, g.getCreatedAt());
    }

    /**
     * 候选用户搜索（复用资产库模式）：排除组长与已有成员；空关键词开箱载 50、输入收窄 20。
     */
    public List<com.superprogrammer.projectgroup.dto.ProjectGroupCandidateVO> searchCandidates(
            Long groupId, Long actorUserId, boolean admin, String keyword) {
        ProjectGroupEntity g = requireOwner(groupId, actorUserId, admin);
        Set<Long> excluded = new LinkedHashSet<>();
        excluded.add(g.getOwnerUserId());
        memberMapper.selectList(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                        .eq(ProjectGroupMemberEntity::getGroupId, groupId))
                .stream().map(ProjectGroupMemberEntity::getUserId).forEach(excluded::add);
        String safeKeyword = keyword == null ? "" : keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        int limit = safeKeyword.isEmpty() ? 50 : 20;
        return userMapper.searchActiveCandidates(safeKeyword, new ArrayList<>(excluded), limit)
                .stream().limit(limit)
                .map(u -> new com.superprogrammer.projectgroup.dto.ProjectGroupCandidateVO(u.getId(), u.getUsername()))
                .toList();
    }

    /** 成员身份探针（Step4/5 计费链路用）：在组返成员行，不在返 null。 */
    public ProjectGroupMemberEntity findMember(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return null;
        }
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            return null;
        }
        return memberMapper.selectByGroupUser(groupId, userId);
    }

    // ==================== 内部 ====================

    private ProjectGroupMemberEntity requireMember(Long groupId, Long userId) {
        ProjectGroupMemberEntity m = memberMapper.selectByGroupUser(groupId, userId);
        if (m == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该用户不是组成员");
        }
        return m;
    }

    /** 组长校验（admin 放行）；返回组实体供调用方复用。 */
    private ProjectGroupEntity requireOwner(Long groupId, Long userId, boolean admin) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        if (!admin && !g.getOwnerUserId().equals(Objects.requireNonNull(userId, "actorUserId"))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅组长可管理项目组");
        }
        return g;
    }
}
