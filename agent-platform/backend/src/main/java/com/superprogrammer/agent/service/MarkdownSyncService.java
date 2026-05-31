// agent-platform/backend/src/main/java/com/superprogrammer/agent/service/MarkdownSyncService.java
package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentGroup;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarkdownSyncService {

    private final AgentGroupMapper agentGroupMapper;
    private final AgentMapper agentMapper;
    private final SkillMapper skillMapper;
    private final SkillStepMapper skillStepMapper;

    @Value("${agent.data-path:}")
    private String dataPath;

    /**
     * 全量同步入口：从Markdown文件目录同步所有Agent数据到数据库
     */
    @Transactional
    public int syncAll(Long operatorId) {
        if (dataPath == null || dataPath.isBlank()) {
            log.warn("agent.data-path 未配置，跳过Markdown同步");
            return 0;
        }

        Path rootPath = Paths.get(dataPath);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            log.warn("Agent数据目录不存在: {}", dataPath);
            return 0;
        }

        Path routerFile = rootPath.resolve("skills-router.md");
        if (!Files.exists(routerFile)) {
            log.warn("顶层skills-router.md不存在: {}", routerFile);
            return 0;
        }

        int totalSynced = 0;
        try {
            String content = Files.readString(routerFile);
            totalSynced += parseTopLevelRouter(content, operatorId);

            // 遍历每个Agent子目录，解析子路由
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootPath)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        Path subRouter = entry.resolve("skills-router.md");
                        if (Files.exists(subRouter)) {
                            String agentDirName = entry.getFileName().toString();
                            // 查找对应的Agent
                            Agent agent = findAgentByDirName(agentDirName);
                            if (agent != null) {
                                String subContent = Files.readString(subRouter);
                                totalSynced += parseSubAgentRouter(subContent, agent, operatorId);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("读取Markdown文件失败", e);
        }

        log.info("Markdown同步完成，共同步 {} 条记录", totalSynced);
        return totalSynced;
    }

    /**
     * 解析顶层skills-router.md → agent_groups + agents
     * 格式：
     * ## 分组名
     * | Agent | 描述 |
     * |-------|------|
     * | Agent名 | 描述内容 |
     */
    public int parseTopLevelRouter(String content, Long operatorId) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        int count = 0;
        String[] lines = content.split("\n");

        String currentGroupName = null;
        int groupSortOrder = 0;
        boolean inTable = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // 匹配二级标题作为分组名
            if (line.startsWith("## ") && !line.startsWith("### ")) {
                currentGroupName = line.substring(3).trim();
                groupSortOrder++;
                inTable = false;
                continue;
            }

            // 匹配表格分隔行
            if (line.startsWith("|") && line.contains("---")) {
                inTable = true;
                continue;
            }

            // 匹配表格数据行
            if (inTable && line.startsWith("|") && currentGroupName != null) {
                String[] cells = parseTableRow(line);
                if (cells.length >= 2 && !cells[0].trim().isEmpty()
                        && !"Agent".equalsIgnoreCase(cells[0].trim())) {
                    String agentName = cells[0].trim();
                    String agentDesc = cells[1].trim();

                    // Upsert分组
                    AgentGroup group = upsertGroup(currentGroupName, groupSortOrder, operatorId);

                    // Upsert Agent
                    upsertAgent(agentName, agentDesc, group.getId(), operatorId);
                    count++;
                }
            }
        }

        return count;
    }

    /**
     * 解析子Agent的skills-router.md → skills + skill_steps
     * 格式：
     * ## 技能名
     * **类型:** SEQUENCE
     * **描述:** 描述文字
     * ### 步骤
     * | 序号 | 名称 | 动作 |
     */
    public int parseSubAgentRouter(String content, Agent agent, Long operatorId) {
        if (content == null || content.isBlank() || agent == null) {
            return 0;
        }

        int count = 0;
        String[] lines = content.split("\n");

        String currentSkillName = null;
        String currentSkillType = "SEQUENCE";
        String currentSkillDesc = "";
        List<StepInfo> currentSteps = new ArrayList<>();
        boolean inStepsTable = false;
        int skillSortOrder = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // 匹配二级标题作为技能名
            if (line.startsWith("## ") && !line.startsWith("### ")) {
                // 保存前一个技能
                if (currentSkillName != null) {
                    upsertSkill(currentSkillName, currentSkillType, currentSkillDesc,
                            agent.getId(), skillSortOrder, currentSteps, operatorId);
                    count++;
                }
                currentSkillName = line.substring(3).trim();
                currentSkillType = "SEQUENCE";
                currentSkillDesc = "";
                currentSteps = new ArrayList<>();
                inStepsTable = false;
                skillSortOrder++;
                continue;
            }

            // 匹配类型
            if (line.startsWith("**类型:**") || line.startsWith("**Type:**")) {
                currentSkillType = line.substring(line.indexOf(":") + 1).trim();
                continue;
            }

            // 匹配描述
            if (line.startsWith("**描述:**") || line.startsWith("**Description:**")) {
                currentSkillDesc = line.substring(line.indexOf(":") + 1).trim();
                continue;
            }

            // 匹配步骤表格标题
            if (line.startsWith("### 步骤") || line.startsWith("### Steps")) {
                inStepsTable = false;
                continue;
            }

            // 匹配表格分隔行
            if (line.startsWith("|") && line.contains("---")) {
                inStepsTable = true;
                continue;
            }

            // 匹配步骤表格数据行
            if (inStepsTable && line.startsWith("|")) {
                String[] cells = parseTableRow(line);
                if (cells.length >= 3) {
                    try {
                        int stepOrder = Integer.parseInt(cells[0].trim());
                        String stepName = cells[1].trim();
                        String stepAction = cells[2].trim();
                        if (!stepName.isEmpty() && !stepAction.isEmpty()
                                && !"序号".equals(stepName)) {
                            currentSteps.add(new StepInfo(stepOrder, stepName, stepAction));
                        }
                    } catch (NumberFormatException e) {
                        // 跳过非数字行
                    }
                }
            }
        }

        // 保存最后一个技能
        if (currentSkillName != null) {
            upsertSkill(currentSkillName, currentSkillType, currentSkillDesc,
                    agent.getId(), skillSortOrder, currentSteps, operatorId);
            count++;
        }

        return count;
    }

    // ==================== 私有方法 ====================

    private AgentGroup upsertGroup(String groupName, int sortOrder, Long operatorId) {
        LambdaQueryWrapper<AgentGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentGroup::getName, groupName);
        AgentGroup existing = agentGroupMapper.selectOne(wrapper);

        if (existing != null) {
            existing.setSortOrder(sortOrder);
            existing.setUpdatedBy(operatorId);
            agentGroupMapper.updateById(existing);
            return existing;
        }

        AgentGroup group = new AgentGroup();
        group.setName(groupName);
        group.setSortOrder(sortOrder);
        group.setCreatedBy(operatorId);
        group.setUpdatedBy(operatorId);
        agentGroupMapper.insert(group);
        return group;
    }

    private Agent upsertAgent(String agentName, String description, Long groupId, Long operatorId) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Agent::getName, agentName);
        Agent existing = agentMapper.selectOne(wrapper);

        if (existing != null) {
            existing.setDescription(description);
            existing.setGroupId(groupId);
            existing.setUpdatedBy(operatorId);
            agentMapper.updateById(existing);
            return existing;
        }

        Agent agent = new Agent();
        agent.setName(agentName);
        agent.setDescription(description);
        agent.setGroupId(groupId);
        agent.setStatus("DRAFT");
        agent.setCreatedBy(operatorId);
        agent.setUpdatedBy(operatorId);
        agentMapper.insert(agent);
        return agent;
    }

    private void upsertSkill(String skillName, String type, String description,
                             Long agentId, int sortOrder, List<StepInfo> steps,
                             Long operatorId) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Skill::getAgentId, agentId)
                .eq(Skill::getName, skillName);
        Skill existing = skillMapper.selectOne(wrapper);

        Skill skill;
        if (existing != null) {
            existing.setType(type);
            existing.setDescription(description);
            existing.setSortOrder(sortOrder);
            existing.setUpdatedBy(operatorId);
            skillMapper.updateById(existing);
            skill = existing;
        } else {
            skill = new Skill();
            skill.setAgentId(agentId);
            skill.setName(skillName);
            skill.setType(type);
            skill.setDescription(description);
            skill.setSortOrder(sortOrder);
            skill.setCreatedBy(operatorId);
            skill.setUpdatedBy(operatorId);
            skillMapper.insert(skill);
        }

        // Upsert步骤
        for (StepInfo stepInfo : steps) {
            LambdaQueryWrapper<SkillStep> stepWrapper = new LambdaQueryWrapper<>();
            stepWrapper.eq(SkillStep::getSkillId, skill.getId())
                    .eq(SkillStep::getStepOrder, stepInfo.order);
            SkillStep existingStep = skillStepMapper.selectOne(stepWrapper);

            if (existingStep != null) {
                existingStep.setName(stepInfo.name);
                existingStep.setAction(stepInfo.action);
                existingStep.setUpdatedBy(operatorId);
                skillStepMapper.updateById(existingStep);
            } else {
                SkillStep step = new SkillStep();
                step.setSkillId(skill.getId());
                step.setStepOrder(stepInfo.order);
                step.setName(stepInfo.name);
                step.setAction(stepInfo.action);
                step.setCreatedBy(operatorId);
                step.setUpdatedBy(operatorId);
                skillStepMapper.insert(step);
            }
        }
    }

    private Agent findAgentByDirName(String dirName) {
        // 将目录名转换为可能的Agent名（去掉编号前缀等）
        // 例如 "01-代码助手" -> "代码助手"
        String agentName = dirName.replaceFirst("^\\d+[-_]", "").replace("-", " ");
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Agent::getName, agentName);
        Agent agent = agentMapper.selectOne(wrapper);
        if (agent == null) {
            // 也尝试按目录名全名匹配
            wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Agent::getName, dirName);
            agent = agentMapper.selectOne(wrapper);
        }
        return agent;
    }

    private String[] parseTableRow(String line) {
        // 去除首尾的 |
        String trimmed = line;
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.split("\\|");
    }

    /**
     * 步骤信息内部类
     */
    private static class StepInfo {
        int order;
        String name;
        String action;

        StepInfo(int order, String name, String action) {
            this.order = order;
            this.name = name;
            this.action = action;
        }
    }
}
