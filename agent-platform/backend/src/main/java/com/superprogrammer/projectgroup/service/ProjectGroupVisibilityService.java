package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 组产出可见性判定（17x#1/#2，V138）。
 * <p>规则（组长需求：每人只看自己 / 全员互见 / 按模块覆盖）：
 * <ul>
 *   <li>产出本人、组长、admin：恒可见。</li>
 *   <li>普通成员：先看 module_visibility_overrides[kind]（稀疏覆盖），缺省回落
 *       member_output_visibility（OWN 默认 / ALL）。ALL → 该模块全组互见。</li>
 * </ul>
 * <p>产物文件读取走 {@link #canAccessGroupFile}（被 ProjectGroupFileAccessGrantor 调）：
 * 文件 → 所属媒体任务 → 任务组 + 任务类型(kind) + 产出人，复用同一判定，fail-closed。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGroupVisibilityService {

    /** 产出模块 kind 全集（与 llm_usage_logs.kind / outputs 筛选口径一致）。 */
    public static final List<String> OUTPUT_KINDS = List.of("CHAT", "EMBED", "RERANK", "IMAGE", "VIDEO");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProjectGroupMapper groupMapper;
    private final ProjectGroupMemberMapper memberMapper;
    private final MediaGenTaskMapper mediaTaskMapper;
    private final ProjectGroupService groupService;

    /** 单模块有效可见性：覆盖表命中用覆盖，否则回落组默认；组行为 null 按 OWN（防御）。 */
    public String effectiveVisibility(ProjectGroupEntity g, String kind) {
        String override = overrideFor(g, kind);
        if (override != null) {
            return override;
        }
        String base = g.getMemberOutputVisibility();
        return ProjectGroupEntity.VIS_ALL.equals(base) ? ProjectGroupEntity.VIS_ALL : ProjectGroupEntity.VIS_OWN;
    }

    /** 覆盖表中该模块的显式值（OWN/ALL），无覆盖/非法 JSON → null。 */
    private String overrideFor(ProjectGroupEntity g, String kind) {
        String json = g.getModuleVisibilityOverrides();
        if (json == null || json.isBlank() || kind == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json).path(kind);
            String v = node.isTextual() ? node.asText() : null;
            return (ProjectGroupEntity.VIS_ALL.equals(v) || ProjectGroupEntity.VIS_OWN.equals(v)) ? v : null;
        } catch (Exception e) {
            log.warn("组可见性覆盖 JSON 解析失败 groupId={} json={}", g.getId(), json);
            return null;
        }
    }

    /**
     * 产出行可见判定。
     *
     * @param kind        产出模块（CHAT/EMBED/RERANK/IMAGE/VIDEO）
     * @param ownerUserId 产出归属人（usage.user_id / 媒体任务 user_id）
     */
    public boolean canSeeOutput(ProjectGroupEntity g, Long viewerUserId, boolean admin, String kind, Long ownerUserId) {
        if (admin || g.getOwnerUserId().equals(viewerUserId) || viewerUserId.equals(ownerUserId)) {
            return true;
        }
        return ProjectGroupEntity.VIS_ALL.equals(effectiveVisibility(g, kind));
    }

    /** 成员视角下「全组互见」的模块集（outputs 列表 SQL 用：own 行 ∪ 这些 kind 的全员行）。 */
    public List<String> visibleAllKindsForMember(ProjectGroupEntity g) {
        List<String> kinds = new ArrayList<>();
        for (String kind : OUTPUT_KINDS) {
            if (ProjectGroupEntity.VIS_ALL.equals(effectiveVisibility(g, kind))) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    /**
     * 组长更新可见性设置（17x#2）。
     *
     * @param base      OWN/ALL（null=不动）
     * @param overrides 稀疏覆盖 map（null=不动；空 map=清空覆盖）；key 限 OUTPUT_KINDS，value 限 OWN/ALL
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateVisibility(Long groupId, Long actorUserId, boolean admin,
                                 String base, Map<String, String> overrides) {
        ProjectGroupEntity g = groupService.requireOwner(groupId, actorUserId, admin);
        if (base != null) {
            if (!ProjectGroupEntity.VIS_OWN.equals(base) && !ProjectGroupEntity.VIS_ALL.equals(base)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "可见性仅支持 OWN/ALL");
            }
            g.setMemberOutputVisibility(base);
        }
        if (overrides != null) {
            if (overrides.isEmpty()) {
                g.setModuleVisibilityOverrides(null);
            } else {
                for (Map.Entry<String, String> e : overrides.entrySet()) {
                    if (!OUTPUT_KINDS.contains(e.getKey())) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "未知产出模块: " + e.getKey());
                    }
                    if (!ProjectGroupEntity.VIS_OWN.equals(e.getValue()) && !ProjectGroupEntity.VIS_ALL.equals(e.getValue())) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "覆盖值仅支持 OWN/ALL: " + e.getKey());
                    }
                }
                try {
                    g.setModuleVisibilityOverrides(MAPPER.writeValueAsString(overrides));
                } catch (Exception e) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "覆盖配置序列化失败");
                }
            }
        }
        groupMapper.updateById(g);
        log.info("组可见性更新 groupId={} base={} overrides={} actor={}", groupId, base, overrides, actorUserId);
    }

    /**
     * 产物文件组内共享判定（FileSharedAccessGrantor 钩子；fail-closed 由调用方兜底）。
     * <p>链路：fileId → media_gen_tasks（视频 result_file_id / 图片 result_meta.imageFileIds）
     * → 任务有 project_group_id 且请求者是该组成员 → canSeeOutput（kind 由任务类型映射）。
     */
    public boolean canAccessGroupFile(String fileId, Long userId) {
        MediaGenTask task = findTaskByResultFile(fileId);
        if (task == null || task.getProjectGroupId() == null) {
            return false;
        }
        ProjectGroupEntity g = groupMapper.selectById(task.getProjectGroupId());
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            return false;
        }
        // 产出本人/组长走 owner 短路（canSeeOutput 内部判定）；普通成员须确为组成员
        if (!g.getOwnerUserId().equals(userId) && !userId.equals(task.getUserId())) {
            ProjectGroupMemberEntity m = memberMapper.selectByGroupUser(g.getId(), userId);
            if (m == null) {
                return false;
            }
        }
        String kind = kindOfTask(task.getTaskType());
        return canSeeOutput(g, userId, false, kind, task.getUserId());
    }

    /** 任务类型 → 产出模块 kind（生图两类→IMAGE；生视频两类→VIDEO；未知→null 回落组默认）。 */
    private String kindOfTask(String taskType) {
        if (MediaGenTask.TYPE_TEXT2IMAGE.equals(taskType) || MediaGenTask.TYPE_IMAGE2IMAGE.equals(taskType)) {
            return "IMAGE";
        }
        if (MediaGenTask.TYPE_TEXT2VIDEO.equals(taskType) || MediaGenTask.TYPE_IMAGE2VIDEO.equals(taskType)) {
            return "VIDEO";
        }
        return null;
    }

    /** 按产物 fileId 找媒体任务：视频精确列先行；图片 result_meta JSON 数组包含匹配（LIKE 粗筛 + 解析精判）。 */
    private MediaGenTask findTaskByResultFile(String fileId) {
        MediaGenTask video = mediaTaskMapper.selectOne(new LambdaQueryWrapper<MediaGenTask>()
                .eq(MediaGenTask::getResultFileId, fileId)
                .last("LIMIT 1"));
        if (video != null) {
            return video;
        }
        List<MediaGenTask> candidates = mediaTaskMapper.selectList(new LambdaQueryWrapper<MediaGenTask>()
                .isNotNull(MediaGenTask::getResultMeta)
                .apply("result_meta::text LIKE {0}", "%" + fileId + "%")
                .last("LIMIT 10"));
        for (MediaGenTask t : candidates) {
            if (resultMetaContains(t.getResultMeta(), fileId)) {
                return t;
            }
        }
        return null;
    }

    /** result_meta.imageFileIds[] 精确包含判定（防 LIKE 子串误中）。 */
    private boolean resultMetaContains(String resultMeta, String fileId) {
        try {
            JsonNode arr = MAPPER.readTree(resultMeta).path("imageFileIds");
            if (!arr.isArray()) {
                return false;
            }
            for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) {
                if (fileId.equals(it.next().asText())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("result_meta 解析失败 taskResultMeta={}", resultMeta);
        }
        return false;
    }
}
