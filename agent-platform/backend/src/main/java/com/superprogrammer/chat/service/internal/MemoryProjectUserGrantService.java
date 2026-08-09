package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.chat.dto.MemoryProjectUserGrantVO;
import com.superprogrammer.chat.dto.MemorySearchItemVO;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectUserGrant;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryProjectUserGrantMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.project.entity.Project;
import com.superprogrammer.project.mapper.ProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆二期 P1 · 项目↔个人授权状态机（只读召回）。
 * <p>
 * 双向发起，落同一 ACTIVE 授权（与项目→项目授权 {@link MemoryProjectLinkService} 并列，镜像其生命周期）：
 * <ul>
 *   <li>{@code initiated_by=PROJECT}（项目主动授权个人）：项目 owner/admin 发起 → 立即 ACTIVE（发起方即审批方）。</li>
 *   <li>{@code initiated_by=USER}（个人申请）：个人发起 → PENDING + 通知项目 owner/admin → 通过 ACTIVE / 拒绝 REJECTED。</li>
 * </ul>
 * <b>不变量</b>：
 * <ul>
 *   <li>同 (project,user) 仅一条活行（DB 部分唯一索引兜底）；REJECTED 30 天内个人重申 → 409 防刷（按 created_at 判）；
 *       REJECTED 超 30 天 / REVOKED 再发起 = 同行复活并重置时钟。项目主动授权不受防刷限制（权威方）。</li>
 *   <li>状态翻转全走条件 UPDATE（{@code WHERE status=:expected}），影响行数=0 → 409（并发打不穿）。</li>
 *   <li>只读召回：ACTIVE 让被授权人召回范围可勾选该项目并召回其条目摘要；不写回、不进该项目总结生成。
 *       撤销 → REVOKED 实时断召回（取数实时算 ACTIVE 集，无缓存）。</li>
 * </ul>
 * 权边界（内建 service）：项目主动授权/审批=项目 OWNER/ADMIN；个人申请=本人；撤销 ACTIVE=项目 OWNER/ADMIN 或被授权人本人；
 * 取消 PENDING=申请人本人或项目 OWNER/ADMIN。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryProjectUserGrantService {

    private static final String ROLE_OWNER = "OWNER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String STATUS_ACTIVE_MEMBER = "ACTIVE";

    /** REJECTED 防刷窗口（FR 对齐 links：同对拒绝后 30 天个人不可重复申请）。 */
    static final int REJECT_COOLDOWN_DAYS = 30;

    public static final String NOTIFY_TYPE_USER_GRANT_REQUEST = "USER_GRANT_REQUEST";
    public static final String NOTIFY_TYPE_USER_GRANT_RESULT = "USER_GRANT_RESULT";

    private final MemoryProjectUserGrantMapper grantMapper;
    private final MemoryProjectMemberMapper memberMapper;
    private final MemoryNotificationMapper notificationMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;

    /**
     * 项目主动授权个人（项目 owner/admin 发起 → 立即 ACTIVE）。
     * 若该个人已有一份 USER 发起的 PENDING 申请，则等同审批通过（PENDING→ACTIVE）。
     */
    public MemoryProjectUserGrantVO grantByProject(Long projectId, Long granteeUserId, Long operatorId) {
        if (granteeUserId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 必填");
        }
        if (!isOwnerOrAdmin(projectId, operatorId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅项目 owner/admin 可授权个人");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        if (userMapper.selectById(granteeUserId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        MemoryProjectUserGrant existing = findActiveRow(projectId, granteeUserId);
        OffsetDateTime now = OffsetDateTime.now();
        if (existing != null) {
            switch (existing.getStatus()) {
                case MemoryProjectUserGrant.STATUS_ACTIVE ->
                        throw new BusinessException(ErrorCode.CONFLICT, "已授权该用户，无需重复授权");
                case MemoryProjectUserGrant.STATUS_PENDING, MemoryProjectUserGrant.STATUS_REJECTED, MemoryProjectUserGrant.STATUS_REVOKED -> {
                    // 项目是权威方：PENDING（含用户申请）→ ACTIVE；REJECTED/REVOKED → 复活为 ACTIVE。不受防刷限制。
                    return activateExisting(existing, operatorId, now);
                }
                default -> throw new BusinessException(ErrorCode.CONFLICT, "授权状态异常: " + existing.getStatus());
            }
        }

        MemoryProjectUserGrant g = new MemoryProjectUserGrant();
        g.setProjectId(projectId);
        g.setUserId(granteeUserId);
        g.setInitiatedBy(MemoryProjectUserGrant.INITIATED_BY_PROJECT);
        g.setGrantedBy(operatorId);
        g.setApprovedBy(operatorId);
        g.setApprovedAt(now);
        g.setStatus(MemoryProjectUserGrant.STATUS_ACTIVE);
        grantMapper.insert(g);
        log.info("项目授权个人 grantId={} project={} grantee={} operatorId={}（项目主动，立即 ACTIVE）",
                g.getId(), projectId, granteeUserId, operatorId);
        notifyGrantee(g, project.getName(), "项目「" + project.getName() + "」已授权你召回其记忆条目，可在「召回范围」勾选该项目");
        return toVO(g);
    }

    /**
     * 个人申请召回某项目（本人发起 → PENDING + 通知项目 owner/admin）。
     * 同对活行：PENDING/ACTIVE → 409；REJECTED 30 天内 → 409 防刷；REJECTED 超期/REVOKED → 同行复活 PENDING。
     */
    public MemoryProjectUserGrantVO applyByUser(Long projectId, Long operatorId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        Long granteeUserId = operatorId; // 个人申请 = 本人

        MemoryProjectUserGrant existing = findActiveRow(projectId, granteeUserId);
        if (existing != null) {
            switch (existing.getStatus()) {
                case MemoryProjectUserGrant.STATUS_PENDING ->
                        throw new BusinessException(ErrorCode.CONFLICT, "已有待审批的申请");
                case MemoryProjectUserGrant.STATUS_ACTIVE ->
                        throw new BusinessException(ErrorCode.CONFLICT, "授权已生效，无需重复申请");
                case MemoryProjectUserGrant.STATUS_REJECTED -> {
                    if (existing.getCreatedAt() != null
                            && existing.getCreatedAt().plusDays(REJECT_COOLDOWN_DAYS).isAfter(OffsetDateTime.now())) {
                        throw new BusinessException(ErrorCode.CONFLICT,
                                "曾被拒绝，" + REJECT_COOLDOWN_DAYS + " 天内不可重复申请");
                    }
                    return revivePending(existing, operatorId, project.getName());
                }
                case MemoryProjectUserGrant.STATUS_REVOKED -> {
                    return revivePending(existing, operatorId, project.getName());
                }
                default -> throw new BusinessException(ErrorCode.CONFLICT, "授权状态异常: " + existing.getStatus());
            }
        }

        MemoryProjectUserGrant g = new MemoryProjectUserGrant();
        g.setProjectId(projectId);
        g.setUserId(granteeUserId);
        g.setInitiatedBy(MemoryProjectUserGrant.INITIATED_BY_USER);
        g.setGrantedBy(operatorId);
        g.setStatus(MemoryProjectUserGrant.STATUS_PENDING);
        grantMapper.insert(g);
        log.info("个人申请授权 grantId={} project={} applicant={}", g.getId(), projectId, operatorId);
        notifyProjectManagers(g, project.getName());
        return toVO(g);
    }

    /** 我相关的授权（被授权人 或 项目侧 owner/admin，带项目/人名）。 */
    public List<MemoryProjectUserGrantVO> listMine(Long userId) {
        return grantMapper.listInvolving(userId);
    }

    /** 审批通过（项目 owner/admin）：PENDING→ACTIVE。 */
    public void approve(Long grantId, Long operatorId) {
        MemoryProjectUserGrant g = requireGrant(grantId);
        requireProjectManager(g, operatorId, "审批");
        transition(g, MemoryProjectUserGrant.STATUS_ACTIVE, operatorId);
        log.info("个人授权通过 grantId={} operatorId={}", grantId, operatorId);
        notifyGrantee(g, projectName(g.getProjectId()), "你申请召回「" + projectName(g.getProjectId()) + "」记忆条目的授权已通过，可在「召回范围」勾选该项目");
    }

    /** 审批拒绝（项目 owner/admin）：PENDING→REJECTED，30 天防刷生效。 */
    public void reject(Long grantId, Long operatorId) {
        MemoryProjectUserGrant g = requireGrant(grantId);
        requireProjectManager(g, operatorId, "审批");
        transition(g, MemoryProjectUserGrant.STATUS_REJECTED, operatorId);
        log.info("个人授权拒绝 grantId={} operatorId={}", grantId, operatorId);
        notifyGrantee(g, projectName(g.getProjectId()), "你申请召回「" + projectName(g.getProjectId()) + "」记忆条目的授权被拒绝");
    }

    /**
     * 撤销/取消：
     * <ul>
     *   <li>PENDING：申请人本人 或 项目 owner/admin → 软删（未生效无审计必要）。</li>
     *   <li>ACTIVE：项目 owner/admin 或 被授权人本人 → REVOKED（行留痕），即时断召回。</li>
     *   <li>其他状态 → 409。</li>
     * </ul>
     */
    public void revoke(Long grantId, Long operatorId) {
        MemoryProjectUserGrant g = requireGrant(grantId);
        boolean projectManager = isOwnerOrAdmin(g.getProjectId(), operatorId);
        boolean isGrantee = g.getUserId().equals(operatorId);
        boolean isApplicant = isGrantee && MemoryProjectUserGrant.INITIATED_BY_USER.equals(g.getInitiatedBy());

        if (MemoryProjectUserGrant.STATUS_PENDING.equals(g.getStatus())) {
            if (!isApplicant && !projectManager) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "仅申请人本人或项目 owner/admin 可取消待审批申请");
            }
            grantMapper.deleteById(grantId);
            log.info("个人授权取消(PENDING 软删) grantId={} operatorId={}", grantId, operatorId);
            return;
        }
        if (!MemoryProjectUserGrant.STATUS_ACTIVE.equals(g.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅生效中的授权可撤销");
        }
        if (!projectManager && !isGrantee) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅项目 owner/admin 或被授权人本人可撤销授权");
        }
        int updated = grantMapper.update(null, new LambdaUpdateWrapper<MemoryProjectUserGrant>()
                .eq(MemoryProjectUserGrant::getId, grantId)
                .eq(MemoryProjectUserGrant::getStatus, MemoryProjectUserGrant.STATUS_ACTIVE)
                .set(MemoryProjectUserGrant::getStatus, MemoryProjectUserGrant.STATUS_REVOKED));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "授权状态已被并发变更，请刷新重试");
        }
        log.info("个人授权撤销 grantId={} operatorId={} by={}", grantId, operatorId, isGrantee ? "grantee" : "project");
        notifyGrantee(g, projectName(g.getProjectId()), "你召回「" + projectName(g.getProjectId()) + "」记忆条目的授权已被撤销，召回范围将不再包含该项目");
    }

    /** 被授权人可召回的 ACTIVE 项目 id 集（召回取数实时算，revoke 即时断召回）。 */
    public List<Long> findActiveGrantedProjectIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return grantMapper.findActiveGrantedProjectIds(userId);
    }

    /**
     * 关键词检索用户（二期 P1 · 项目授权个人的被授权人选择）。
     * 仅返 id+name；第二轮 #6：空关键词 → 返默认候选（排除自己，限 20），下拉打开即有数据，
     * 非空 → LIKE name/username 限 10。任何登录用户可调。
     */
    public List<MemorySearchItemVO> searchUsers(String keyword, Long userId) {
        String q = keyword == null ? null : keyword.trim();
        LambdaQueryWrapper<User> w = new LambdaQueryWrapper<User>()
                .ne(userId != null, User::getId, userId);
        if (q == null || q.isBlank()) {
            // 第二轮 #6：空关键词默认候选（排除自己，限 20），下拉打开即有数据，不再「无数据」。
            w.last("LIMIT 20");
        } else {
            w.and(x -> x.like(User::getName, q).or().like(User::getUsername, q)).last("LIMIT 10");
        }
        return userMapper.selectList(w).stream()
                .map(u -> new MemorySearchItemVO(u.getId(),
                        (u.getName() != null && !u.getName().isBlank()) ? u.getName() : u.getUsername()))
                .toList();
    }

    /**
     * 关键词检索项目（二期 P1 · 个人申请召回的目标项目选择）。
     * 仅返 id+name，排除当前用户自建（无需向自己项目申请）；第二轮 #6：空关键词 → 返公共池默认候选，
     * 非空 → LIKE name 限 10。
     */
    public List<MemorySearchItemVO> searchProjects(String keyword, Long userId) {
        String q = keyword == null ? null : keyword.trim();
        LambdaQueryWrapper<Project> w = new LambdaQueryWrapper<Project>()
                .ne(userId != null, Project::getCreatedBy, userId);
        if (q == null || q.isBlank()) {
            // 第二轮 #6：空关键词默认返公共池项目（排除自建），下拉打开即有数据。
            w.eq(Project::getMemoryPoolPublic, true).last("LIMIT 20");
        } else {
            w.like(Project::getName, q).last("LIMIT 10");
        }
        return projectMapper.selectList(w).stream()
                .map(p -> new MemorySearchItemVO(p.getId(), p.getName()))
                .toList();
    }

    /**
     * 第二轮 #5：切换项目的记忆公共池可见性（仅项目 OWNER/ADMIN）。
     * 推入公共池后，所有人可在「申请召回」候选看到本项目并发起授权申请。
     */
    public void togglePool(Long projectId, Long operatorId, boolean publicPool) {
        if (!isOwnerOrAdmin(projectId, operatorId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅项目 owner/admin 可切换公共池");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        int updated = projectMapper.update(null, new LambdaUpdateWrapper<Project>()
                .eq(Project::getId, projectId)
                .set(Project::getMemoryPoolPublic, publicPool));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "项目状态已被并发变更，请刷新重试");
        }
        log.info("项目公共池切换 projectId={} public={} operatorId={}", projectId, publicPool, operatorId);
    }

    /**
     * 第二轮 #5：公共池候选项目（memory_pool_public=true，排除本人自建，所有人可申请召回）。
     */
    public List<MemorySearchItemVO> listPoolProjects(Long userId) {
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .eq(Project::getMemoryPoolPublic, true)
                        .ne(userId != null, Project::getCreatedBy, userId)
                        .last("LIMIT 50")).stream()
                .map(p -> new MemorySearchItemVO(p.getId(), p.getName()))
                .toList();
    }

    // ============================ 内部 ============================

    /** 把已有行（PENDING/REJECTED/REVOKED）翻转为 ACTIVE（项目主动授权/审批通过）。 */
    private MemoryProjectUserGrantVO activateExisting(MemoryProjectUserGrant row, Long operatorId, OffsetDateTime now) {
        int updated = grantMapper.update(null, new LambdaUpdateWrapper<MemoryProjectUserGrant>()
                .eq(MemoryProjectUserGrant::getId, row.getId())
                .eq(MemoryProjectUserGrant::getStatus, row.getStatus())
                .set(MemoryProjectUserGrant::getStatus, MemoryProjectUserGrant.STATUS_ACTIVE)
                .set(MemoryProjectUserGrant::getApprovedBy, operatorId)
                .set(MemoryProjectUserGrant::getApprovedAt, now));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "授权状态已被并发变更，请刷新重试");
        }
        log.info("个人授权翻转 ACTIVE grantId={} {}→ACTIVE operatorId={}", row.getId(), row.getStatus(), operatorId);
        row.setStatus(MemoryProjectUserGrant.STATUS_ACTIVE);
        row.setApprovedBy(operatorId);
        row.setApprovedAt(now);
        notifyGrantee(row, projectName(row.getProjectId()), "项目「" + projectName(row.getProjectId()) + "」已授权你召回其记忆条目，可在「召回范围」勾选该项目");
        return toVO(row);
    }

    /** REJECTED 超期 / REVOKED 同行复活 PENDING（重置发起时钟与审批痕）。 */
    private MemoryProjectUserGrantVO revivePending(MemoryProjectUserGrant row, Long operatorId, String projectName) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated = grantMapper.update(null, new LambdaUpdateWrapper<MemoryProjectUserGrant>()
                .eq(MemoryProjectUserGrant::getId, row.getId())
                .eq(MemoryProjectUserGrant::getStatus, row.getStatus())
                .set(MemoryProjectUserGrant::getStatus, MemoryProjectUserGrant.STATUS_PENDING)
                .set(MemoryProjectUserGrant::getGrantedBy, operatorId)
                .set(MemoryProjectUserGrant::getApprovedBy, null)
                .set(MemoryProjectUserGrant::getApprovedAt, null)
                .set(MemoryProjectUserGrant::getCreatedAt, now));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "授权状态已被并发变更，请刷新重试");
        }
        log.info("个人授权复活 grantId={} {}→PENDING operatorId={}", row.getId(), row.getStatus(), operatorId);
        row.setStatus(MemoryProjectUserGrant.STATUS_PENDING);
        row.setGrantedBy(operatorId);
        row.setCreatedAt(now);
        notifyProjectManagers(row, projectName);
        return toVO(row);
    }

    /** PENDING→target 条件翻转（并发安全）；approved_by/at 留痕。 */
    private void transition(MemoryProjectUserGrant g, String target, Long operatorId) {
        int updated = grantMapper.update(null, new LambdaUpdateWrapper<MemoryProjectUserGrant>()
                .eq(MemoryProjectUserGrant::getId, g.getId())
                .eq(MemoryProjectUserGrant::getStatus, MemoryProjectUserGrant.STATUS_PENDING)
                .set(MemoryProjectUserGrant::getStatus, target)
                .set(MemoryProjectUserGrant::getApprovedBy, operatorId)
                .set(MemoryProjectUserGrant::getApprovedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "授权状态已被并发变更，请刷新重试");
        }
        g.setStatus(target);
    }

    private MemoryProjectUserGrant requireGrant(Long grantId) {
        MemoryProjectUserGrant g = grantMapper.selectById(grantId);
        if (g == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "授权不存在");
        }
        return g;
    }

    private void requireProjectManager(MemoryProjectUserGrant g, Long operatorId, String action) {
        if (!isOwnerOrAdmin(g.getProjectId(), operatorId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅项目 owner/admin 可" + action);
        }
    }

    /** 通知项目全部 ACTIVE owner/admin（个人申请待审批）。 */
    private void notifyProjectManagers(MemoryProjectUserGrant g, String projectName) {
        List<MemoryProjectMember> managers = memberMapper.selectList(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, g.getProjectId())
                .eq(MemoryProjectMember::getStatus, STATUS_ACTIVE_MEMBER)
                .in(MemoryProjectMember::getRole, ROLE_OWNER, ROLE_ADMIN));
        String applicantName = userName(g.getUserId());
        for (MemoryProjectMember m : managers) {
            insertNotification(m.getUserId(), NOTIFY_TYPE_USER_GRANT_REQUEST, g.getId(),
                    "用户「" + applicantName + "」申请召回项目「" + projectName + "」的记忆条目，请到 记忆管理→个人授权 审批");
        }
    }

    /** 通知被授权人审批结果/项目主动授权/撤销。 */
    private void notifyGrantee(MemoryProjectUserGrant g, String projectName, String message) {
        insertNotification(g.getUserId(), NOTIFY_TYPE_USER_GRANT_RESULT, g.getId(), message);
    }

    private void insertNotification(Long userId, String type, Long refId, String message) {
        MemoryNotification n = new MemoryNotification();
        n.setUserId(userId);
        n.setType(type);
        n.setRefId(refId);
        n.setMessage(message);
        n.setCreatedAt(OffsetDateTime.now());
        notificationMapper.insert(n);
    }

    private String projectName(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        return p == null ? "项目#" + projectId : p.getName();
    }

    private String userName(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) return "用户#" + userId;
        return (u.getName() != null && !u.getName().isBlank()) ? u.getName() : u.getUsername();
    }

    private MemoryProjectUserGrant findActiveRow(Long projectId, Long userId) {
        return grantMapper.selectOne(new LambdaQueryWrapper<MemoryProjectUserGrant>()
                .eq(MemoryProjectUserGrant::getProjectId, projectId)
                .eq(MemoryProjectUserGrant::getUserId, userId)
                .last("LIMIT 1"));
    }

    /** ACTIVE OWNER/ADMIN（项目侧发起/审批/撤销权判据）。 */
    boolean isOwnerOrAdmin(Long projectId, Long userId) {
        MemoryProjectMember m = findMember(projectId, userId);
        return m != null && (ROLE_OWNER.equals(m.getRole()) || ROLE_ADMIN.equals(m.getRole()));
    }

    private MemoryProjectMember findMember(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            return null;
        }
        MemoryProjectMember m = memberMapper.selectOne(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, projectId)
                .eq(MemoryProjectMember::getUserId, userId));
        return m != null && STATUS_ACTIVE_MEMBER.equals(m.getStatus()) ? m : null;
    }

    private MemoryProjectUserGrantVO toVO(MemoryProjectUserGrant g) {
        return MemoryProjectUserGrantVO.builder()
                .id(g.getId())
                .projectId(g.getProjectId())
                .projectName(projectName(g.getProjectId()))
                .userId(g.getUserId())
                .userName(userName(g.getUserId()))
                .initiatedBy(g.getInitiatedBy())
                .grantedBy(g.getGrantedBy())
                .grantedByName(g.getGrantedBy() == null ? null : userName(g.getGrantedBy()))
                .approvedBy(g.getApprovedBy())
                .approvedByName(g.getApprovedBy() == null ? null : userName(g.getApprovedBy()))
                .status(g.getStatus())
                .createdAt(g.getCreatedAt())
                .approvedAt(g.getApprovedAt())
                .build();
    }
}
