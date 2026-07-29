package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.chat.dto.MemoryLifecycleActionVO;
import com.superprogrammer.chat.dto.MemoryLifecycleProjectVO;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.project.dto.ProjectCreateRequest;
import com.superprogrammer.project.dto.ProjectVO;
import com.superprogrammer.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计划12 · F-4b 前置 · 生命周期折叠板 service（总体设计 §3.7，C/E 收尾遗漏补齐）。
 * <p>
 * <b>已离开项目（departed）</b>：列表源 = {@code memory_project_members} 本人 DEPARTED 行。
 * copy-to 补救 = 自建新项目 Q，把本人挂在原项目 P 的 turns 追加挂 Q（<b>copy 非 move</b>：
 * 原 P 挂载保留、departed_project_ids 不动、原项目数据零改动、别的成员照常召回）。
 * <p>
 * <b>已删除项目（deleted）</b>：列表源 = 本人 turns 的 {@code deleted_project_ids} 引用。
 * restore 补救 = 自建新项目 Q，本人 turns 移出 deleted_project_ids 的 P + 重挂 Q
 * （<b>仅拉 turn 不拉 summary</b>——项目总结行在项目删除时已 CASCADE 清）；拉完把本人
 * {@code PROJECT_DELETED_AFFECTED} 波及通知置 resolved（badge 消）。
 * <p>
 * <b>权边界</b>（向量 7 IDOR）：两个写动作全程 wrapper 强制 {@code user_id=self}，
 * 只动本人 turns；copy-to 前置校验本人确为该项目 DEPARTED 成员，否则 403。
 * <p>
 * <b>偏离 plan</b>：独立新 service（非改 legacy MemoryService，承 C/D/E/I2 隔离裁决）；
 * 新项目创建复用 {@link ProjectService#create}（项目 CRUD 单一出口，自动落旧 project_members
 * owner 行），再补 {@code memory_project_members} OWNER 行供新栈召回 ACL 判定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryLifecycleService {

    private static final String STATUS_DEPARTED = "DEPARTED";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String ROLE_OWNER = "OWNER";
    private static final String TYPE_PROJECT_DELETED_AFFECTED = "PROJECT_DELETED_AFFECTED";
    /** projects.name VARCHAR(100) 上限。 */
    private static final int PROJECT_NAME_CAP = 100;

    private final MemoryProjectMemberMapper memberMapper;
    private final MemoryTurnMapper turnMapper;
    private final MemoryNotificationMapper notificationMapper;
    private final ProjectService projectService;

    /** 本人已离开项目列表（DEPARTED membership + 可拉取 turn 计数）。 */
    public List<MemoryLifecycleProjectVO> listDepartedProjects(Long userId) {
        return memberMapper.findMyDepartedProjects(userId);
    }

    /** 本人已删除项目列表（deleted_project_ids 引用 + 待拉取 turn 计数）。 */
    public List<MemoryLifecycleProjectVO> listDeletedProjects(Long userId) {
        return turnMapper.findMyDeletedProjects(userId);
    }

    /**
     * copy-to：已离开项目记忆拉取到自建新项目（§3.7 line159，copy 非 move）。
     *
     * @param userId       当前用户（须为该项目 DEPARTED 成员）
     * @param projectId    已离开的原项目
     * @param projectName  新项目名（可空走默认命名）
     */
    @Transactional
    public MemoryLifecycleActionVO copyDepartedProjectTo(Long userId, Long projectId, String projectName) {
        MemoryProjectMember membership = memberMapper.selectOne(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, projectId)
                .eq(MemoryProjectMember::getUserId, userId));
        if (membership == null || !STATUS_DEPARTED.equals(membership.getStatus())) {
            log.info("copy-to 越权拦截 userId={} projectId={} membership={}", userId, projectId,
                    membership == null ? "无" : membership.getStatus());
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅已离开该项目的本人可拉取记忆");
        }
        String oldName = turnMapper.findProjectNameAnyState(projectId);
        if (oldName == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "原项目不存在");
        }
        ProjectVO newProject = createPullProject(userId, oldName, projectName);
        insertMemoryOwnerRow(newProject.getId(), userId);
        int affected = turnMapper.appendProjectToMyTurns(userId, projectId, newProject.getId());
        log.info("copy-to 完成 userId={} fromProject={} newProject={} affectedTurns={}",
                userId, projectId, newProject.getId(), affected);
        return MemoryLifecycleActionVO.builder()
                .newProjectId(newProject.getId())
                .newProjectName(newProject.getName())
                .affectedTurns(affected)
                .build();
    }

    /**
     * restore：已删除项目记忆拉取到自建新项目（§3.7 line165，仅拉 turn 不拉 summary）。
     * 拉取成功后将本人该项目的 PROJECT_DELETED_AFFECTED 波及通知置 resolved（badge 消）。
     *
     * @param userId       当前用户
     * @param projectId    已删除的原项目
     * @param projectName  新项目名（可空走默认命名）
     */
    @Transactional
    public MemoryLifecycleActionVO restoreDeletedProject(Long userId, Long projectId, String projectName) {
        int pending = turnMapper.countMyTurnsInDeletedProject(userId, projectId);
        if (pending == 0) {
            // 不区分「无记忆」与「已处理」（防存在性探测），统一 NOT_FOUND
            throw new BusinessException(ErrorCode.NOT_FOUND, "该项目下无待拉取的记忆");
        }
        String oldName = turnMapper.findProjectNameAnyState(projectId);
        ProjectVO newProject = createPullProject(userId, oldName, projectName);
        insertMemoryOwnerRow(newProject.getId(), userId);
        int affected = turnMapper.restoreMyTurnsFromDeletedProject(userId, projectId, newProject.getId());
        int resolved = notificationMapper.update(null,
                new LambdaUpdateWrapper<MemoryNotification>()
                        .eq(MemoryNotification::getUserId, userId)
                        .eq(MemoryNotification::getType, TYPE_PROJECT_DELETED_AFFECTED)
                        .eq(MemoryNotification::getRefId, projectId)
                        .isNull(MemoryNotification::getResolvedAt)
                        .set(MemoryNotification::getResolvedAt, OffsetDateTime.now()));
        log.info("restore 完成 userId={} deletedProject={} newProject={} affectedTurns={} resolvedNotices={}",
                userId, projectId, newProject.getId(), affected, resolved);
        return MemoryLifecycleActionVO.builder()
                .newProjectId(newProject.getId())
                .newProjectName(newProject.getName())
                .affectedTurns(affected)
                .build();
    }

    /** 建拉取用新项目（复用 ProjectService 单一出口）；空名走「「原项目名」记忆拉取」默认命名，截 100 字符。 */
    private ProjectVO createPullProject(Long userId, String oldName, String requestedName) {
        String name = (requestedName != null && !requestedName.isBlank())
                ? requestedName.trim()
                : "「" + (oldName == null ? "原项目" : oldName) + "」记忆拉取";
        if (name.length() > PROJECT_NAME_CAP) {
            name = name.substring(0, PROJECT_NAME_CAP);
        }
        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setName(name);
        return projectService.create(req, userId);
    }

    /** 新栈成员行：拉取者为新项目 OWNER（recall ACL 判定/花名册源数据）。无 MetaObjectHandler，时间戳手填。 */
    private void insertMemoryOwnerRow(Long projectId, Long userId) {
        MemoryProjectMember owner = new MemoryProjectMember();
        owner.setProjectId(projectId);
        owner.setUserId(userId);
        owner.setRole(ROLE_OWNER);
        owner.setRecallAdmin(false);
        owner.setStatus(STATUS_ACTIVE);
        owner.setCreatedAt(OffsetDateTime.now());
        owner.setUpdatedAt(owner.getCreatedAt());
        memberMapper.insert(owner);
    }
}
