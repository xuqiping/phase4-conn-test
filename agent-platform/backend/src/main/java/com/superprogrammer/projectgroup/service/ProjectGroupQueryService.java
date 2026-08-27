package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import com.superprogrammer.chat.entity.ChatMessage;
import com.superprogrammer.chat.mapper.ChatMessageMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.projectgroup.dto.ProjectGroupDetailVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupLedgerRowVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupOverviewVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupOutputVO;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupLedgerMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目组推进查询服务（计划5 Step7）：组长总览（组详情+组池流水分页）与组产出列表。
 *
 * <p>可见性（修复IV D3，17x-4 放宽后口径）：
 * <ul>
 *   <li>组长/管理/admin：overview 全量（流水全员）+ outputs 可看全部成员行、可按 memberUserId 筛选。</li>
 *   <li>普通成员：overview 可开（组织信息可见）——流水仅本人行且 balanceAfter 置 null（余额=管理数据）；
 *       outputs 仅自己的行（忽略传入筛选强制 self）。</li>
 *   <li>非成员：两接口均 403。</li>
 * </ul>
 *
 * <p>数据口径：outputs 以 llm_usage_logs.project_group_id 为源（组维度消耗真源，
 * CONSUME/BACKSTOP 均有行）；媒体概要批查 media_gen_tasks 补齐（避免 SQL JOIN 大宽行）。
 * 产物文件不透出——媒体下载端点归属门控保持不变，本人行前端可跳任务详情预览。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGroupQueryService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ProjectGroupMapper groupMapper;
    private final ProjectGroupMemberMapper memberMapper;
    private final ProjectGroupLedgerMapper ledgerMapper;
    private final LlmUsageLogMapper usageLogMapper;
    private final MediaGenTaskMapper mediaTaskMapper;
    private final UserMapper userMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ProjectGroupService groupService;
    private final ProjectGroupVisibilityService visibilityService;

    /**
     * 组总览：组详情（getDetail 复用，MEMBER+ 可开）+ 组池流水倒序分页（actor 用户名批量补齐）。
     * 修复IV D3（17x-4）：普通成员同口径裁剪——流水只看本人行、balanceAfter 不透出（余额=管理数据）。
     * 修复V B1（17x#1）：管理视角新增流水筛选（keyword/type/actorUserId/时间）——keyword 匹配
     * 流水备注 ∪ 操作人账号/姓名/备注；MEMBER 路径忽略筛选维持仅本人行（后端强制，不信任前端）。
     */
    public ProjectGroupOverviewVO overview(Long groupId, Long actorUserId, boolean admin,
                                           String keyword, String type, Long actorUserIdFilter,
                                           OffsetDateTime from, OffsetDateTime to,
                                           int page, int size) {
        ProjectGroupDetailVO detail = groupService.getDetail(groupId, actorUserId, admin);
        final boolean managerView = managerViewOf(groupId, actorUserId, admin);

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        LambdaQueryWrapper<ProjectGroupLedgerEntity> lw = baseLedgerWrapper(groupId);
        if (!managerView) {
            // 修复IV D3（17x-4，决策 6）：普通成员流水=仅本人行（筛选参数不生效——无筛选 UI 也不信前端）
            lw.eq(ProjectGroupLedgerEntity::getActorUserId, actorUserId);
        } else {
            applyLedgerFilter(lw, normalizeFilter(keyword, type, actorUserIdFilter, from, to));
        }
        Page<ProjectGroupLedgerEntity> p = ledgerMapper.selectPage(
                new Page<>(safePage, safeSize), lw.orderByDesc(ProjectGroupLedgerEntity::getId));
        List<ProjectGroupLedgerEntity> rows = p.getRecords();

        Map<Long, String> names = usernameMap(rows.stream()
                .map(ProjectGroupLedgerEntity::getActorUserId)
                .filter(Objects::nonNull)
                .toList());

        List<ProjectGroupLedgerRowVO> vos = rows.stream()
                .map(l -> new ProjectGroupLedgerRowVO(
                        l.getId(), l.getCreatedAt(), l.getActorUserId(),
                        l.getActorUserId() != null ? names.get(l.getActorUserId()) : null,
                        l.getType(), l.getDeltaPoints(),
                        managerView ? l.getBalanceAfter() : null, // 修复IV D3：成员视角余额不透出
                        l.getRefType(), l.getRefId(), l.getRemark()))
                .toList();
        return new ProjectGroupOverviewVO(detail,
                PageResult.of(vos, p.getTotal(), safePage, safeSize));
    }

    // ==================== 修复V B1/B2（17x#1）：流水筛选 + CSV 导出 ====================

    /** 14 种 CHECK 枚举白名单（type 筛选非法值直接 400）。 */
    private static final Set<String> LEDGER_TYPES = Set.of(
            ProjectGroupLedgerEntity.TYPE_ALLOCATE, ProjectGroupLedgerEntity.TYPE_RECLAIM,
            ProjectGroupLedgerEntity.TYPE_CONSUME, ProjectGroupLedgerEntity.TYPE_REFUND,
            ProjectGroupLedgerEntity.TYPE_ADMIN_ADJUST, ProjectGroupLedgerEntity.TYPE_BACKSTOP,
            ProjectGroupLedgerEntity.TYPE_MEMBER_ALLOCATE, ProjectGroupLedgerEntity.TYPE_MEMBER_RECLAIM,
            ProjectGroupLedgerEntity.TYPE_MEMBER_QUOTA_ADJUST, ProjectGroupLedgerEntity.TYPE_SELF_ALLOCATE,
            ProjectGroupLedgerEntity.TYPE_SELF_CONSUME, ProjectGroupLedgerEntity.TYPE_SELF_REFUND,
            ProjectGroupLedgerEntity.TYPE_SELF_REPAY, ProjectGroupLedgerEntity.TYPE_DEBT_WRITEOFF);

    /** 导出硬顶（容量护栏）：超限截断 + CSV 尾注记；改值只改这一处（plan 运维口径）。 */
    static final int EXPORT_ROW_LIMIT = 50_000;

    /** 流水类型 → CSV 中文标签（与前端 LEDGER_TYPE 六项口径一致 + 成员腿八项；前端映射表见
     * ProjectGroupsView.vue LEDGER_TYPE——两份映射分属两端无共享层，改动需同步，见修复V plan 坑表）。 */
    private static final Map<String, String> TYPE_LABEL = Map.ofEntries(
            Map.entry(ProjectGroupLedgerEntity.TYPE_ALLOCATE, "划入"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_RECLAIM, "回收"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_CONSUME, "消耗"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_REFUND, "退款"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_ADMIN_ADJUST, "调整"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_BACKSTOP, "兜底"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_MEMBER_ALLOCATE, "配额划入"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_MEMBER_RECLAIM, "配额回收"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_MEMBER_QUOTA_ADJUST, "限额调整"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_SELF_ALLOCATE, "名下划入"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_SELF_CONSUME, "名下消耗"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_SELF_REFUND, "名下退款"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_SELF_REPAY, "名下还款"),
            Map.entry(ProjectGroupLedgerEntity.TYPE_DEBT_WRITEOFF, "欠款核销"));

    /** 流水筛选参数包（overview / export 共用）。 */
    public record LedgerFilter(String keyword, String type, Long actorUserId,
                               OffsetDateTime from, OffsetDateTime to) {}

    /** 组装基础 wrapper（group 维度）；筛选条件由 {@link #applyLedgerFilter} 追加（两入口共用防漂移）。 */
    private LambdaQueryWrapper<ProjectGroupLedgerEntity> baseLedgerWrapper(Long groupId) {
        return new LambdaQueryWrapper<ProjectGroupLedgerEntity>()
                .eq(ProjectGroupLedgerEntity::getGroupId, groupId);
    }

    /** type 白名单校验 + keyword 去空归一（空串→null）。 */
    private LedgerFilter normalizeFilter(String keyword, String type, Long actorUserId,
                                         OffsetDateTime from, OffsetDateTime to) {
        String t = type == null || type.isBlank() ? null : type.trim();
        if (t != null && !LEDGER_TYPES.contains(t)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法流水类型: " + t);
        }
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return new LedgerFilter(kw, t, actorUserId, from, to);
    }

    /**
     * 筛选条件组装（管理视角专用）。keyword 匹配流水 remark ∪ 操作人账号/姓名/备注——
     * 操作人侧走 users 子查询（inSql 无参数绑定能力，改用 .apply {0} 占位符全参数化；
     * users 量级小全扫，E 轮 remark 筛选同口径）；LIKE 值先 escapeLikeKeyword 转义
     * （\ % _ 各自转义，ESCAPE '\' 显式声明——PG 标准转义）。
     */
    private void applyLedgerFilter(LambdaQueryWrapper<ProjectGroupLedgerEntity> lw, LedgerFilter f) {
        if (f == null) {
            return;
        }
        if (f.type() != null) {
            lw.eq(ProjectGroupLedgerEntity::getType, f.type());
        }
        if (f.actorUserId() != null) {
            lw.eq(ProjectGroupLedgerEntity::getActorUserId, f.actorUserId());
        }
        if (f.from() != null) {
            lw.ge(ProjectGroupLedgerEntity::getCreatedAt, f.from());
        }
        if (f.to() != null) {
            lw.le(ProjectGroupLedgerEntity::getCreatedAt, f.to());
        }
        if (f.keyword() != null) {
            String esc = escapeLikeKeyword(f.keyword());
            lw.and(w -> w.like(ProjectGroupLedgerEntity::getRemark, esc)
                    // 子查询 SQL 框架静态、三处 LIKE 值全走 {0} 参数占位符（无字符串拼接用户输入）
                    .or().apply("actor_user_id IN (SELECT id FROM users WHERE username LIKE ('%' || {0} || '%') ESCAPE '\\'"
                            + " OR name LIKE ('%' || {0} || '%') ESCAPE '\\'"
                            + " OR remark LIKE ('%' || {0} || '%') ESCAPE '\\')", esc));
        }
    }

    /** LIKE 关键词转义（\ % _ → 前缀反斜杠），截断 50 字符（AssetMemberService:233 同款）。 */
    private String escapeLikeKeyword(String keyword) {
        String trimmed = keyword.trim();
        if (trimmed.length() > 50) {
            trimmed = trimmed.substring(0, 50);
        }
        return trimmed.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 修复V B2（17x#1，决策 2/3/4）：组池流水 CSV 导出——按当前筛选全量（不分页），
     * 上限 {@link #EXPORT_ROW_LIMIT} 行截断 + 尾注记；UTF-8 带 BOM（Excel 双击直开）。
     * 权限仅组长/MANAGER/admin——MEMBER 403（比 overview 更紧：overview 成员可读本人行，export 不给）。
     */
    public byte[] exportLedger(Long groupId, Long actorUserId, boolean admin,
                               String keyword, String type, Long actorUserIdFilter,
                               OffsetDateTime from, OffsetDateTime to) {
        if (!managerViewOf(groupId, actorUserId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅组长/管理员可导出组池流水");
        }
        LedgerFilter f = normalizeFilter(keyword, type, actorUserIdFilter, from, to);
        LambdaQueryWrapper<ProjectGroupLedgerEntity> lw = baseLedgerWrapper(groupId);
        applyLedgerFilter(lw, f);
        // LIMIT 50001：多取 1 行判截断（值为代码常量，无注入面；条件/orderBy 先组装、last 永远最后）
        List<ProjectGroupLedgerEntity> rows = ledgerMapper.selectList(
                lw.orderByDesc(ProjectGroupLedgerEntity::getId).last("LIMIT " + (EXPORT_ROW_LIMIT + 1)));
        boolean truncated = rows.size() > EXPORT_ROW_LIMIT;
        if (truncated) {
            rows = rows.subList(0, EXPORT_ROW_LIMIT);
        }

        // 操作人三字段（账号/姓名/备注）一次批量补齐（防 N+1）
        Set<Long> actorIds = rows.stream()
                .map(ProjectGroupLedgerEntity::getActorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> users = actorIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(actorIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        DateTimeFormatter ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder(96 * (rows.size() + 2));
        sb.append('﻿'); // UTF-8 BOM（U+FEFF）：Excel 双击直开不乱码
        sb.append("时间,类型,操作人,变动积分,变动后组池余额,关联,备注\r\n");
        for (ProjectGroupLedgerEntity l : rows) {
            User u = l.getActorUserId() == null ? null : users.get(l.getActorUserId());
            sb.append(csvCell(l.getCreatedAt() == null ? "" : ts.format(l.getCreatedAt()))).append(',')
              .append(csvCell(TYPE_LABEL.getOrDefault(l.getType(), l.getType() == null ? "" : l.getType()))).append(',')
              .append(csvCell(actorCell(l.getActorUserId(), u))).append(',')
              .append(csvCell(num(l.getDeltaPoints()))).append(',')
              .append(csvCell(num(l.getBalanceAfter()))).append(',')
              .append(csvCell(l.getRefType() == null ? "" : l.getRefType() + (l.getRefId() == null ? "" : "#" + l.getRefId()))).append(',')
              .append(csvCell(l.getRemark() == null ? "" : l.getRemark()))
              .append("\r\n");
        }
        if (truncated) {
            // 截断真值另起 count 查（同筛选无分页）；仍超限的极端量级下这一次 count 也可接受
            LambdaQueryWrapper<ProjectGroupLedgerEntity> cw = baseLedgerWrapper(groupId);
            applyLedgerFilter(cw, f);
            long total = ledgerMapper.selectCount(cw);
            sb.append("# 截断：共命中 ").append(total).append(" 行，仅导出前 ").append(EXPORT_ROW_LIMIT).append(" 行\r\n");
        }
        log.info("组池流水导出: groupId={} operator={} rows={} truncated={} keyword={} type={} actor={} from={} to={}",
                groupId, actorUserId, rows.size(), truncated,
                f.keyword() == null ? "-" : f.keyword(), f.type() == null ? "-" : f.type(),
                f.actorUserId() == null ? "-" : f.actorUserId(), f.from(), f.to());
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 操作人列：姓名（账号）·备注；无姓名回落账号；无 actor（系统行）→ "-"。 */
    private String actorCell(Long actorUserId, User u) {
        if (u == null) {
            return actorUserId == null ? "-" : "#" + actorUserId;
        }
        String base = u.getName() == null || u.getName().isBlank()
                ? u.getUsername() : u.getName() + "（" + u.getUsername() + "）";
        return u.getRemark() == null || u.getRemark().isBlank() ? base : base + "·" + u.getRemark();
    }

    /** BigDecimal → 原值去尾零（1.50→1.5，0.00→0）；null → ""。 */
    private String num(java.math.BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    /** RFC4180 单元格转义：含 , " \n \r → 双引号包裹 + 内部双引号翻倍。 */
    private String csvCell(String v) {
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /** mgr 视角判定抽单点（overview 裁剪与 export 403 同源，防双处漂移——修复V plan 坑表）。 */
    private boolean managerViewOf(Long groupId, Long actorUserId, boolean admin) {
        if (admin) {
            return true;
        }
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g != null && g.getOwnerUserId() != null && g.getOwnerUserId().equals(actorUserId)) {
            return true;
        }
        if (actorUserId != null) {
            ProjectGroupMemberEntity row = memberMapper.selectByGroupUser(groupId, actorUserId);
            return row != null && ProjectGroupMemberEntity.ROLE_MANAGER.equals(row.getRole());
        }
        return false;
    }

    /**
     * 组产出列表：usage_log 组维度行倒序分页，媒体行附任务概要。
     * 成员视角强制 self 过滤（忽略 memberUserId 参数）；组长/admin 可筛任意成员。
     *
     * @param kind    可选：CHAT/EMBED/RERANK/IMAGE/VIDEO
     * @param from/to 可选时间范围（含 from 含 to）
     */
    public PageResult<ProjectGroupOutputVO> outputs(Long groupId, Long actorUserId, boolean admin,
                                                    Long memberUserId, String kind,
                                                    OffsetDateTime from, OffsetDateTime to,
                                                    int page, int size) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        ProjectGroupMemberEntity viewerRow = memberMapper.selectByGroupUser(groupId, actorUserId);
        String viewerRole = viewerRow == null ? null
                : (viewerRow.getRole() == null ? ProjectGroupMemberEntity.ROLE_MEMBER : viewerRow.getRole());
        // V139：MANAGER 视同组长全量视角（管人需要）；admin/组长恒全量
        boolean owner = admin || g.getOwnerUserId().equals(actorUserId)
                || ProjectGroupMemberEntity.ROLE_MANAGER.equals(viewerRole);
        if (!owner && viewerRow == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非本项目组成员");
        }

        // 17x#2 可见性：组长/管理/admin 全量可筛；成员视角 = 自己的行 ∪ 「可见模块」的全员行。
        // V139 成员级覆盖：SQL 预过滤放宽（组级 ALL ∪ 任一成员覆盖 ALL 的模块——宁多取不漏取），
        // 行级精判在内存按「归属人成员覆盖 > 组模块覆盖 > 组默认」逐行过滤（分页 total 为预过滤口径，
        // 成员收紧场景页内行数可能少于 size——可接受，见 plan 坑表）。
        final Long effectiveUser;
        final List<String> memberVisibleAllKinds;
        final Map<Long, String> ownerOverrides;
        if (owner) {
            effectiveUser = memberUserId;
            memberVisibleAllKinds = List.of();
            ownerOverrides = Map.of();
        } else {
            List<ProjectGroupMemberEntity> memberRows = memberMapper.selectList(
                    new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                            .eq(ProjectGroupMemberEntity::getGroupId, groupId));
            ownerOverrides = memberRows.stream().collect(Collectors.toMap(
                    ProjectGroupMemberEntity::getUserId,
                    m -> m.getMemberVisibilityOverrides() == null ? "" : m.getMemberVisibilityOverrides(),
                    (a, b) -> a));
            List<String> visibleKinds = new ArrayList<>(visibilityService.visibleAllKindsForMember(g));
            for (String k : visibilityService.kindsAnyMemberOverrideAll(ownerOverrides.values())) {
                if (!visibleKinds.contains(k)) {
                    visibleKinds.add(k);
                }
            }
            memberVisibleAllKinds = visibleKinds;
            effectiveUser = visibleKinds.isEmpty() ? actorUserId : null;
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        LambdaQueryWrapper<LlmUsageLogEntity> qw = new LambdaQueryWrapper<LlmUsageLogEntity>()
                .eq(LlmUsageLogEntity::getProjectGroupId, groupId)
                .orderByDesc(LlmUsageLogEntity::getId);
        if (effectiveUser != null) {
            qw.eq(LlmUsageLogEntity::getUserId, effectiveUser);
        }
        if (!memberVisibleAllKinds.isEmpty()) {
            qw.and(w -> w.eq(LlmUsageLogEntity::getUserId, actorUserId)
                    .or().in(LlmUsageLogEntity::getKind, memberVisibleAllKinds));
        }
        if (kind != null && !kind.isBlank()) {
            qw.eq(LlmUsageLogEntity::getKind, kind);
        }
        if (from != null) {
            qw.ge(LlmUsageLogEntity::getCreatedAt, from);
        }
        if (to != null) {
            qw.le(LlmUsageLogEntity::getCreatedAt, to);
        }
        Page<LlmUsageLogEntity> p = usageLogMapper.selectPage(new Page<>(safePage, safeSize), qw);
        List<LlmUsageLogEntity> rows = p.getRecords();

        Map<Long, String> names = usernameMap(rows.stream()
                .map(LlmUsageLogEntity::getUserId)
                .filter(Objects::nonNull)
                .toList());
        Map<Long, String> displayNames = displayNameMap(rows.stream()
                .map(LlmUsageLogEntity::getUserId)
                .filter(Objects::nonNull)
                .toList());
        Map<Long, MediaGenTask> tasks = mediaTaskMap(rows.stream()
                .map(LlmUsageLogEntity::getTaskId)
                .filter(Objects::nonNull)
                .toList());
        // 17x 未解决#3：CHAT 行按轮配对——内容列=该次调用的用户提问，预览列=紧接的 assistant 回复
        Map<Long, String[]> chatTurns = chatTurnMap(rows);

        final String finalViewerRole = viewerRole;
        List<ProjectGroupOutputVO> vos = rows.stream()
                // V139：成员视角行级精判（预过滤放宽的回补）——按归属人三层有效可见性逐行过滤
                .filter(u -> owner || visibilityService.canSeeOutputResolved(
                        g, actorUserId, false, u.getKind(), u.getUserId(),
                        finalViewerRole, ownerOverrides.get(u.getUserId())))
                .map(u -> {
                    MediaGenTask t = u.getTaskId() != null ? tasks.get(u.getTaskId()) : null;
                    // 17x#1（V138）：产物文件引用仅对「按组可见性可见该行」的请求者透出
                    // V139：走预取判定（防逐行 N+1），成员覆盖/观察者角色与列表过滤同口径
                    boolean canSeeFiles = t != null
                            && (admin || visibilityService.canSeeOutputResolved(
                                    g, actorUserId, false, u.getKind(), u.getUserId(),
                                    finalViewerRole, ownerOverrides.get(u.getUserId())));
                    return new ProjectGroupOutputVO(
                            u.getId(), u.getCreatedAt(), u.getUserId(),
                            u.getUserId() != null ? names.get(u.getUserId()) : null,
                            u.getUserId() != null ? displayNames.get(u.getUserId()) : null,
                            u.getKind(), u.getModel(), u.getPointsConsumed(), u.getStatus(),
                            u.getTaskId(),
                            t != null ? t.getStatus() : null,
                            // 内容列：媒体行=requestConfig.prompt；CHAT 行=该次调用的用户提问（17x 未解决#3）
                            t != null && t.getRequestConfig() != null
                                    ? extractPrompt(t.getRequestConfig())
                                    : ("CHAT".equals(u.getKind()) && chatTurns.containsKey(u.getId())
                                            ? chatTurns.get(u.getId())[0] : null),
                            canSeeFiles ? t.getResultFileId() : null,
                            canSeeFiles ? extractImageFileIds(t.getResultMeta()) : null,
                            "CHAT".equals(u.getKind()) && chatTurns.containsKey(u.getId())
                                    ? chatTurns.get(u.getId())[1] : null);
                })
                .toList();
        return PageResult.of(vos, p.getTotal(), safePage, safeSize);
    }

    // ==================== 内部 ====================

    /** 批量取用户名（users 已删/缺失返缺项，前端显「#uid」兜底）。 */
    private Map<Long, String> usernameMap(List<Long> ids) {
        Set<Long> distinct = ids.stream().collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    /** 17x#2：批量取显示名（name 非空用 name，否则回落 username）。 */
    private Map<Long, String> displayNameMap(List<Long> ids) {
        Set<Long> distinct = ids.stream().collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(User::getId,
                        u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername(),
                        (a, b) -> a));
    }

    /** 批量取媒体任务（usage.taskId → 概要）。 */
    private Map<Long, MediaGenTask> mediaTaskMap(List<Long> taskIds) {
        Set<Long> distinct = taskIds.stream().collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return mediaTaskMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(MediaGenTask::getId, Function.identity(), (a, b) -> a));
    }

    /** requestConfig JSONB 里的 prompt 字段粗提取（无 Jackson 依赖的容错取法；失败返空串）。 */
    private String extractPrompt(String requestConfigJson) {
        if (requestConfigJson == null) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestConfigJson);
            String prompt = root.path("prompt").asText("");
            return prompt == null ? "" : prompt;
        } catch (Exception e) {
            return "";
        }
    }

    /** resultMeta JSONB 的 imageFileIds[] 提取（图片任务；视频/解析失败 → null）。 */
    private List<String> extractImageFileIds(String resultMetaJson) {
        if (resultMetaJson == null) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(resultMetaJson).path("imageFileIds");
            if (!arr.isArray() || arr.isEmpty()) {
                return null;
            }
            List<String> ids = new java.util.ArrayList<>();
            arr.forEach(n -> ids.add(n.asText()));
            return ids;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 17x 未解决#3：CHAT 行按轮配对（usage.sessionId=chat_sessions.id 字符串）。
     * 一次 IN 查回相关会话全部消息按 id 升序，逐 usage 行配对：
     * 提问=created_at ≤ 该行扣费时间的最后一条 user 消息（兜底=会话首条 user 消息）；
     * 回复=提问之后第一条 assistant 消息（兜底=会话最新 assistant）。
     *
     * @return key=usage_log.id，value=[用户提问, assistant 回复]（元素可空）
     */
    private Map<Long, String[]> chatTurnMap(List<LlmUsageLogEntity> rows) {
        List<LlmUsageLogEntity> chatRows = rows.stream()
                .filter(u -> "CHAT".equals(u.getKind()) && u.getSessionId() != null && !u.getSessionId().isBlank())
                .toList();
        Set<Long> sessionIds = chatRows.stream()
                .map(u -> {
                    try {
                        return Long.parseLong(u.getSessionId().trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        List<ChatMessage> msgs = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .in(ChatMessage::getSessionId, sessionIds)
                .orderByAsc(ChatMessage::getId));
        Map<Long, List<ChatMessage>> bySession = msgs.stream()
                .collect(Collectors.groupingBy(ChatMessage::getSessionId));
        Map<Long, String[]> out = new java.util.HashMap<>();
        for (LlmUsageLogEntity u : chatRows) {
            long sid;
            try {
                sid = Long.parseLong(u.getSessionId().trim());
            } catch (NumberFormatException e) {
                continue;
            }
            List<ChatMessage> sessionMsgs = bySession.getOrDefault(sid, List.of());
            ChatMessage question = null;
            ChatMessage firstUser = null;
            ChatMessage latestAssistant = null;
            for (ChatMessage m : sessionMsgs) {
                String role = m.getRole() == null ? "" : m.getRole().toLowerCase();
                if ("user".equals(role)) {
                    if (firstUser == null) {
                        firstUser = m;
                    }
                    if (u.getCreatedAt() == null || m.getCreatedAt() == null
                            || !m.getCreatedAt().isAfter(u.getCreatedAt())) {
                        question = m; // id 升序遍历，满足时间条件的最后一条=该次调用的提问
                    }
                } else if ("assistant".equals(role)) {
                    latestAssistant = m;
                }
            }
            if (question == null) {
                question = firstUser;
            }
            ChatMessage reply = null;
            if (question != null) {
                for (ChatMessage m : sessionMsgs) {
                    if (m.getId() > question.getId()
                            && "assistant".equalsIgnoreCase(m.getRole() == null ? "" : m.getRole())) {
                        reply = m;
                        break;
                    }
                }
            }
            if (reply == null) {
                reply = latestAssistant;
            }
            out.put(u.getId(), new String[]{
                    question != null ? question.getContent() : null,
                    reply != null ? reply.getContent() : null});
        }
        return out;
    }
}
