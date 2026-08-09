package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.chat.dto.MemoryProjectLinkVO;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.entity.MemoryProjectLink;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemoryProjectLinkMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
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
 * 记忆二期 P2 · 项目授权状态机（FR-101/103）。
 * <p>
 * 生命周期：child owner 发起 → PENDING + 通知 parent owner/admin → 通过 ACTIVE / 拒绝 REJECTED；
 * 双方可撤 ACTIVE → REVOKED；child 可取消自己 PENDING（软删）。
 * <b>不变量</b>：
 * <ul>
 *   <li>同 (parent,child) 仅一条活行（DB 部分唯一索引兜底）；REJECTED 30 天内重发 → 409 防刷
 *       （按 created_at 判）；REJECTED 超 30 天 / REVOKED 再发起 = 同行复活 PENDING 并重置时钟。</li>
 *   <li>状态翻转全走条件 UPDATE（{@code WHERE status=:expected}），影响行数=0 → 409（并发打不穿）。</li>
 *   <li>单级不传递：召回只查一跳（{@code findActiveChildIds} 不递归）。</li>
 *   <li>写不穿透：本服务只管链；条目写入/审核端点不感知 links。</li>
 * </ul>
 * 权边界（内建 service，防 controller 漏判——承 P1 决策 3）：发起=child OWNER；
 * 审批=parent OWNER/ADMIN；撤销=child OWNER 或 parent OWNER/ADMIN；列表=任一侧 OWNER/ADMIN。
 * 审计：每步 log.info；REVOKED 行留痕不删。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryProjectLinkService {

    private static final String ROLE_OWNER = "OWNER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String STATUS_ACTIVE_MEMBER = "ACTIVE";

    /** REJECTED 防刷窗口（FR-101：同对拒绝后 30 天不可重复发起）。 */
    static final int REJECT_COOLDOWN_DAYS = 30;

    public static final String NOTIFY_TYPE_LINK_REQUEST = "LINK_REQUEST";
    public static final String NOTIFY_TYPE_LINK_RESULT = "LINK_RESULT";

    private final MemoryProjectLinkMapper linkMapper;
    private final MemoryProjectMemberMapper memberMapper;
    private final MemoryNotificationMapper notificationMapper;
    private final ProjectMapper projectMapper;
    private final com.superprogrammer.chat.mapper.MemorySummaryMapper summaryMapper;

    /**
     * 发起授权（child owner；body=parentId）。落 PENDING + 通知 parent owner/admin。
     * 同对活行：PENDING/ACTIVE → 409；REJECTED 30 天内 → 409 防刷；REJECTED 超期/REVOKED → 同行复活。
     */
    public MemoryProjectLinkVO request(Long childProjectId, Long parentProjectId, Long operatorId) {
        if (parentProjectId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "parentProjectId 必填");
        }
        if (childProjectId.equals(parentProjectId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能授权给本项目自身");
        }
        if (!isOwner(childProjectId, operatorId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅授权方项目 owner 可发起授权");
        }
        Project parent = projectMapper.selectById(parentProjectId);
        if (parent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目标项目不存在");
        }

        MemoryProjectLink existing = findActiveRow(parentProjectId, childProjectId);
        if (existing != null) {
            switch (existing.getStatus()) {
                case MemoryProjectLink.STATUS_PENDING ->
                        throw new BusinessException(ErrorCode.CONFLICT, "已有待审批的授权申请");
                case MemoryProjectLink.STATUS_ACTIVE ->
                        throw new BusinessException(ErrorCode.CONFLICT, "授权已生效，无需重复发起");
                case MemoryProjectLink.STATUS_REJECTED -> {
                    if (existing.getCreatedAt() != null
                            && existing.getCreatedAt().plusDays(REJECT_COOLDOWN_DAYS).isAfter(OffsetDateTime.now())) {
                        throw new BusinessException(ErrorCode.CONFLICT,
                                "曾被拒绝，" + REJECT_COOLDOWN_DAYS + " 天内不可重复发起");
                    }
                    return revive(existing, operatorId);
                }
                case MemoryProjectLink.STATUS_REVOKED -> {
                    return revive(existing, operatorId);
                }
                default -> throw new BusinessException(ErrorCode.CONFLICT, "授权链状态异常: " + existing.getStatus());
            }
        }

        MemoryProjectLink link = new MemoryProjectLink();
        link.setParentProjectId(parentProjectId);
        link.setChildProjectId(childProjectId);
        link.setGrantedBy(operatorId);
        link.setStatus(MemoryProjectLink.STATUS_PENDING);
        linkMapper.insert(link);
        log.info("项目授权发起 linkId={} child={} parent={} operatorId={}", link.getId(), childProjectId, parentProjectId, operatorId);
        notifyParentManagers(link, parent.getName());
        return toVO(link, parent.getName(), projectName(childProjectId));
    }

    /** REJECTED 超期 / REVOKED 同行复活 PENDING（重置发起时钟与审批痕）。 */
    private MemoryProjectLinkVO revive(MemoryProjectLink row, Long operatorId) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated = linkMapper.update(null, new LambdaUpdateWrapper<MemoryProjectLink>()
                .eq(MemoryProjectLink::getId, row.getId())
                .eq(MemoryProjectLink::getStatus, row.getStatus())
                .set(MemoryProjectLink::getStatus, MemoryProjectLink.STATUS_PENDING)
                .set(MemoryProjectLink::getGrantedBy, operatorId)
                .set(MemoryProjectLink::getApprovedBy, null)
                .set(MemoryProjectLink::getApprovedAt, null)
                .set(MemoryProjectLink::getCreatedAt, now));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "授权状态已被并发变更，请刷新重试");
        }
        log.info("项目授权复活 linkId={} {}→PENDING operatorId={}", row.getId(), row.getStatus(), operatorId);
        row.setStatus(MemoryProjectLink.STATUS_PENDING);
        row.setGrantedBy(operatorId);
        row.setCreatedAt(now);
        notifyParentManagers(row, projectName(row.getParentProjectId()));
        return toVO(row, projectName(row.getParentProjectId()), projectName(row.getChildProjectId()));
    }

    /** 我相关的授权链（任一侧 ACTIVE owner/admin 可见，带双方项目名）。 */
    public List<MemoryProjectLinkVO> listMine(Long userId) {
        return linkMapper.listInvolving(userId);
    }

    /** 审批通过（parent owner/admin）：PENDING→ACTIVE，条件 UPDATE 防并发。 */
    public void approve(Long linkId, Long operatorId) {
        MemoryProjectLink link = requireLink(linkId);
        requireParentManager(link, operatorId, "审批");
        transition(link, MemoryProjectLink.STATUS_ACTIVE, operatorId);
        log.info("项目授权通过 linkId={} operatorId={}", linkId, operatorId);
        notifyRequester(link, true);
    }

    /** 审批拒绝（parent owner/admin）：PENDING→REJECTED，30 天防刷生效。 */
    public void reject(Long linkId, Long operatorId) {
        MemoryProjectLink link = requireLink(linkId);
        requireParentManager(link, operatorId, "审批");
        transition(link, MemoryProjectLink.STATUS_REJECTED, operatorId);
        log.info("项目授权拒绝 linkId={} operatorId={}", linkId, operatorId);
        notifyRequester(link, false);
    }

    /**
     * 撤销/取消：child owner 可撤（ACTIVE→REVOKED 或 PENDING 取消=软删）；
     * parent owner/admin 可撤 ACTIVE。其他身份/状态 → 403/409。
     */
    public void revoke(Long linkId, Long operatorId) {
        MemoryProjectLink link = requireLink(linkId);
        boolean childOwner = isOwner(link.getChildProjectId(), operatorId);
        boolean parentManager = isOwnerOrAdmin(link.getParentProjectId(), operatorId);
        if (MemoryProjectLink.STATUS_PENDING.equals(link.getStatus())) {
            if (!childOwner) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "仅授权方项目 owner 可取消待审批申请");
            }
            linkMapper.deleteById(linkId);
            log.info("项目授权取消(PENDING 软删) linkId={} operatorId={}", linkId, operatorId);
            return;
        }
        if (!MemoryProjectLink.STATUS_ACTIVE.equals(link.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅生效中的授权可撤销");
        }
        if (!childOwner && !parentManager) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅双方项目 owner/admin 可撤销授权");
        }
        int updated = linkMapper.update(null, new LambdaUpdateWrapper<MemoryProjectLink>()
                .eq(MemoryProjectLink::getId, linkId)
                .eq(MemoryProjectLink::getStatus, MemoryProjectLink.STATUS_ACTIVE)
                .set(MemoryProjectLink::getStatus, MemoryProjectLink.STATUS_REVOKED));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "授权状态已被并发变更，请刷新重试");
        }
        // 二期 P4（FR-303）：撤销授权 → parent 共享总结中 provenance 含 child 条目的标 STALE；
        // worker 重压取数=当前 ACTIVE 链实时算 → 重压后不含 child 内容（坑点预判③）
        int stale = summaryMapper.markProjectSharedStaleByChildEntries(
                link.getParentProjectId(), link.getChildProjectId());
        if (stale > 0) {
            log.info("授权撤销级联：parent={} 共享总结 {} 条标 STALE（含 child={} 条目 provenance）",
                    link.getParentProjectId(), stale, link.getChildProjectId());
        }
        log.info("项目授权撤销 linkId={} operatorId={} by={}", linkId, operatorId, childOwner ? "child" : "parent");
        notifyRequester(link, null);
    }

    /** 一批 parent 项目的 ACTIVE child 集（召回合流用；单级一跳不递归）。 */
    public List<Long> findActiveChildIds(List<Long> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return List.of();
        }
        return linkMapper.findActiveChildIds(parentIds);
    }

    /**
     * 三期：userId 作为某 ACTIVE link 的 parent 成员时，可只读召回/查看的那些 child 项目 id 集
     * （总结视图读权用——被授权方可看授权方共享总结，呼应 Style A：link ACTIVE 即生效读，不另建 grant）。
     * 实现 = userId 的 ACTIVE 项目（其作为 parent）→ 这些 parent 的 ACTIVE child（单级一跳，不递归）。
     */
    public List<Long> findReadableChildProjectIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<Long> myProjects = memberMapper.selectList(new LambdaQueryWrapper<MemoryProjectMember>()
                        .select(MemoryProjectMember::getProjectId)
                        .eq(MemoryProjectMember::getUserId, userId)
                        .eq(MemoryProjectMember::getStatus, STATUS_ACTIVE_MEMBER))
                .stream().map(MemoryProjectMember::getProjectId).distinct().toList();
        return findActiveChildIds(myProjects);
    }

    // ============================ 内部 ============================

    /** PENDING→target 条件翻转（并发安全）；approved_by/at 留痕。 */
    private void transition(MemoryProjectLink link, String target, Long operatorId) {
        int updated = linkMapper.update(null, new LambdaUpdateWrapper<MemoryProjectLink>()
                .eq(MemoryProjectLink::getId, link.getId())
                .eq(MemoryProjectLink::getStatus, MemoryProjectLink.STATUS_PENDING)
                .set(MemoryProjectLink::getStatus, target)
                .set(MemoryProjectLink::getApprovedBy, operatorId)
                .set(MemoryProjectLink::getApprovedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "授权状态已被并发变更，请刷新重试");
        }
        link.setStatus(target);
    }

    private MemoryProjectLink requireLink(Long linkId) {
        MemoryProjectLink link = linkMapper.selectById(linkId);
        if (link == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "授权链不存在");
        }
        return link;
    }

    private void requireParentManager(MemoryProjectLink link, Long operatorId, String action) {
        if (!isOwnerOrAdmin(link.getParentProjectId(), operatorId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅被授权方项目 owner/admin 可" + action);
        }
    }

    /** 通知 parent 全部 ACTIVE owner/admin（待审批）。 */
    private void notifyParentManagers(MemoryProjectLink link, String parentName) {
        List<MemoryProjectMember> managers = memberMapper.selectList(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, link.getParentProjectId())
                .eq(MemoryProjectMember::getStatus, STATUS_ACTIVE_MEMBER)
                .in(MemoryProjectMember::getRole, ROLE_OWNER, ROLE_ADMIN));
        String childName = projectName(link.getChildProjectId());
        for (MemoryProjectMember m : managers) {
            insertNotification(m.getUserId(), NOTIFY_TYPE_LINK_REQUEST, link.getId(),
                    "项目「" + childName + "」申请将其记忆条目授权给「" + parentName + "」召回，请到 记忆管理→项目授权 审批");
        }
    }

    /** 通知发起人审批结果/撤销（approved=null=被撤销）。 */
    private void notifyRequester(MemoryProjectLink link, Boolean approved) {
        String childName = projectName(link.getChildProjectId());
        String parentName = projectName(link.getParentProjectId());
        String msg = approved == null
                ? "项目「" + childName + "」对「" + parentName + "」的记忆授权已被撤销"
                : Boolean.TRUE.equals(approved)
                ? "项目「" + childName + "」的记忆授权已通过，「" + parentName + "」成员可召回到条目"
                : "项目「" + childName + "」对「" + parentName + "」的记忆授权被拒绝";
        insertNotification(link.getGrantedBy(), NOTIFY_TYPE_LINK_RESULT, link.getId(), msg);
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

    private MemoryProjectLink findActiveRow(Long parentProjectId, Long childProjectId) {
        return linkMapper.selectOne(new LambdaQueryWrapper<MemoryProjectLink>()
                .eq(MemoryProjectLink::getParentProjectId, parentProjectId)
                .eq(MemoryProjectLink::getChildProjectId, childProjectId)
                .last("LIMIT 1"));
    }

    /** ACTIVE OWNER（发起权判据——FR-101 child owner）。 */
    boolean isOwner(Long projectId, Long userId) {
        MemoryProjectMember m = findMember(projectId, userId);
        return m != null && ROLE_OWNER.equals(m.getRole());
    }

    /** ACTIVE OWNER/ADMIN（审批/撤销权判据）。 */
    boolean isOwnerOrAdmin(Long projectId, Long userId) {
        MemoryProjectMember m = findMember(projectId, userId);
        return m != null && (ROLE_OWNER.equals(m.getRole()) || ROLE_ADMIN.equals(m.getRole()));
    }

    /** P4（FR-302/303）：ACTIVE 成员判定（成员个人压缩通道 + 共享总结读咽喉）。 */
    boolean isActiveMember(Long projectId, Long userId) {
        return findMember(projectId, userId) != null;
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

    private MemoryProjectLinkVO toVO(MemoryProjectLink link, String parentName, String childName) {
        return MemoryProjectLinkVO.builder()
                .id(link.getId())
                .parentProjectId(link.getParentProjectId())
                .parentProjectName(parentName)
                .childProjectId(link.getChildProjectId())
                .childProjectName(childName)
                .grantedBy(link.getGrantedBy())
                .approvedBy(link.getApprovedBy())
                .status(link.getStatus())
                .createdAt(link.getCreatedAt())
                .approvedAt(link.getApprovedAt())
                .build();
    }
}
