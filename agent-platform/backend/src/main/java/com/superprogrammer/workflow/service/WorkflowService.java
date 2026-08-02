package com.superprogrammer.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.agent.service.AgentPermissionService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.workflow.dto.WorkflowCreateRequest;
import com.superprogrammer.workflow.dto.WorkflowDetailVO;
import com.superprogrammer.workflow.dto.WorkflowEdgeDTO;
import com.superprogrammer.workflow.dto.WorkflowNodeDTO;
import com.superprogrammer.workflow.dto.WorkflowVO;
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.entity.WorkflowEdge;
import com.superprogrammer.workflow.entity.WorkflowNode;
import com.superprogrammer.workflow.mapper.WorkflowEdgeMapper;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import com.superprogrammer.workflow.mapper.WorkflowNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final List<String> SENSITIVE_SKILL_CONFIG_KEYS = List.of(
            "systemPrompt", "promptTemplate", "model", "temperature", "outputKey");

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowEdgeMapper workflowEdgeMapper;
    private final AgentMapper agentMapper;
    private final SkillMapper skillMapper;
    private final SkillStepMapper skillStepMapper;
    private final AgentPermissionService agentPermissionService;
    private final ObjectMapper objectMapper;

    public List<WorkflowVO> listWorkflows(Long userId) {
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Workflow::getOwnerId, userId)
                .orderByDesc(Workflow::getUpdatedAt);
        return workflowMapper.selectList(wrapper).stream()
                .map(this::toWorkflowVO)
                .collect(Collectors.toList());
    }

    public WorkflowDetailVO getWorkflowDetail(Long id) {
        return getWorkflowDetail(id, null, false);
    }

    public WorkflowDetailVO getWorkflowDetail(Long id, Long userId, boolean admin) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }

        List<WorkflowNode> nodes = getWorkflowNodes(id);
        List<WorkflowEdge> edges = getWorkflowEdges(id);

        return WorkflowDetailVO.builder()
                .id(workflow.getId())
                .name(workflow.getName())
                .description(workflow.getDescription())
                .status(workflow.getStatus())
                .ownerId(workflow.getOwnerId())
                .nodes(nodes.stream().map(node -> toNodeDTO(node, userId, admin)).collect(Collectors.toList()))
                .edges(edges.stream().map(this::toEdgeDTO).collect(Collectors.toList()))
                .ragEnabled(workflow.getRagEnabled())
                .createdAt(workflow.getCreatedAt())
                .updatedAt(workflow.getUpdatedAt())
                .build();
    }

    @Transactional
    public WorkflowVO createWorkflow(WorkflowCreateRequest request, Long userId) {
        Workflow workflow = new Workflow();
        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setStatus("DRAFT");
        workflow.setOwnerId(userId);
        workflow.setCreatedBy(userId);
        workflow.setUpdatedBy(userId);
        workflowMapper.insert(workflow);

        boolean hasRequestNodes = request.getNodes() != null && !request.getNodes().isEmpty();
        if (!hasRequestNodes) {
            insertDefaultNode(workflow.getId(), "START", "开始", 100.0, 300.0, userId);
            insertDefaultNode(workflow.getId(), "END", "结束", 800.0, 300.0, userId);
        }

        if (request.getNodes() != null) {
            validateNodeAliases(request.getNodes());
            for (WorkflowNodeDTO nodeDTO : request.getNodes()) {
                WorkflowNode node = new WorkflowNode();
                node.setWorkflowId(workflow.getId());
                node.setNodeId(nodeDTO.getNodeId() != null ? nodeDTO.getNodeId() : UUID.randomUUID().toString());
                node.setType(nodeDTO.getType());
                node.setPositionX(nodeDTO.getPositionX());
                node.setPositionY(nodeDTO.getPositionY());
                node.setLabel(nodeDTO.getLabel());
                node.setConfig(prepareNodeConfigForSave(nodeDTO, userId, false, Map.of()));
                node.setCreatedBy(userId);
                node.setUpdatedBy(userId);
                workflowNodeMapper.insert(node);
            }
        }

        insertEdges(workflow.getId(), request.getEdges(), userId);
        log.info("工作流创建成功 id={}, name={}", workflow.getId(), workflow.getName());
        return toWorkflowVO(workflow);
    }

    @Transactional
    public WorkflowVO updateWorkflow(Long id, WorkflowCreateRequest request, Long userId) {
        return updateWorkflow(id, request, userId, false);
    }

    @Transactional
    public WorkflowVO updateWorkflow(Long id, WorkflowCreateRequest request, Long userId, boolean admin) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        if (!admin && !workflow.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或工作流创建者可以修改工作流配置");
        }

        validateNodeAliases(request.getNodes());

        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setUpdatedBy(userId);
        workflowMapper.updateById(workflow);

        Map<String, Map<String, Object>> previousNodeConfigs = existingNodeConfigs(id);
        workflowEdgeMapper.deletePhysicallyByWorkflowId(id);
        workflowNodeMapper.deletePhysicallyByWorkflowId(id);

        if (request.getNodes() != null) {
            for (WorkflowNodeDTO nodeDTO : request.getNodes()) {
                WorkflowNode node = new WorkflowNode();
                node.setWorkflowId(id);
                node.setNodeId(nodeDTO.getNodeId());
                node.setType(nodeDTO.getType());
                node.setPositionX(nodeDTO.getPositionX());
                node.setPositionY(nodeDTO.getPositionY());
                node.setLabel(nodeDTO.getLabel());
                node.setConfig(prepareNodeConfigForSave(nodeDTO, userId, admin, previousNodeConfigs));
                node.setCreatedBy(userId);
                node.setUpdatedBy(userId);
                workflowNodeMapper.insert(node);
            }
        }

        insertEdges(id, request.getEdges(), userId);
        log.info("工作流更新成功 id={}", id);
        return toWorkflowVO(workflow);
    }

    public void deleteWorkflow(Long id, Long userId) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        if (!workflow.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己创建的工作流");
        }
        workflowMapper.deleteById(id);
        log.info("工作流删除成功 id={}", id);
    }

    @Transactional
    public WorkflowVO duplicateWorkflow(Long id, Long userId) {
        Workflow source = workflowMapper.selectById(id);
        if (source == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "源工作流不存在");
        }

        Workflow newWorkflow = new Workflow();
        newWorkflow.setName(source.getName() + " (副本)");
        newWorkflow.setDescription(source.getDescription());
        newWorkflow.setStatus("DRAFT");
        newWorkflow.setOwnerId(userId);
        newWorkflow.setCreatedBy(userId);
        newWorkflow.setUpdatedBy(userId);
        workflowMapper.insert(newWorkflow);

        for (WorkflowNode sourceNode : getWorkflowNodes(id)) {
            WorkflowNode newNode = new WorkflowNode();
            newNode.setWorkflowId(newWorkflow.getId());
            newNode.setNodeId(sourceNode.getNodeId());
            newNode.setType(sourceNode.getType());
            newNode.setPositionX(sourceNode.getPositionX());
            newNode.setPositionY(sourceNode.getPositionY());
            newNode.setLabel(sourceNode.getLabel());
            newNode.setConfig(sourceNode.getConfig());
            newNode.setCreatedBy(userId);
            newNode.setUpdatedBy(userId);
            workflowNodeMapper.insert(newNode);
        }

        for (WorkflowEdge sourceEdge : getWorkflowEdges(id)) {
            WorkflowEdge newEdge = new WorkflowEdge();
            newEdge.setWorkflowId(newWorkflow.getId());
            newEdge.setSourceNodeId(sourceEdge.getSourceNodeId());
            newEdge.setTargetNodeId(sourceEdge.getTargetNodeId());
            newEdge.setSourceHandle(sourceEdge.getSourceHandle());
            newEdge.setTargetHandle(sourceEdge.getTargetHandle());
            newEdge.setLabel(sourceEdge.getLabel());
            newEdge.setCondition(sourceEdge.getCondition());
            newEdge.setCreatedBy(userId);
            newEdge.setUpdatedBy(userId);
            workflowEdgeMapper.insert(newEdge);
        }

        log.info("工作流复制成功 sourceId={}, newId={}", id, newWorkflow.getId());
        return toWorkflowVO(newWorkflow);
    }

    private void insertDefaultNode(Long workflowId, String type, String label, Double x, Double y, Long userId) {
        WorkflowNode node = new WorkflowNode();
        node.setWorkflowId(workflowId);
        node.setNodeId(UUID.randomUUID().toString());
        node.setType(type);
        node.setLabel(label);
        node.setPositionX(x);
        node.setPositionY(y);
        node.setCreatedBy(userId);
        node.setUpdatedBy(userId);
        workflowNodeMapper.insert(node);
    }

    private void insertEdges(Long workflowId, List<WorkflowEdgeDTO> edges, Long userId) {
        if (edges == null) {
            return;
        }
        for (WorkflowEdgeDTO edgeDTO : edges) {
            WorkflowEdge edge = new WorkflowEdge();
            edge.setWorkflowId(workflowId);
            edge.setSourceNodeId(edgeDTO.getSourceNodeId());
            edge.setTargetNodeId(edgeDTO.getTargetNodeId());
            edge.setSourceHandle(edgeDTO.getSourceHandle());
            edge.setTargetHandle(edgeDTO.getTargetHandle());
            edge.setLabel(edgeDTO.getLabel());
            edge.setCondition(edgeDTO.getCondition());
            edge.setCreatedBy(userId);
            edge.setUpdatedBy(userId);
            workflowEdgeMapper.insert(edge);
        }
    }

    private String prepareNodeConfigForSave(
            WorkflowNodeDTO nodeDTO,
            Long userId,
            boolean admin,
            Map<String, Map<String, Object>> previousNodeConfigs) {
        Map<String, Object> config = parseConfig(nodeDTO.getConfig());
        if (config.isEmpty()) {
            return nodeDTO.getConfig();
        }
        if ("AGENT_REF".equalsIgnoreCase(nodeDTO.getType())) {
            Agent agent = agentOfAgentRefNode(config);
            boolean canManageDescription = admin || isAgentOwner(agent, userId);
            normalizeNodeDescription(config, previousNodeConfigs.get(nodeDTO.getNodeId()),
                    agent == null ? null : agent.getDescription(), canManageDescription);
            mergeDescriptionPermissions(config, agent == null ? null : agent.getDescription(), canManageDescription);
            return writeConfig(config);
        }
        if (!"SKILL".equalsIgnoreCase(nodeDTO.getType())) {
            return writeConfig(config);
        }

        Agent agent = agentOfSkillNode(config);
        Skill skill = skillOfSkillNode(config);
        boolean canManageSkill = admin || isAgentOwner(agent, userId) || isSkillOwner(skill, userId);
        normalizeNodeDescription(config, previousNodeConfigs.get(nodeDTO.getNodeId()),
                skill == null ? null : skill.getDescription(), canManageSkill);
        boolean containsSensitive = containsSensitiveSkillConfig(config);
        if (containsSensitive && !canManageSkill) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或 Agent 拥有者可以修改能力提示词");
        }
        if (containsSensitive) {
            syncSkillStepConfig(config, userId);
        }
        mergeDescriptionPermissions(config, skill == null ? null : skill.getDescription(), canManageSkill);
        config.put("promptConfigVisible", canManageSkill);
        config.put("promptConfigEditable", canManageSkill);
        return writeConfig(config);
    }

    private String configForDetail(WorkflowNode node, Long userId, boolean admin) {
        Map<String, Object> config = parseConfig(node.getConfig());
        if (config.isEmpty() || !"SKILL".equalsIgnoreCase(node.getType())) {
            if ("AGENT_REF".equalsIgnoreCase(node.getType())) {
                Agent agent = agentOfAgentRefNode(config);
                boolean canManageDescription = admin || isAgentOwner(agent, userId);
                Map<String, Object> visibleConfig = new LinkedHashMap<>(config);
                mergeDescriptionPermissions(visibleConfig, agent == null ? null : agent.getDescription(), canManageDescription);
                return writeConfig(visibleConfig);
            }
            return node.getConfig();
        }
        Agent agent = agentOfSkillNode(config);
        Map<String, Object> visibleConfig = new LinkedHashMap<>(config);
        mergePublicSkillConfig(visibleConfig);
        Skill skill = skillOfSkillNode(config);
        boolean canManageSkill = admin || isAgentOwner(agent, userId) || isSkillOwner(skill, userId);
        boolean canReadPrompt = canManageSkill || (agent != null && agentPermissionService.canReadPrompt(agent.getId(), userId, admin));
        mergeDescriptionPermissions(visibleConfig, skill == null ? null : skill.getDescription(), canManageSkill);
        visibleConfig.put("promptConfigVisible", canReadPrompt);
        visibleConfig.put("promptConfigEditable", canManageSkill);
        if (!canReadPrompt) {
            SENSITIVE_SKILL_CONFIG_KEYS.forEach(visibleConfig::remove);
        }
        return writeConfig(visibleConfig);
    }

    private void syncSkillStepConfig(Map<String, Object> nodeConfig, Long userId) {
        Long skillId = asLong(nodeConfig.get("skillId"));
        if (skillId == null) {
            return;
        }
        List<SkillStep> steps = skillSteps(skillId);
        if (steps.isEmpty()) {
            return;
        }
        SkillStep firstStep = steps.get(0);
        Map<String, Object> stepConfig = parseConfig(firstStep.getConfig());
        ObjectNode merged = objectMapper.valueToTree(stepConfig);
        for (String key : SENSITIVE_SKILL_CONFIG_KEYS) {
            if (nodeConfig.containsKey(key)) {
                merged.set(key, objectMapper.valueToTree(nodeConfig.get(key)));
            }
        }
        try {
            firstStep.setConfig(objectMapper.writeValueAsString(merged));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "能力提示词配置序列化失败");
        }
        firstStep.setUpdatedBy(userId);
        skillStepMapper.updateById(firstStep);
    }

    private List<SkillStep> skillSteps(Long skillId) {
        LambdaQueryWrapper<SkillStep> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkillStep::getSkillId, skillId)
                .eq(SkillStep::getDeleted, 0)
                .orderByAsc(SkillStep::getStepOrder);
        return skillStepMapper.selectList(wrapper);
    }

    private Agent agentOfSkillNode(Map<String, Object> config) {
        Long agentId = asLong(config.get("agentId"));
        Long skillId = asLong(config.get("skillId"));
        if (skillId != null) {
            Skill skill = skillMapper.selectById(skillId);
            if (skill != null) {
                if (agentId != null && !agentId.equals(skill.getAgentId())) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 不属于指定 Agent");
                }
                agentId = skill.getAgentId();
            }
        }
        return agentId == null ? null : agentMapper.selectById(agentId);
    }

    private Skill skillOfSkillNode(Map<String, Object> config) {
        Long skillId = asLong(config.get("skillId"));
        return skillId == null ? null : skillMapper.selectById(skillId);
    }

    private Agent agentOfAgentRefNode(Map<String, Object> config) {
        Long agentId = asLong(config.get("agentId"));
        return agentId == null ? null : agentMapper.selectById(agentId);
    }

    private void mergeDescriptionPermissions(Map<String, Object> config, String sourceDescription, boolean editable) {
        if (!config.containsKey("description") && sourceDescription != null && !sourceDescription.isBlank()) {
            config.put("description", sourceDescription);
        }
        config.put("descriptionVisible", true);
        config.put("descriptionEditable", editable);
    }

    private void normalizeNodeDescription(
            Map<String, Object> config,
            Map<String, Object> previousConfig,
            String sourceDescription,
            boolean editable) {
        if (editable) {
            return;
        }
        Object previousDescription = previousConfig == null ? null : previousConfig.get("description");
        if (previousDescription != null && !String.valueOf(previousDescription).isBlank()) {
            config.put("description", previousDescription);
            return;
        }
        if (sourceDescription == null || sourceDescription.isBlank()) {
            config.remove("description");
        } else {
            config.put("description", sourceDescription);
        }
    }

    private void mergePublicSkillConfig(Map<String, Object> nodeConfig) {
        Long skillId = asLong(nodeConfig.get("skillId"));
        if (skillId == null) {
            return;
        }
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            return;
        }
        Map<String, Object> skillConfig = parseConfig(skill.getConfig());
        Object inputParams = skillConfig.get("inputParams");
        if (inputParams != null) {
            nodeConfig.put("inputParams", inputParams);
        }
    }

    private boolean isAgentOwner(Agent agent, Long userId) {
        return agent != null && userId != null && userId.equals(agent.getCreatedBy());
    }

    private boolean isSkillOwner(Skill skill, Long userId) {
        return skill != null && userId != null && userId.equals(skill.getCreatedBy());
    }

    private boolean containsSensitiveSkillConfig(Map<String, Object> config) {
        return SENSITIVE_SKILL_CONFIG_KEYS.stream().anyMatch(config::containsKey);
    }

    private void validateNodeAliases(List<WorkflowNodeDTO> nodes) {
        if (nodes == null) {
            return;
        }
        Set<String> aliases = new HashSet<>();
        for (WorkflowNodeDTO node : nodes) {
            Map<String, Object> config = parseConfig(node.getConfig());
            if (config.isEmpty()) {
                continue;
            }
            ensureNodeAlias(node, config);
            String alias = String.valueOf(config.get("nodeAlias"));
            if (!alias.matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "节点别名只能包含字母、数字、下划线，且必须以字母开头");
            }
            if (!aliases.add(alias)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "节点别名不能重复: " + alias);
            }
            node.setConfig(writeConfig(config));
        }
    }

    private void ensureNodeAlias(WorkflowNodeDTO node, Map<String, Object> config) {
        Object value = config.get("nodeAlias");
        if (value != null && !String.valueOf(value).isBlank()) {
            return;
        }
        config.put("nodeAlias", defaultNodeAlias(node));
    }

    private String defaultNodeAlias(WorkflowNodeDTO node) {
        if ("START".equalsIgnoreCase(node.getType())) {
            return "start";
        }
        String source = node.getLabel() == null || node.getLabel().isBlank()
                ? node.getNodeId()
                : node.getLabel();
        String normalized = Normalizer.normalize(source == null ? "node" : source, Normalizer.Form.NFD)
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank() || !Character.isLetter(normalized.charAt(0))) {
            String fallback = node.getNodeId() == null
                    ? UUID.randomUUID().toString().replace("-", "")
                    : node.getNodeId().replaceAll("[^A-Za-z0-9]+", "_");
            normalized = "node_" + fallback;
        }
        return normalized.substring(0, 1).toLowerCase(Locale.ROOT) + normalized.substring(1);
    }

    private Map<String, Map<String, Object>> existingNodeConfigs(Long workflowId) {
        return getWorkflowNodes(workflowId).stream()
                .collect(Collectors.toMap(
                        WorkflowNode::getNodeId,
                        node -> parseConfig(node.getConfig()),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private List<WorkflowNode> getWorkflowNodes(Long workflowId) {
        LambdaQueryWrapper<WorkflowNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkflowNode::getWorkflowId, workflowId);
        return workflowNodeMapper.selectList(wrapper);
    }

    private List<WorkflowEdge> getWorkflowEdges(Long workflowId) {
        LambdaQueryWrapper<WorkflowEdge> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkflowEdge::getWorkflowId, workflowId);
        return workflowEdgeMapper.selectList(wrapper);
    }

    private WorkflowVO toWorkflowVO(Workflow workflow) {
        return WorkflowVO.builder()
                .id(workflow.getId())
                .name(workflow.getName())
                .description(workflow.getDescription())
                .status(workflow.getStatus())
                .ownerId(workflow.getOwnerId())
                .ragEnabled(workflow.getRagEnabled())
                .createdAt(workflow.getCreatedAt())
                .updatedAt(workflow.getUpdatedAt())
                .build();
    }

    private WorkflowNodeDTO toNodeDTO(WorkflowNode node) {
        return toNodeDTO(node, null, false);
    }

    private WorkflowNodeDTO toNodeDTO(WorkflowNode node, Long userId, boolean admin) {
        return WorkflowNodeDTO.builder()
                .id(node.getId())
                .nodeId(node.getNodeId())
                .type(node.getType())
                .positionX(node.getPositionX())
                .positionY(node.getPositionY())
                .label(node.getLabel())
                .config(configForDetail(node, userId, admin))
                .build();
    }

    private WorkflowEdgeDTO toEdgeDTO(WorkflowEdge edge) {
        return WorkflowEdgeDTO.builder()
                .id(edge.getId())
                .sourceNodeId(edge.getSourceNodeId())
                .targetNodeId(edge.getTargetNodeId())
                .sourceHandle(edge.getSourceHandle())
                .targetHandle(edge.getTargetHandle())
                .label(edge.getLabel())
                .condition(edge.getCondition())
                .build();
    }

    private Map<String, Object> parseConfig(String config) {
        if (config == null || config.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(config, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工作流节点配置不是合法 JSON");
        }
    }

    private String writeConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "工作流节点配置序列化失败");
        }
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
