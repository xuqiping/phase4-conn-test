package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.entity.MemoryProjectSetting;
import com.superprogrammer.chat.entity.MemoryProjectUserSetting;
import com.superprogrammer.chat.mapper.MemoryProjectSettingMapper;
import com.superprogrammer.chat.mapper.MemoryProjectUserSettingMapper;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 计划12 · C · gen 开关判定（总体设计 §3.1 + §5 配置开关矩阵）。
 * <p>
 * 判定一轮对话是否调生成 LLM（L0/L1/L2 同开同关）：
 * <ul>
 *   <li><b>非项目会话</b>（{@code projectId=null}）→ 读全局兜底
 *       {@code rag.memory.gen.personal.enabled}（默认 true）。</li>
 *   <li><b>项目会话</b> → {@code memory_project_settings.gen_enabled}（owner 项目级）
 *       <b>AND</b> {@code memory_project_user_settings.gen_enabled}（会员覆写，会员自控）。
 *       两者默认 true（无行 = 未显式关 = 开）。</li>
 * </ul>
 * <p>
 * <b>任一关 → 不调生成 LLM</b>，仍跑前置过滤后过过滤侧写 raw 行（{@code gen_done=false}，
 * tag/L1/L2 空）——由 MemoryGenerationService 解释本返回值。
 *
 * @see MemoryPrefilter 前置过滤（与开关独立——开关关也跑过滤后写 raw）
 * @see MemoryProjectSettingMapper owner 项目级开关
 * @see MemoryProjectUserSettingMapper 会员覆写开关
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryGenToggleService {

    private final SystemSettingService systemSettingService;
    private final MemoryProjectSettingMapper projectSettingMapper;
    private final MemoryProjectUserSettingMapper userSettingMapper;

    /**
     * 判定是否生成（调 LLM 出 L0/L1/L2）。
     *
     * @param userId    作者 user id（项目会话用于查会员覆写；非项目会话可空）
     * @param projectId 会话所属项目 id（null = 非项目会话，读全局兜底）
     * @return true = 跑生成 LLM；false = 只写 raw（gen_done=false）
     */
    public boolean resolveGenEnabled(Long userId, Long projectId) {
        if (projectId == null) {
            boolean on = systemSettingService.getMemoryGenPersonalEnabled();
            log.debug("gen 开关 非项目 userId={} → 全局兜底 rag.memory.gen.personal.enabled={}", userId, on);
            return on;
        }
        boolean ownerOn = projectOwnerEnabled(projectId);
        boolean memberOn = memberOverrideEnabled(projectId, userId);
        boolean on = ownerOn && memberOn;
        log.debug("gen 开关 项目 projectId={} userId={} → owner={} AND member={} = {}",
                projectId, userId, ownerOn, memberOn, on);
        return on;
    }

    /** owner 项目级开关：无行 / genEnabled=null → 默认 true（未显式关 = 开）。 */
    private boolean projectOwnerEnabled(Long projectId) {
        MemoryProjectSetting row = projectSettingMapper.selectOne(
                new LambdaQueryWrapper<MemoryProjectSetting>()
                        .eq(MemoryProjectSetting::getProjectId, projectId));
        return row == null || row.getGenEnabled() == null || row.getGenEnabled();
    }

    /** 会员覆写开关：无行 / genEnabled=null → 默认 true；无 userId 无法判 → 默认开（不阻塞生成）。 */
    private boolean memberOverrideEnabled(Long projectId, Long userId) {
        if (userId == null) {
            return true;
        }
        MemoryProjectUserSetting row = userSettingMapper.selectOne(
                new LambdaQueryWrapper<MemoryProjectUserSetting>()
                        .eq(MemoryProjectUserSetting::getProjectId, projectId)
                        .eq(MemoryProjectUserSetting::getUserId, userId));
        return row == null || row.getGenEnabled() == null || row.getGenEnabled();
    }
}
