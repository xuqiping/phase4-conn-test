package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.chat.dto.MemoryProjectAffectedAuthorVO;
import com.superprogrammer.chat.entity.MemoryConsolidationScope;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectSetting;
import com.superprogrammer.chat.entity.MemoryProjectUserSetting;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.mapper.MemoryConsolidationScopeMapper;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryProjectSettingMapper;
import com.superprogrammer.chat.mapper.MemoryProjectUserSettingMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计划12 · 生命周期写侧 hook（总体设计 §3.7，F-4b 读/补救侧的触发源补齐）。
 * <p>
 * 挂 {@code ProjectService} 四个写流程（create / addMember / removeMember / delete），
 * 把旧栈 {@code project_members} 的成员与生命周期事件同步到记忆新栈：
 * <ul>
 *   <li><b>项目创建</b> → {@code memory_project_members} 落 OWNER ACTIVE 行（roster/ACL/gen 矩阵源数据）。</li>
 *   <li><b>成员加入/角色变更</b> → upsert ACTIVE；DEPARTED 重加入 = 回 ACTIVE + 清 departed_at
 *       （recall_admin 保留），角色镜像旧栈。</li>
 *   <li><b>成员移除/离职</b> → 置 DEPARTED + departed_at（<b>不删行保交接</b>）+
 *       本人挂在该项目的 turns 追加 {@code departed_project_ids}（不卸载不删数据）。</li>
 *   <li><b>项目删除</b> → 全部作者 turns 追加 {@code deleted_project_ids}（<b>不移除 project_ids</b>）+
 *       曾写记忆成员收 {@code PROJECT_DELETED_AFFECTED} 通知（M1）+
 *       清项目总结(软删)/coverage/成员行/总结 scope/gen 开关（recall_acl 二期 P1 废弃，无需再清）。</li>
 * </ul>
 * <p>
 * <b>为什么 app 层清而不是 DB CASCADE</b>：V47 建表时假设 {@code REFERENCES projects(id) ON DELETE
 * CASCADE} 自动清——但 projects 是 @TableLogic 软删（UPDATE deleted=1），PG 的 ON DELETE CASCADE
 * 永远不触发，记忆侧行全部残留。故删除级联必须在 app 层显式补（本类 {@link #onProjectDeleted}）。
 * <p>
 * <b>循环依赖规避</b>：{@link MemoryLifecycleService} 已依赖 ProjectService（copy-to/restore 建项目），
 * 本 hook 只依赖 mapper、不依赖 ProjectService，故 ProjectService → 本类单向无环。
 * <p>
 * <b>偏离 plan</b>：独立新 service（承 C/D/E/I2/F-4b 隔离裁决）；角色映射旧栈 OWNER→OWNER、
 * 其余（EDITOR/VIEWER）→MEMBER（新栈 ADMIN 是 ACL 配权层，不自动授予）；
 * 设计 §3.7 只列清 summaries/coverage/members，本类补清 consolidation_scopes（否则 worker 会对已删项目
 * 复活总结）+ recall_acl/gen 开关（死行顺手清，restore 走自建新项目、旧项目永不复活）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryLifecycleHookService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DEPARTED = "DEPARTED";
    private static final String ROLE_OWNER = "OWNER";
    private static final String ROLE_MEMBER = "MEMBER";
    private static final String TYPE_PROJECT_DELETED_AFFECTED = "PROJECT_DELETED_AFFECTED";

    private final MemoryProjectMemberMapper memberMapper;
    private final MemoryTurnMapper turnMapper;
    private final MemorySummaryMapper summaryMapper;
    private final MemorySummaryCoverageMapper coverageMapper;
    private final MemoryNotificationMapper notificationMapper;
    private final MemoryConsolidationScopeMapper consolidationScopeMapper;
    private final MemoryProjectSettingMapper projectSettingMapper;
    private final MemoryProjectUserSettingMapper projectUserSettingMapper;

    /** 项目创建：owner 落新栈 ACTIVE 行。幂等（行已存在则仅确保 ACTIVE/角色）。 */
    @Transactional
    public void onProjectCreated(Long projectId, Long ownerId) {
        upsertActiveMember(projectId, ownerId, ROLE_OWNER);
    }

    /**
     * 成员加入/角色变更：新栈 upsert ACTIVE。
     * DEPARTED 重加入 = 回 ACTIVE + 清 departed_at（recall_admin 保留，不重置授权配置）。
     *
     * @param oldRole 旧栈 project_members.role（OWNER/EDITOR/VIEWER）
     */
    @Transactional
    public void onMemberAdded(Long projectId, Long userId, String oldRole) {
        upsertActiveMember(projectId, userId, mapRole(oldRole));
    }

    /**
     * 成员移除/离职（§3.7）：置 DEPARTED + departed_at（不删行保交接）+
     * 本人挂在该项目的 turns 追加 departed_project_ids（不卸载不删数据）。
     * 无成员行（hook 上线前存量项目未同步）→ 直接落 DEPARTED 行，保证「已离开项目」折叠板可见。
     */
    @Transactional
    public void onMemberDeparted(Long projectId, Long userId, String oldRole) {
        MemoryProjectMember existing = findMemberRow(projectId, userId);
        OffsetDateTime now = OffsetDateTime.now();
        if (existing == null) {
            MemoryProjectMember row = newMemberRow(projectId, userId, mapRole(oldRole), now);
            row.setStatus(STATUS_DEPARTED);
            row.setDepartedAt(now);
            memberMapper.insert(row);
        } else if (STATUS_ACTIVE.equals(existing.getStatus())) {
            existing.setStatus(STATUS_DEPARTED);
            existing.setDepartedAt(now);
            existing.setUpdatedAt(now);
            memberMapper.updateById(existing);
        } // 已 DEPARTED → 幂等跳过
        int marked = turnMapper.appendDepartedProjectToMyTurns(userId, projectId);
        log.info("成员离职 hook projectId={} userId={} departedTurns={}", projectId, userId, marked);
    }

    /**
     * 项目删除（§3.7）：全部作者 turns 追加 deleted_project_ids（不移除 project_ids）+
     * 曾写记忆成员收 PROJECT_DELETED_AFFECTED 通知 + 清项目总结(软删)/coverage/成员行/总结 scope/ACL/gen 开关。
     *
     * @param projectName 已删项目名（通知文案用，调用方软删前取）
     */
    @Transactional
    public void onProjectDeleted(Long projectId, String projectName) {
        // ① turns 标记 + 波及通知（先查作者再标——同一集合，顺序不影响结果，但先查语义更直白）
        List<MemoryProjectAffectedAuthorVO> authors = turnMapper.findAuthorsWithTurnsInProject(projectId);
        int markedTurns = turnMapper.markProjectDeletedForAllTurns(projectId);
        OffsetDateTime now = OffsetDateTime.now();
        for (MemoryProjectAffectedAuthorVO author : authors) {
            MemoryNotification n = new MemoryNotification();
            n.setUserId(author.getUserId());
            n.setType(TYPE_PROJECT_DELETED_AFFECTED);
            n.setRefId(projectId);
            n.setMessage("项目「" + projectName + "」已删除，你在其中的 " + author.getTurnCount()
                    + " 条记忆已保留，可在记忆面板「生命周期」页签拉取到自建新项目");
            n.setCreatedAt(now);
            notificationMapper.insert(n);
        }
        // ② 清项目总结（软删，仅 project_id=该项目；个人总结 project_id=NULL 不动）
        List<Long> summaryIds = summaryMapper.selectList(new LambdaQueryWrapper<MemorySummary>()
                        .eq(MemorySummary::getProjectId, projectId)
                        .select(MemorySummary::getId))
                .stream().map(MemorySummary::getId).toList();
        int summariesCleared = summaryIds.isEmpty() ? 0 : summaryMapper.softDeleteByIds(summaryIds);
        // ③ 清该项目 scope 的 coverage 行（个人 scope project_id=NULL 不动）
        int coverageCleared = coverageMapper.delete(new LambdaQueryWrapper<MemorySummaryCoverage>()
                .eq(MemorySummaryCoverage::getProjectId, projectId));
        // ④ 清成员行（该项目整体删除，不再是离职保交接场景——行随项目走）
        int membersCleared = memberMapper.delete(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, projectId));
        // ⑤ 清自动总结 scope（否则 worker 会对已删项目复活总结）
        int scopesCleared = consolidationScopeMapper.delete(new LambdaQueryWrapper<MemoryConsolidationScope>()
                .eq(MemoryConsolidationScope::getProjectId, projectId));
        // ⑥ 清 gen 开关（死行顺手清；recall_acl 二期 P1 废弃——代码已下线，表随 V67 DROP，无需再清）
        int settingsCleared = projectSettingMapper.delete(new LambdaQueryWrapper<MemoryProjectSetting>()
                .eq(MemoryProjectSetting::getProjectId, projectId))
                + projectUserSettingMapper.delete(new LambdaQueryWrapper<MemoryProjectUserSetting>()
                .eq(MemoryProjectUserSetting::getProjectId, projectId));
        log.info("项目删除 hook projectId={} markedTurns={} notified={} summaries={} coverage={} members={} scopes={} settings={}",
                projectId, markedTurns, authors.size(), summariesCleared, coverageCleared,
                membersCleared, scopesCleared, settingsCleared);
    }

    // ---- 内部 ----

    /** upsert ACTIVE 成员行：无行插入；有行回 ACTIVE + 清 departed_at + 镜像角色（recall_admin 保留）。 */
    private void upsertActiveMember(Long projectId, Long userId, String role) {
        MemoryProjectMember existing = findMemberRow(projectId, userId);
        OffsetDateTime now = OffsetDateTime.now();
        if (existing == null) {
            memberMapper.insert(newMemberRow(projectId, userId, role, now));
            log.info("新栈成员行落库 projectId={} userId={} role={}", projectId, userId, role);
            return;
        }
        boolean dirty = !STATUS_ACTIVE.equals(existing.getStatus()) || !role.equals(existing.getRole());
        if (dirty) {
            // 走 UpdateWrapper 而非 updateById：NOT_NULL 策略会丢 departed_at=null（重加入清离职时间，IT 暴露）
            memberMapper.update(null, new LambdaUpdateWrapper<MemoryProjectMember>()
                    .eq(MemoryProjectMember::getId, existing.getId())
                    .set(MemoryProjectMember::getRole, role)
                    .set(MemoryProjectMember::getStatus, STATUS_ACTIVE)
                    .set(MemoryProjectMember::getDepartedAt, null)
                    .set(MemoryProjectMember::getUpdatedAt, now));
            log.info("新栈成员行回 ACTIVE projectId={} userId={} role={}", projectId, userId, role);
        }
    }

    private MemoryProjectMember findMemberRow(Long projectId, Long userId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, projectId)
                .eq(MemoryProjectMember::getUserId, userId));
    }

    /** 旧栈角色 → 新栈：OWNER→OWNER，其余（EDITOR/VIEWER）→MEMBER（新栈 ADMIN 是 ACL 配权层，不自动授予）。 */
    private String mapRole(String oldRole) {
        return ROLE_OWNER.equals(oldRole) ? ROLE_OWNER : ROLE_MEMBER;
    }

    /** 无 MetaObjectHandler，时间戳手填（承 MemoryLifecycleService 范式）。 */
    private MemoryProjectMember newMemberRow(Long projectId, Long userId, String role, OffsetDateTime now) {
        MemoryProjectMember row = new MemoryProjectMember();
        row.setProjectId(projectId);
        row.setUserId(userId);
        row.setRole(role);
        row.setRecallAdmin(false);
        row.setStatus(STATUS_ACTIVE);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }
}
