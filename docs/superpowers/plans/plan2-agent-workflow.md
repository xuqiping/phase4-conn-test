# Agent模块 + Workflow模块 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**目标：** 实现Agent管理模块（实体/Mapper/DTO/Service/Controller）和Workflow编排模块（实体/Mapper/DTO/Service/Controller），以及Execution预留模块。使Agent数据能从Markdown同步到数据库，工作流能CRUD+节点编排，执行日志可记录。

**前提条件：** Plan 1已完成。Spring Boot项目骨架、13张表DDL、公共代码（BaseEntity、R<T>、PageResult、ErrorCode、BusinessException、GlobalExceptionHandler、MybatisPlusConfig、CorsConfig）、Auth模块全部就绪。`@RequirePermission`注解和`PermissionEvaluator`已实现，JWT过滤器会查询用户完整权限列表并设置为GrantedAuthority。

**架构：** 模块化单体，在auth模块之后新增agent、workflow、execution三个业务模块。MyBatis-Plus的LambdaQueryWrapper查询，所有Controller返回`R<T>`统一响应，权限控制使用`@RequirePermission`。

**技术栈：** Java 17, Spring Boot 3.2.5, MyBatis-Plus 3.5.5, PostgreSQL, Lombok, Jackson

---

## 文件结构

```
agent-platform/backend/src/main/java/com/superprogrammer/
├── agent/
│   ├── entity/
│   │   ├── AgentGroup.java          # agent_groups表实体
│   │   ├── Agent.java               # agents表实体
│   │   ├── Skill.java               # skills表实体
│   │   └── SkillStep.java           # skill_steps表实体
│   ├── mapper/
│   │   ├── AgentGroupMapper.java
│   │   ├── AgentMapper.java
│   │   ├── SkillMapper.java
│   │   └── SkillStepMapper.java
│   ├── dto/
│   │   ├── AgentGroupVO.java        # Agent分组视图
│   │   ├── AgentVO.java             # Agent列表视图
│   │   ├── AgentDetailVO.java       # Agent详情(含skills)
│   │   ├── SkillVO.java             # 技能视图
│   │   └── SkillDetailVO.java       # 技能详情(含steps)
│   ├── service/
│   │   ├── AgentService.java        # Agent CRUD + 搜索筛选
│   │   ├── SkillService.java        # 技能CRUD
│   │   └── MarkdownSyncService.java # Markdown解析+同步引擎
│   └── controller/
│       ├── AgentGroupController.java # /api/agent-groups
│       └── AgentController.java      # /api/agents, /api/skills
├── workflow/
│   ├── entity/
│   │   ├── Workflow.java            # workflows表实体
│   │   ├── WorkflowNode.java        # workflow_nodes表实体
│   │   └── WorkflowEdge.java        # workflow_edges表实体
│   ├── mapper/
│   │   ├── WorkflowMapper.java
│   │   ├── WorkflowNodeMapper.java
│   │   └── WorkflowEdgeMapper.java
│   ├── dto/
│   │   ├── WorkflowVO.java
│   │   ├── WorkflowDetailVO.java    # 含nodes+edges
│   │   ├── WorkflowNodeDTO.java
│   │   ├── WorkflowEdgeDTO.java
│   │   └── WorkflowCreateRequest.java
│   ├── service/
│   │   └── WorkflowService.java     # 工作流CRUD + 节点编排
│   └── controller/
│       └── WorkflowController.java   # /api/workflows
└── execution/
    ├── entity/
    │   └── ExecutionLog.java        # execution_logs表实体
    ├── mapper/
    │   └── ExecutionLogMapper.java
    ├── service/
    │   └── ExecutionLogService.java  # 仅日志记录（预留）
    └── controller/
        └── ExecutionController.java  # /api/executions（预留）
```

---

### Task 1: Agent模块实体 + Mapper
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/AgentGroup.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/Agent.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/Skill.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/SkillStep.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/mapper/AgentGroupMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/mapper/AgentMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/mapper/SkillMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/mapper/SkillStepMapper.java`

- [ ] **Step 1: 创建 AgentGroup.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/AgentGroup.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_groups")
public class AgentGroup extends BaseEntity {

    private String name;

    private String icon;

    private String description;

    private Integer sortOrder;
}
```

- [ ] **Step 2: 创建 Agent.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/Agent.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agents")
public class Agent extends BaseEntity {

    private String name;

    private String description;

    private String avatar;

    private Long groupId;

    private String status;

    private String config;
}
```

- [ ] **Step 3: 创建 Skill.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/Skill.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skills")
public class Skill extends BaseEntity {

    private Long agentId;

    private String name;

    private String description;

    private String type;

    private String config;

    private Integer sortOrder;
}
```

- [ ] **Step 4: 创建 SkillStep.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/entity/SkillStep.java
package com.superprogrammer.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_steps")
public class SkillStep extends BaseEntity {

    private Long skillId;

    private Integer stepOrder;

    private String name;

    private String action;

    private String config;
}
```

- [ ] **Step 5: 创建4个Mapper接口**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/mapper/AgentGroupMapper.java
package com.superprogrammer.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.agent.entity.AgentGroup;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentGroupMapper extends BaseMapper<AgentGroup> {
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/mapper/AgentMapper.java
package com.superprogrammer.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.agent.entity.Agent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentMapper extends BaseMapper<Agent> {
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/mapper/SkillMapper.java
package com.superprogrammer.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.agent.entity.Skill;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkillMapper extends BaseMapper<Skill> {
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/mapper/SkillStepMapper.java
package com.superprogrammer.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.agent.entity.SkillStep;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkillStepMapper extends BaseMapper<SkillStep> {
}
```

- [ ] **Step 6: 验证编译**

```bash
cd e:\workspace\agent-platform\backend
mvn compile -q
```

预期输出：`BUILD SUCCESS`

- [ ] **Step 7: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/agent/
git commit -m "feat: 添加Agent模块实体和Mapper

- AgentGroup/Agent/Skill/SkillStep 4个实体(继承BaseEntity)
- 4个Mapper接口(extends BaseMapper)
- 对应agent_groups/agents/skills/skill_steps 4张表"
```

---

### Task 2: Agent模块DTO + Service
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/AgentGroupVO.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/AgentVO.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/AgentDetailVO.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/SkillVO.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/SkillDetailVO.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/service/AgentService.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/service/SkillService.java`
- Test: `agent-platform/backend/src/test/java/com/superprogrammer/agent/service/AgentServiceTest.java`

- [ ] **Step 1: 创建DTO**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/AgentGroupVO.java
package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentGroupVO {

    private Long id;
    private String name;
    private String icon;
    private String description;
    private Integer sortOrder;
    private Long agentCount;
    private LocalDateTime createdAt;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/AgentVO.java
package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentVO {

    private Long id;
    private String name;
    private String description;
    private String avatar;
    private String status;
    private Long groupId;
    private String groupName;
    private Integer skillCount;
    private LocalDateTime createdAt;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/AgentDetailVO.java
package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDetailVO {

    private Long id;
    private String name;
    private String description;
    private String avatar;
    private String status;
    private String config;
    private Long groupId;
    private String groupName;
    private List<SkillVO> skills;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/SkillVO.java
package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVO {

    private Long id;
    private String name;
    private String description;
    private String type;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/SkillDetailVO.java
package com.superprogrammer.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDetailVO {

    private Long id;
    private Long agentId;
    private String agentName;
    private String name;
    private String description;
    private String type;
    private String config;
    private Integer sortOrder;
    private List<SkillStepVO> steps;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillStepVO {
        private Long id;
        private Integer stepOrder;
        private String name;
        private String action;
        private String config;
    }
}
```

- [ ] **Step 2: 创建 SkillService.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/service/SkillService.java
package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.SkillDetailVO;
import com.superprogrammer.agent.dto.SkillVO;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillMapper skillMapper;
    private final SkillStepMapper skillStepMapper;
    private final AgentMapper agentMapper;

    /**
     * 查询指定Agent下的技能列表
     */
    public List<SkillVO> listByAgentId(Long agentId) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Skill::getAgentId, agentId)
                .orderByAsc(Skill::getSortOrder);
        List<Skill> skills = skillMapper.selectList(wrapper);
        return skills.stream()
                .map(this::toSkillVO)
                .collect(Collectors.toList());
    }

    /**
     * 获取技能详情（含步骤）
     */
    public SkillDetailVO getDetail(Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "技能不存在");
        }

        // 查询所属Agent
        Agent agent = agentMapper.selectById(skill.getAgentId());
        String agentName = agent != null ? agent.getName() : null;

        // 查询步骤
        LambdaQueryWrapper<SkillStep> stepWrapper = new LambdaQueryWrapper<>();
        stepWrapper.eq(SkillStep::getSkillId, skillId)
                .orderByAsc(SkillStep::getStepOrder);
        List<SkillStep> steps = skillStepMapper.selectList(stepWrapper);

        List<SkillDetailVO.SkillStepVO> stepVOs = steps.stream()
                .map(step -> SkillDetailVO.SkillStepVO.builder()
                        .id(step.getId())
                        .stepOrder(step.getStepOrder())
                        .name(step.getName())
                        .action(step.getAction())
                        .config(step.getConfig())
                        .build())
                .collect(Collectors.toList());

        return SkillDetailVO.builder()
                .id(skill.getId())
                .agentId(skill.getAgentId())
                .agentName(agentName)
                .name(skill.getName())
                .description(skill.getDescription())
                .type(skill.getType())
                .config(skill.getConfig())
                .sortOrder(skill.getSortOrder())
                .steps(stepVOs)
                .createdAt(skill.getCreatedAt())
                .updatedAt(skill.getUpdatedAt())
                .build();
    }

    private SkillVO toSkillVO(Skill skill) {
        return SkillVO.builder()
                .id(skill.getId())
                .name(skill.getName())
                .description(skill.getDescription())
                .type(skill.getType())
                .sortOrder(skill.getSortOrder())
                .createdAt(skill.getCreatedAt())
                .build();
    }
}
```

- [ ] **Step 3: 创建 AgentService.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/service/AgentService.java
package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.*;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentGroup;
import com.superprogrammer.agent.mapper.AgentGroupMapper;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentGroupMapper agentGroupMapper;
    private final AgentMapper agentMapper;
    private final SkillMapper skillMapper;
    private final SkillService skillService;

    /**
     * 查询所有Agent分组（含每个分组下的Agent数量）
     */
    public List<AgentGroupVO> listGroups() {
        LambdaQueryWrapper<AgentGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(AgentGroup::getSortOrder);
        List<AgentGroup> groups = agentGroupMapper.selectList(wrapper);

        return groups.stream()
                .map(group -> {
                    // 查询分组下的Agent数量
                    LambdaQueryWrapper<Agent> countWrapper = new LambdaQueryWrapper<>();
                    countWrapper.eq(Agent::getGroupId, group.getId());
                    Long count = agentMapper.selectCount(countWrapper);

                    return AgentGroupVO.builder()
                            .id(group.getId())
                            .name(group.getName())
                            .icon(group.getIcon())
                            .description(group.getDescription())
                            .sortOrder(group.getSortOrder())
                            .agentCount(count)
                            .createdAt(group.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 查询Agent列表（支持按分组和关键词筛选）
     */
    public List<AgentVO> listAgents(Long groupId, String keyword) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(groupId != null, Agent::getGroupId, groupId)
                .and(keyword != null && !keyword.isBlank(), w ->
                        w.like(Agent::getName, keyword)
                                .or()
                                .like(Agent::getDescription, keyword))
                .orderByDesc(Agent::getCreatedAt);
        List<Agent> agents = agentMapper.selectList(wrapper);

        return agents.stream()
                .map(agent -> {
                    // 查询分组名称
                    AgentGroup group = agentGroupMapper.selectById(agent.getGroupId());
                    String groupName = group != null ? group.getName() : null;

                    // 查询技能数量
                    LambdaQueryWrapper<com.superprogrammer.agent.entity.Skill> skillCountWrapper = new LambdaQueryWrapper<>();
                    skillCountWrapper.eq(com.superprogrammer.agent.entity.Skill::getAgentId, agent.getId());
                    Long skillCount = skillMapper.selectCount(skillCountWrapper);

                    return AgentVO.builder()
                            .id(agent.getId())
                            .name(agent.getName())
                            .description(agent.getDescription())
                            .avatar(agent.getAvatar())
                            .status(agent.getStatus())
                            .groupId(agent.getGroupId())
                            .groupName(groupName)
                            .skillCount(skillCount.intValue())
                            .createdAt(agent.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取Agent详情（含技能列表）
     */
    public AgentDetailVO getAgentDetail(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent不存在");
        }

        AgentGroup group = agentGroupMapper.selectById(agent.getGroupId());
        String groupName = group != null ? group.getName() : null;

        List<SkillVO> skills = skillService.listByAgentId(agentId);

        return AgentDetailVO.builder()
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .avatar(agent.getAvatar())
                .status(agent.getStatus())
                .config(agent.getConfig())
                .groupId(agent.getGroupId())
                .groupName(groupName)
                .skills(skills)
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .build();
    }

    /**
     * 获取技能详情（委托给SkillService）
     */
    public SkillDetailVO getSkillDetail(Long skillId) {
        return skillService.getDetail(skillId);
    }
}
```

- [ ] **Step 4: 写失败测试**

```java
// agent-platform/backend/src/test/java/com/superprogrammer/agent/service/AgentServiceTest.java
package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.agent.dto.*;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentGroup;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.mapper.AgentGroupMapper;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.agent.mapper.SkillMapper;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private AgentGroupMapper agentGroupMapper;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private SkillMapper skillMapper;

    @Mock
    private SkillStepMapper skillStepMapper;

    @InjectMocks
    private SkillService skillService;

    @InjectMocks
    private AgentService agentService;

    private AgentGroup testGroup;
    private Agent testAgent;
    private Skill testSkill;

    @BeforeEach
    void setUp() {
        testGroup = new AgentGroup();
        testGroup.setId(1L);
        testGroup.setName("通用助手");
        testGroup.setIcon("robot");
        testGroup.setDescription("通用对话和问答类Agent");
        testGroup.setSortOrder(1);
        testGroup.setCreatedAt(LocalDateTime.now());

        testAgent = new Agent();
        testAgent.setId(1L);
        testAgent.setName("代码助手");
        testAgent.setDescription("帮助编写和调试代码");
        testAgent.setGroupId(1L);
        testAgent.setStatus("PUBLISHED");
        testAgent.setCreatedAt(LocalDateTime.now());
        testAgent.setUpdatedAt(LocalDateTime.now());

        testSkill = new Skill();
        testSkill.setId(1L);
        testSkill.setAgentId(1L);
        testSkill.setName("代码生成");
        testSkill.setDescription("根据需求生成代码");
        testSkill.setType("SEQUENCE");
        testSkill.setSortOrder(1);
        testSkill.setCreatedAt(LocalDateTime.now());
    }

    @Test
    void listGroups_returnsAllGroups() {
        when(agentGroupMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testGroup));
        when(agentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);

        List<AgentGroupVO> result = agentService.listGroups();

        assertEquals(1, result.size());
        assertEquals("通用助手", result.get(0).getName());
        assertEquals(5L, result.get(0).getAgentCount());
    }

    @Test
    void listAgents_byGroupId_filtersCorrectly() {
        when(agentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testAgent));
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);

        List<AgentVO> result = agentService.listAgents(1L, null);

        assertEquals(1, result.size());
        assertEquals("代码助手", result.get(0).getName());
        assertEquals("通用助手", result.get(0).getGroupName());
        assertEquals(3, result.get(0).getSkillCount());
    }

    @Test
    void listAgents_byKeyword_filtersCorrectly() {
        when(agentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testAgent));
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        List<AgentVO> result = agentService.listAgents(null, "代码");

        assertEquals(1, result.size());
        assertEquals("代码助手", result.get(0).getName());
    }

    @Test
    void listAgents_noFilters_returnsAll() {
        when(agentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testAgent));
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        List<AgentVO> result = agentService.listAgents(null, null);

        assertEquals(1, result.size());
    }

    @Test
    void getAgentDetail_success() {
        when(agentMapper.selectById(1L)).thenReturn(testAgent);
        when(agentGroupMapper.selectById(1L)).thenReturn(testGroup);
        when(skillMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testSkill));

        AgentDetailVO result = agentService.getAgentDetail(1L);

        assertEquals("代码助手", result.getName());
        assertEquals("通用助手", result.getGroupName());
        assertEquals("PUBLISHED", result.getStatus());
        assertNotNull(result.getSkills());
        assertEquals(1, result.getSkills().size());
        assertEquals("代码生成", result.getSkills().get(0).getName());
    }

    @Test
    void getAgentDetail_notFound_throwsException() {
        when(agentMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> agentService.getAgentDetail(999L));
    }

    @Test
    void getSkillDetail_success() {
        when(skillMapper.selectById(1L)).thenReturn(testSkill);
        when(agentMapper.selectById(1L)).thenReturn(testAgent);
        when(skillStepMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        SkillDetailVO result = agentService.getSkillDetail(1L);

        assertEquals("代码生成", result.getName());
        assertEquals("代码助手", result.getAgentName());
        assertNotNull(result.getSteps());
    }

    @Test
    void getSkillDetail_notFound_throwsException() {
        when(skillMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> agentService.getSkillDetail(999L));
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=AgentServiceTest -q
```

预期输出：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/agent/dto/
git add agent-platform/backend/src/main/java/com/superprogrammer/agent/service/
git add agent-platform/backend/src/test/java/com/superprogrammer/agent/service/AgentServiceTest.java
git commit -m "feat: 实现Agent模块DTO和Service

- 5个VO/DTO: AgentGroupVO/AgentVO/AgentDetailVO/SkillVO/SkillDetailVO
- AgentService: listGroups/listAgents(分组+关键词筛选)/getAgentDetail/getSkillDetail
- SkillService: listByAgentId/getDetail(含步骤)
- 8个单元测试全部通过"
```

---

### Task 3: Markdown同步引擎
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/service/MarkdownSyncService.java`
- Test: `agent-platform/backend/src/test/java/com/superprogrammer/agent/service/MarkdownSyncServiceTest.java`

**说明：** Markdown同步引擎负责解析Agent的Markdown配置文件（skills-router.md和workflow文件），将数据同步到数据库。同步策略为upsert（存在则更新，不存在则插入），Markdown中没有的DB记录保留不删除。

**Markdown文件约定：**
- 顶层`skills-router.md`：定义Agent分组和Agent列表
- 每个Agent子目录下有`skills-router.md`：定义该Agent的技能列表
- workflow文件定义技能步骤

**顶层skills-router.md格式示例：**
```markdown
# Agent 路由

## 通用助手

| Agent | 描述 |
|-------|------|
| 代码助手 | 帮助编写和调试代码 |
| 翻译助手 | 多语言翻译 |

## 数据分析

| Agent | 描述 |
|-------|------|
| SQL助手 | SQL查询生成与优化 |
```

- [ ] **Step 1: 写失败测试**

```java
// agent-platform/backend/src/test/java/com/superprogrammer/agent/service/MarkdownSyncServiceTest.java
package com.superprogrammer.agent.service;

import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentGroup;
import com.superprogrammer.agent.entity.Skill;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarkdownSyncServiceTest {

    @Mock
    private AgentGroupMapper agentGroupMapper;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private SkillMapper skillMapper;

    @Mock
    private SkillStepMapper skillStepMapper;

    @InjectMocks
    private MarkdownSyncService markdownSyncService;

    private String topLevelRouterContent;
    private String subAgentRouterContent;

    @BeforeEach
    void setUp() {
        topLevelRouterContent = "# Agent 路由\n" +
                "\n" +
                "## 通用助手\n" +
                "\n" +
                "| Agent | 描述 |\n" +
                "|-------|------|\n" +
                "| 代码助手 | 帮助编写和调试代码 |\n" +
                "| 翻译助手 | 多语言翻译 |\n" +
                "\n" +
                "## 数据分析\n" +
                "\n" +
                "| Agent | 描述 |\n" +
                "|-------|------|\n" +
                "| SQL助手 | SQL查询生成与优化 |";

        subAgentRouterContent = "# 代码助手 技能路由\n" +
                "\n" +
                "## 代码生成\n" +
                "\n" +
                "**类型:** SEQUENCE\n" +
                "\n" +
                "**描述:** 根据自然语言需求生成代码\n" +
                "\n" +
                "### 步骤\n" +
                "\n" +
                "| 序号 | 名称 | 动作 |\n" +
                "|------|------|------|\n" +
                "| 1 | 理解需求 | LLM_CALL |\n" +
                "| 2 | 生成代码 | LLM_CALL |\n" +
                "| 3 | 代码审查 | LLM_CALL |\n" +
                "\n" +
                "## 代码调试\n" +
                "\n" +
                "**类型:** SEQUENCE\n" +
                "\n" +
                "**描述:** 分析和修复代码错误";
    }

    @Test
    void parseTopLevelRouter_extractsGroupsAndAgents() {
        // 模拟分组不存在（首次同步）
        when(agentGroupMapper.selectOne(any())).thenReturn(null);
        when(agentGroupMapper.insert(any())).thenReturn(1);
        when(agentMapper.selectOne(any())).thenReturn(null);
        when(agentMapper.insert(any())).thenReturn(1);

        int count = markdownSyncService.parseTopLevelRouter(topLevelRouterContent, 1L);

        // 应解析出2个分组：通用助手、数据分析
        // 每个分组下各有2和1个Agent
        verify(agentGroupMapper, times(2)).insert(any(AgentGroup.class));
        verify(agentMapper, times(3)).insert(any(Agent.class));
        assertTrue(count > 0);
    }

    @Test
    void parseTopLevelRouter_existingGroup_updated() {
        AgentGroup existingGroup = new AgentGroup();
        existingGroup.setId(1L);
        existingGroup.setName("通用助手");

        when(agentGroupMapper.selectOne(any())).thenReturn(existingGroup);
        when(agentGroupMapper.updateById(any())).thenReturn(1);
        when(agentMapper.selectOne(any())).thenReturn(null);
        when(agentMapper.insert(any())).thenReturn(1);

        markdownSyncService.parseTopLevelRouter(topLevelRouterContent, 1L);

        // 已存在的分组应该被更新而不是插入
        verify(agentGroupMapper).updateById(any(AgentGroup.class));
        verify(agentGroupMapper, never()).insert(any(AgentGroup.class));
    }

    @Test
    void parseSubAgentRouter_extractsSkillsAndSteps() {
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setName("代码助手");

        when(skillMapper.selectOne(any())).thenReturn(null);
        when(skillMapper.insert(any())).thenReturn(1);
        when(skillStepMapper.selectOne(any())).thenReturn(null);
        when(skillStepMapper.insert(any())).thenReturn(1);

        int count = markdownSyncService.parseSubAgentRouter(subAgentRouterContent, agent, 1L);

        // 应解析出2个技能：代码生成(含3个步骤)、代码调试(无步骤)
        ArgumentCaptor<Skill> skillCaptor = ArgumentCaptor.forClass(Skill.class);
        verify(skillMapper, times(2)).insert(skillCaptor.capture());

        List<Skill> insertedSkills = skillCaptor.getAllValues();
        assertEquals("代码生成", insertedSkills.get(0).getName());
        assertEquals("SEQUENCE", insertedSkills.get(0).getType());
        assertEquals("代码调试", insertedSkills.get(1).getName());

        // 代码生成技能有3个步骤
        verify(skillStepMapper, times(3)).insert(any(SkillStep.class));
        assertTrue(count > 0);
    }

    @Test
    void parseTopLevelRouter_emptyContent_returnsZero() {
        int count = markdownSyncService.parseTopLevelRouter("", 1L);
        assertEquals(0, count);
    }

    @Test
    void parseTopLevelRouter_noTables_returnsZero() {
        int count = markdownSyncService.parseTopLevelRouter("# 标题\n\n没有表格内容", 1L);
        assertEquals(0, count);
    }

    @Test
    void parseSubAgentRouter_emptyContent_returnsZero() {
        Agent agent = new Agent();
        agent.setId(1L);
        int count = markdownSyncService.parseSubAgentRouter("", agent, 1L);
        assertEquals(0, count);
    }

    @Test
    void parseSubAgentRouter_existingSkill_updated() {
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setName("代码助手");

        Skill existingSkill = new Skill();
        existingSkill.setId(10L);
        existingSkill.setName("代码生成");
        existingSkill.setAgentId(1L);

        when(skillMapper.selectOne(any())).thenReturn(existingSkill);
        when(skillMapper.updateById(any())).thenReturn(1);
        when(skillStepMapper.selectOne(any())).thenReturn(null);
        when(skillStepMapper.insert(any())).thenReturn(1);

        markdownSyncService.parseSubAgentRouter(subAgentRouterContent, agent, 1L);

        // 代码生成技能已存在，应该被更新
        verify(skillMapper).updateById(any(Skill.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=MarkdownSyncServiceTest -q 2>&1 | tail -5
```

预期：编译失败（MarkdownSyncService类不存在）

- [ ] **Step 3: 写最小实现**

```java
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
```

- [ ] **Step 4: 在application.yml中添加配置项**

在`agent-platform/backend/src/main/resources/application.yml`末尾追加：

```yaml
# Agent Markdown数据目录（可选，配置后启用Markdown同步功能）
agent:
  data-path: ${AGENT_DATA_PATH:}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=MarkdownSyncServiceTest -q
```

预期输出：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/agent/service/MarkdownSyncService.java
git add agent-platform/backend/src/test/java/com/superprogrammer/agent/service/MarkdownSyncServiceTest.java
git add agent-platform/backend/src/main/resources/application.yml
git commit -m "feat: 实现Markdown同步引擎

- MarkdownSyncService: 解析skills-router.md同步Agent/技能/步骤到数据库
- parseTopLevelRouter: 解析顶层路由 → agent_groups + agents
- parseSubAgentRouter: 解析子Agent路由 → skills + skill_steps
- upsert逻辑: 按名称匹配，存在则更新，不存在则插入
- 配置: agent.data-path 指向Markdown文件目录
- 8个单元测试全部通过"
```

---

### Task 4: Agent Controller
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/controller/AgentGroupController.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/agent/controller/AgentController.java`
- Test: `agent-platform/backend/src/test/java/com/superprogrammer/agent/controller/AgentControllerTest.java`

- [ ] **Step 1: 创建 AgentGroupController.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/controller/AgentGroupController.java
package com.superprogrammer.agent.controller;

import com.superprogrammer.agent.dto.AgentGroupVO;
import com.superprogrammer.agent.service.AgentService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent-groups")
@RequiredArgsConstructor
public class AgentGroupController {

    private final AgentService agentService;

    @GetMapping
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<AgentGroupVO>>> listGroups() {
        List<AgentGroupVO> groups = agentService.listGroups();
        return ResponseEntity.ok(R.ok(groups));
    }
}
```

- [ ] **Step 2: 创建 AgentController.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/agent/controller/AgentController.java
package com.superprogrammer.agent.controller;

import com.superprogrammer.agent.dto.AgentDetailVO;
import com.superprogrammer.agent.dto.AgentVO;
import com.superprogrammer.agent.dto.SkillDetailVO;
import com.superprogrammer.agent.dto.SkillVO;
import com.superprogrammer.agent.service.AgentService;
import com.superprogrammer.agent.service.MarkdownSyncService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final MarkdownSyncService markdownSyncService;

    /**
     * 查询Agent列表（支持按分组和关键词筛选）
     */
    @GetMapping("/agents")
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<AgentVO>>> listAgents(
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String keyword) {
        List<AgentVO> agents = agentService.listAgents(groupId, keyword);
        return ResponseEntity.ok(R.ok(agents));
    }

    /**
     * 获取Agent详情（含技能列表）
     */
    @GetMapping("/agents/{id}")
    @RequirePermission("agent:read")
    public ResponseEntity<R<AgentDetailVO>> getAgentDetail(@PathVariable Long id) {
        AgentDetailVO detail = agentService.getAgentDetail(id);
        return ResponseEntity.ok(R.ok(detail));
    }

    /**
     * 查询指定Agent下的技能列表
     */
    @GetMapping("/agents/{id}/skills")
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<SkillVO>>> listAgentSkills(@PathVariable Long id) {
        List<SkillVO> skills = agentService.getAgentDetail(id).getSkills();
        return ResponseEntity.ok(R.ok(skills));
    }

    /**
     * 获取技能详情（含步骤）
     */
    @GetMapping("/skills/{id}")
    @RequirePermission("agent:read")
    public ResponseEntity<R<SkillDetailVO>> getSkillDetail(@PathVariable Long id) {
        SkillDetailVO detail = agentService.getSkillDetail(id);
        return ResponseEntity.ok(R.ok(detail));
    }

    /**
     * 触发Markdown同步（需admin或agent_admin权限）
     */
    @PostMapping("/agents/sync")
    @RequirePermission("agent:create")
    public ResponseEntity<R<Integer>> syncFromMarkdown() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long operatorId = (Long) authentication.getPrincipal();
        int count = markdownSyncService.syncAll(operatorId);
        return ResponseEntity.ok(R.ok("同步完成", count));
    }
}
```

- [ ] **Step 3: 写Controller测试**

```java
// agent-platform/backend/src/test/java/com/superprogrammer/agent/controller/AgentControllerTest.java
package com.superprogrammer.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.agent.dto.*;
import com.superprogrammer.agent.service.AgentService;
import com.superprogrammer.agent.service.MarkdownSyncService;
import com.superprogrammer.auth.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentService agentService;

    @MockBean
    private MarkdownSyncService markdownSyncService;

    @MockBean
    private JwtUtil jwtUtil;

    @Test
    void listAgents_returnsAgentList() throws Exception {
        AgentVO agentVO = AgentVO.builder()
                .id(1L)
                .name("代码助手")
                .description("帮助编写和调试代码")
                .status("PUBLISHED")
                .groupId(1L)
                .groupName("通用助手")
                .skillCount(3)
                .createdAt(LocalDateTime.now())
                .build();
        when(agentService.listAgents(isNull(), isNull()))
                .thenReturn(Arrays.asList(agentVO));

        mockMvc.perform(get("/api/agents")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("代码助手"))
                .andExpect(jsonPath("$.data[0].groupName").value("通用助手"));
    }

    @Test
    void listAgents_withGroupId_filtersCorrectly() throws Exception {
        when(agentService.listAgents(eq(1L), isNull()))
                .thenReturn(Arrays.asList());

        mockMvc.perform(get("/api/agents?groupId=1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void listAgents_withKeyword_filtersCorrectly() throws Exception {
        when(agentService.listAgents(isNull(), eq("代码")))
                .thenReturn(Arrays.asList());

        mockMvc.perform(get("/api/agents?keyword=代码")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void getAgentDetail_returnsDetail() throws Exception {
        AgentDetailVO detailVO = AgentDetailVO.builder()
                .id(1L)
                .name("代码助手")
                .description("帮助编写和调试代码")
                .status("PUBLISHED")
                .groupId(1L)
                .groupName("通用助手")
                .skills(Arrays.asList(
                        SkillVO.builder().id(1L).name("代码生成").type("SEQUENCE").build()
                ))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(agentService.getAgentDetail(1L)).thenReturn(detailVO);

        mockMvc.perform(get("/api/agents/1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("代码助手"))
                .andExpect(jsonPath("$.data.skills[0].name").value("代码生成"));
    }

    @Test
    void getSkillDetail_returnsDetailWithSteps() throws Exception {
        SkillDetailVO detailVO = SkillDetailVO.builder()
                .id(1L)
                .agentId(1L)
                .agentName("代码助手")
                .name("代码生成")
                .type("SEQUENCE")
                .steps(Arrays.asList(
                        SkillDetailVO.SkillStepVO.builder()
                                .stepOrder(1)
                                .name("理解需求")
                                .action("LLM_CALL")
                                .build()
                ))
                .build();
        when(agentService.getSkillDetail(1L)).thenReturn(detailVO);

        mockMvc.perform(get("/api/skills/1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("代码生成"))
                .andExpect(jsonPath("$.data.steps[0].action").value("LLM_CALL"));
    }

    @Test
    void syncFromMarkdown_returnsSyncCount() throws Exception {
        when(markdownSyncService.syncAll(anyLong())).thenReturn(10);

        mockMvc.perform(post("/api/agents/sync")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(10));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=AgentControllerTest -q
```

预期输出：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/agent/controller/
git add agent-platform/backend/src/test/java/com/superprogrammer/agent/controller/AgentControllerTest.java
git commit -m "feat: 实现Agent模块Controller

- AgentGroupController: GET /api/agent-groups
- AgentController: GET /api/agents(分组+关键词筛选), GET /api/agents/{id}(详情),
  GET /api/agents/{id}/skills, GET /api/skills/{id}(含步骤), POST /api/agents/sync(Markdown同步)
- 所有接口使用@RequirePermission权限控制
- 6个MockMvc测试全部通过"
```

---

### Task 5: Workflow模块实体 + Mapper
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/entity/Workflow.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/entity/WorkflowNode.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/entity/WorkflowEdge.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/mapper/WorkflowMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/mapper/WorkflowNodeMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/mapper/WorkflowEdgeMapper.java`

- [ ] **Step 1: 创建 Workflow.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/entity/Workflow.java
package com.superprogrammer.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflows")
public class Workflow extends BaseEntity {

    private String name;

    private String description;

    private String status;

    private Long ownerId;
}
```

- [ ] **Step 2: 创建 WorkflowNode.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/entity/WorkflowNode.java
package com.superprogrammer.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_nodes")
public class WorkflowNode extends BaseEntity {

    private Long workflowId;

    private String nodeId;

    private String type;

    private Double positionX;

    private Double positionY;

    private String label;

    private String config;
}
```

- [ ] **Step 3: 创建 WorkflowEdge.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/entity/WorkflowEdge.java
package com.superprogrammer.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_edges")
public class WorkflowEdge extends BaseEntity {

    private Long workflowId;

    private String sourceNodeId;

    private String targetNodeId;

    private String sourceHandle;

    private String targetHandle;

    private String label;

    private String condition;
}
```

- [ ] **Step 4: 创建3个Mapper接口**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/mapper/WorkflowMapper.java
package com.superprogrammer.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.workflow.entity.Workflow;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowMapper extends BaseMapper<Workflow> {
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/mapper/WorkflowNodeMapper.java
package com.superprogrammer.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.workflow.entity.WorkflowNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowNodeMapper extends BaseMapper<WorkflowNode> {
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/mapper/WorkflowEdgeMapper.java
package com.superprogrammer.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.workflow.entity.WorkflowEdge;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowEdgeMapper extends BaseMapper<WorkflowEdge> {
}
```

- [ ] **Step 5: 验证编译**

```bash
cd e:\workspace\agent-platform\backend
mvn compile -q
```

预期输出：`BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/workflow/
git commit -m "feat: 添加Workflow模块实体和Mapper

- Workflow/WorkflowNode/WorkflowEdge 3个实体(继承BaseEntity)
- 3个Mapper接口(extends BaseMapper)
- 对应workflows/workflow_nodes/workflow_edges 3张表"
```

---

### Task 6: Workflow模块DTO + Service
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowVO.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowDetailVO.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowNodeDTO.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowEdgeDTO.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowCreateRequest.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/service/WorkflowService.java`
- Test: `agent-platform/backend/src/test/java/com/superprogrammer/workflow/service/WorkflowServiceTest.java`

- [ ] **Step 1: 创建DTO**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowVO.java
package com.superprogrammer.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowVO {

    private Long id;
    private String name;
    private String description;
    private String status;
    private Long ownerId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowDetailVO.java
package com.superprogrammer.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDetailVO {

    private Long id;
    private String name;
    private String description;
    private String status;
    private Long ownerId;
    private List<WorkflowNodeDTO> nodes;
    private List<WorkflowEdgeDTO> edges;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowNodeDTO.java
package com.superprogrammer.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowNodeDTO {

    private Long id;
    private String nodeId;
    private String type;
    private Double positionX;
    private Double positionY;
    private String label;
    private String config;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowEdgeDTO.java
package com.superprogrammer.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowEdgeDTO {

    private Long id;
    private String sourceNodeId;
    private String targetNodeId;
    private String sourceHandle;
    private String targetHandle;
    private String label;
    private String condition;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/WorkflowCreateRequest.java
package com.superprogrammer.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowCreateRequest {

    @NotBlank(message = "工作流名称不能为空")
    private String name;

    private String description;

    private List<WorkflowNodeDTO> nodes;

    private List<WorkflowEdgeDTO> edges;
}
```

- [ ] **Step 2: 写失败测试**

```java
// agent-platform/backend/src/test/java/com/superprogrammer/workflow/service/WorkflowServiceTest.java
package com.superprogrammer.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.workflow.dto.*;
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.entity.WorkflowEdge;
import com.superprogrammer.workflow.entity.WorkflowNode;
import com.superprogrammer.workflow.mapper.WorkflowEdgeMapper;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import com.superprogrammer.workflow.mapper.WorkflowNodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private WorkflowNodeMapper workflowNodeMapper;

    @Mock
    private WorkflowEdgeMapper workflowEdgeMapper;

    @InjectMocks
    private WorkflowService workflowService;

    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        testWorkflow = new Workflow();
        testWorkflow.setId(1L);
        testWorkflow.setName("测试工作流");
        testWorkflow.setDescription("用于测试的工作流");
        testWorkflow.setStatus("DRAFT");
        testWorkflow.setOwnerId(1L);
        testWorkflow.setCreatedAt(LocalDateTime.now());
        testWorkflow.setUpdatedAt(LocalDateTime.now());
    }

    @Test
    void listWorkflows_returnsUserWorkflows() {
        when(workflowMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(testWorkflow));

        List<WorkflowVO> result = workflowService.listWorkflows(1L);

        assertEquals(1, result.size());
        assertEquals("测试工作流", result.get(0).getName());
        assertEquals("DRAFT", result.get(0).getStatus());
    }

    @Test
    void getWorkflowDetail_success() {
        WorkflowNode startNode = new WorkflowNode();
        startNode.setId(1L);
        startNode.setWorkflowId(1L);
        startNode.setNodeId("node-start");
        startNode.setType("START");
        startNode.setPositionX(100.0);
        startNode.setPositionY(100.0);
        startNode.setLabel("开始");

        WorkflowNode endNode = new WorkflowNode();
        endNode.setId(2L);
        endNode.setWorkflowId(1L);
        endNode.setNodeId("node-end");
        endNode.setType("END");
        endNode.setPositionX(500.0);
        endNode.setPositionY(100.0);
        endNode.setLabel("结束");

        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(1L);
        edge.setWorkflowId(1L);
        edge.setSourceNodeId("node-start");
        edge.setTargetNodeId("node-end");

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(startNode, endNode));
        when(workflowEdgeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(edge));

        WorkflowDetailVO result = workflowService.getWorkflowDetail(1L);

        assertEquals("测试工作流", result.getName());
        assertEquals(2, result.getNodes().size());
        assertEquals(1, result.getEdges().size());
        assertEquals("START", result.getNodes().get(0).getType());
        assertEquals("END", result.getNodes().get(1).getType());
        assertEquals("node-start", result.getEdges().get(0).getSourceNodeId());
    }

    @Test
    void getWorkflowDetail_notFound_throwsException() {
        when(workflowMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> workflowService.getWorkflowDetail(999L));
    }

    @Test
    void createWorkflow_success_autoGeneratesStartEndNodes() {
        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("新建工作流")
                .description("测试创建")
                .build();

        when(workflowMapper.insert(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow w = invocation.getArgument(0);
            w.setId(1L);
            return 1;
        });
        when(workflowNodeMapper.insert(any(WorkflowNode.class))).thenReturn(1);

        WorkflowVO result = workflowService.createWorkflow(request, 1L);

        assertEquals("新建工作流", result.getName());

        // 验证自动生成了开始和结束节点
        ArgumentCaptor<WorkflowNode> nodeCaptor = ArgumentCaptor.forClass(WorkflowNode.class);
        verify(workflowNodeMapper, times(2)).insert(nodeCaptor.capture());

        List<WorkflowNode> insertedNodes = nodeCaptor.getAllValues();
        assertEquals("START", insertedNodes.get(0).getType());
        assertEquals("END", insertedNodes.get(1).getType());
    }

    @Test
    void updateWorkflow_success() {
        WorkflowNodeDTO nodeDTO = WorkflowNodeDTO.builder()
                .nodeId("node-1")
                .type("AGENT")
                .positionX(200.0)
                .positionY(200.0)
                .label("Agent节点")
                .build();

        WorkflowEdgeDTO edgeDTO = WorkflowEdgeDTO.builder()
                .sourceNodeId("node-start")
                .targetNodeId("node-1")
                .build();

        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("更新后的工作流")
                .description("更新描述")
                .nodes(Arrays.asList(nodeDTO))
                .edges(Arrays.asList(edgeDTO))
                .build();

        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowMapper.updateById(any(Workflow.class))).thenReturn(1);
        // 先删除旧节点和边
        when(workflowNodeMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(2);
        when(workflowEdgeMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        when(workflowNodeMapper.insert(any(WorkflowNode.class))).thenReturn(1);
        when(workflowEdgeMapper.insert(any(WorkflowEdge.class))).thenReturn(1);

        WorkflowVO result = workflowService.updateWorkflow(1L, request, 1L);

        assertEquals("更新后的工作流", result.getName());
        verify(workflowNodeMapper).delete(any(LambdaQueryWrapper.class));
        verify(workflowEdgeMapper).delete(any(LambdaQueryWrapper.class));
        verify(workflowNodeMapper).insert(any(WorkflowNode.class));
        verify(workflowEdgeMapper).insert(any(WorkflowEdge.class));
    }

    @Test
    void updateWorkflow_notFound_throwsException() {
        when(workflowMapper.selectById(999L)).thenReturn(null);

        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("test")
                .build();

        assertThrows(BusinessException.class,
                () -> workflowService.updateWorkflow(999L, request, 1L));
    }

    @Test
    void deleteWorkflow_success() {
        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowMapper.deleteById(1L)).thenReturn(1);

        assertDoesNotThrow(() -> workflowService.deleteWorkflow(1L, 1L));
        verify(workflowMapper).deleteById(1L);
    }

    @Test
    void deleteWorkflow_notOwner_throwsException() {
        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);

        assertThrows(BusinessException.class,
                () -> workflowService.deleteWorkflow(1L, 999L));
    }

    @Test
    void duplicateWorkflow_success() {
        when(workflowMapper.selectById(1L)).thenReturn(testWorkflow);
        when(workflowMapper.insert(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow w = invocation.getArgument(0);
            w.setId(2L);
            return 1;
        });
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        when(workflowEdgeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        when(workflowNodeMapper.insert(any(WorkflowNode.class))).thenReturn(1);

        WorkflowVO result = workflowService.duplicateWorkflow(1L, 1L);

        assertNotNull(result);
        verify(workflowMapper).insert(any(Workflow.class));
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=WorkflowServiceTest -q 2>&1 | tail -5
```

预期：编译失败（WorkflowService类不存在）

- [ ] **Step 4: 写最小实现**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/service/WorkflowService.java
package com.superprogrammer.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.workflow.dto.*;
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowEdgeMapper workflowEdgeMapper;

    /**
     * 查询当前用户的工作流列表
     */
    public List<WorkflowVO> listWorkflows(Long userId) {
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Workflow::getOwnerId, userId)
                .orderByDesc(Workflow::getUpdatedAt);
        List<Workflow> workflows = workflowMapper.selectList(wrapper);

        return workflows.stream()
                .map(this::toWorkflowVO)
                .collect(Collectors.toList());
    }

    /**
     * 获取工作流详情（含nodes和edges）
     */
    public WorkflowDetailVO getWorkflowDetail(Long id) {
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
                .nodes(nodes.stream().map(this::toNodeDTO).collect(Collectors.toList()))
                .edges(edges.stream().map(this::toEdgeDTO).collect(Collectors.toList()))
                .createdAt(workflow.getCreatedAt())
                .updatedAt(workflow.getUpdatedAt())
                .build();
    }

    /**
     * 创建工作流，自动生成开始/结束节点
     */
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

        // 自动生成开始节点
        WorkflowNode startNode = new WorkflowNode();
        startNode.setWorkflowId(workflow.getId());
        startNode.setNodeId(UUID.randomUUID().toString());
        startNode.setType("START");
        startNode.setPositionX(100.0);
        startNode.setPositionY(300.0);
        startNode.setLabel("开始");
        startNode.setCreatedBy(userId);
        startNode.setUpdatedBy(userId);
        workflowNodeMapper.insert(startNode);

        // 自动生成结束节点
        WorkflowNode endNode = new WorkflowNode();
        endNode.setWorkflowId(workflow.getId());
        endNode.setNodeId(UUID.randomUUID().toString());
        endNode.setType("END");
        endNode.setPositionX(800.0);
        endNode.setPositionY(300.0);
        endNode.setLabel("结束");
        endNode.setCreatedBy(userId);
        endNode.setUpdatedBy(userId);
        workflowNodeMapper.insert(endNode);

        // 如果请求中包含自定义节点和边，也一并保存
        if (request.getNodes() != null) {
            for (WorkflowNodeDTO nodeDTO : request.getNodes()) {
                WorkflowNode node = new WorkflowNode();
                node.setWorkflowId(workflow.getId());
                node.setNodeId(nodeDTO.getNodeId() != null ? nodeDTO.getNodeId() : UUID.randomUUID().toString());
                node.setType(nodeDTO.getType());
                node.setPositionX(nodeDTO.getPositionX());
                node.setPositionY(nodeDTO.getPositionY());
                node.setLabel(nodeDTO.getLabel());
                node.setConfig(nodeDTO.getConfig());
                node.setCreatedBy(userId);
                node.setUpdatedBy(userId);
                workflowNodeMapper.insert(node);
            }
        }

        if (request.getEdges() != null) {
            for (WorkflowEdgeDTO edgeDTO : request.getEdges()) {
                WorkflowEdge edge = new WorkflowEdge();
                edge.setWorkflowId(workflow.getId());
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

        log.info("工作流创建成功: id={}, name={}", workflow.getId(), workflow.getName());
        return toWorkflowVO(workflow);
    }

    /**
     * 更新工作流（全量替换nodes和edges）
     */
    @Transactional
    public WorkflowVO updateWorkflow(Long id, WorkflowCreateRequest request, Long userId) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }

        // 更新基本信息
        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setUpdatedBy(userId);
        workflowMapper.updateById(workflow);

        // 删除旧的节点和边
        LambdaQueryWrapper<WorkflowNode> nodeDeleteWrapper = new LambdaQueryWrapper<>();
        nodeDeleteWrapper.eq(WorkflowNode::getWorkflowId, id);
        workflowNodeMapper.delete(nodeDeleteWrapper);

        LambdaQueryWrapper<WorkflowEdge> edgeDeleteWrapper = new LambdaQueryWrapper<>();
        edgeDeleteWrapper.eq(WorkflowEdge::getWorkflowId, id);
        workflowEdgeMapper.delete(edgeDeleteWrapper);

        // 重新插入节点
        if (request.getNodes() != null) {
            for (WorkflowNodeDTO nodeDTO : request.getNodes()) {
                WorkflowNode node = new WorkflowNode();
                node.setWorkflowId(id);
                node.setNodeId(nodeDTO.getNodeId());
                node.setType(nodeDTO.getType());
                node.setPositionX(nodeDTO.getPositionX());
                node.setPositionY(nodeDTO.getPositionY());
                node.setLabel(nodeDTO.getLabel());
                node.setConfig(nodeDTO.getConfig());
                node.setCreatedBy(userId);
                node.setUpdatedBy(userId);
                workflowNodeMapper.insert(node);
            }
        }

        // 重新插入边
        if (request.getEdges() != null) {
            for (WorkflowEdgeDTO edgeDTO : request.getEdges()) {
                WorkflowEdge edge = new WorkflowEdge();
                edge.setWorkflowId(id);
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

        log.info("工作流更新成功: id={}", id);
        return toWorkflowVO(workflow);
    }

    /**
     * 删除工作流（逻辑删除）
     */
    public void deleteWorkflow(Long id, Long userId) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }

        // 检查是否是工作流拥有者
        if (!workflow.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己创建的工作流");
        }

        workflowMapper.deleteById(id);
        log.info("工作流删除成功: id={}", id);
    }

    /**
     * 复制工作流
     */
    @Transactional
    public WorkflowVO duplicateWorkflow(Long id, Long userId) {
        Workflow source = workflowMapper.selectById(id);
        if (source == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "源工作流不存在");
        }

        // 创建新工作流
        Workflow newWorkflow = new Workflow();
        newWorkflow.setName(source.getName() + " (副本)");
        newWorkflow.setDescription(source.getDescription());
        newWorkflow.setStatus("DRAFT");
        newWorkflow.setOwnerId(userId);
        newWorkflow.setCreatedBy(userId);
        newWorkflow.setUpdatedBy(userId);
        workflowMapper.insert(newWorkflow);

        // 复制节点
        List<WorkflowNode> sourceNodes = getWorkflowNodes(id);
        for (WorkflowNode sourceNode : sourceNodes) {
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

        // 复制边
        List<WorkflowEdge> sourceEdges = getWorkflowEdges(id);
        for (WorkflowEdge sourceEdge : sourceEdges) {
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

        log.info("工作流复制成功: sourceId={}, newId={}", id, newWorkflow.getId());
        return toWorkflowVO(newWorkflow);
    }

    // ==================== 私有方法 ====================

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
                .createdAt(workflow.getCreatedAt())
                .updatedAt(workflow.getUpdatedAt())
                .build();
    }

    private WorkflowNodeDTO toNodeDTO(WorkflowNode node) {
        return WorkflowNodeDTO.builder()
                .id(node.getId())
                .nodeId(node.getNodeId())
                .type(node.getType())
                .positionX(node.getPositionX())
                .positionY(node.getPositionY())
                .label(node.getLabel())
                .config(node.getConfig())
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
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=WorkflowServiceTest -q
```

预期输出：`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/workflow/dto/
git add agent-platform/backend/src/main/java/com/superprogrammer/workflow/service/
git add agent-platform/backend/src/test/java/com/superprogrammer/workflow/service/WorkflowServiceTest.java
git commit -m "feat: 实现Workflow模块DTO和Service

- 5个DTO: WorkflowVO/WorkflowDetailVO/WorkflowNodeDTO/WorkflowEdgeDTO/WorkflowCreateRequest
- WorkflowService: listWorkflows/getWorkflowDetail/createWorkflow(自动生成开始结束节点)/
  updateWorkflow(全量替换nodes+edges)/deleteWorkflow(逻辑删除)/duplicateWorkflow(复制)
- 10个单元测试全部通过"
```

---

### Task 7: Workflow Controller
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/workflow/controller/WorkflowController.java`
- Test: `agent-platform/backend/src/test/java/com/superprogrammer/workflow/controller/WorkflowControllerTest.java`

- [ ] **Step 1: 创建 WorkflowController.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/workflow/controller/WorkflowController.java
package com.superprogrammer.workflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.workflow.dto.*;
import com.superprogrammer.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    /**
     * 查询当前用户的工作流列表
     */
    @GetMapping
    @RequirePermission("workflow:read")
    public ResponseEntity<R<List<WorkflowVO>>> listWorkflows() {
        Long userId = getCurrentUserId();
        List<WorkflowVO> workflows = workflowService.listWorkflows(userId);
        return ResponseEntity.ok(R.ok(workflows));
    }

    /**
     * 创建工作流
     */
    @PostMapping
    @RequirePermission("workflow:create")
    public ResponseEntity<R<WorkflowVO>> createWorkflow(
            @Valid @RequestBody WorkflowCreateRequest request) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.createWorkflow(request, userId);
        return ResponseEntity.ok(R.ok("创建成功", workflow));
    }

    /**
     * 获取工作流详情（含nodes和edges）
     */
    @GetMapping("/{id}")
    @RequirePermission("workflow:read")
    public ResponseEntity<R<WorkflowDetailVO>> getWorkflowDetail(@PathVariable Long id) {
        WorkflowDetailVO detail = workflowService.getWorkflowDetail(id);
        return ResponseEntity.ok(R.ok(detail));
    }

    /**
     * 更新工作流（含nodes和edges）
     */
    @PutMapping("/{id}")
    @RequirePermission("workflow:update")
    public ResponseEntity<R<WorkflowVO>> updateWorkflow(
            @PathVariable Long id,
            @Valid @RequestBody WorkflowCreateRequest request) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.updateWorkflow(id, request, userId);
        return ResponseEntity.ok(R.ok("更新成功", workflow));
    }

    /**
     * 删除工作流（逻辑删除）
     */
    @DeleteMapping("/{id}")
    @RequirePermission("workflow:delete")
    public ResponseEntity<R<Void>> deleteWorkflow(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        workflowService.deleteWorkflow(id, userId);
        return ResponseEntity.ok(R.ok("删除成功", null));
    }

    /**
     * 复制工作流
     */
    @PostMapping("/{id}/duplicate")
    @RequirePermission("workflow:create")
    public ResponseEntity<R<WorkflowVO>> duplicateWorkflow(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.duplicateWorkflow(id, userId);
        return ResponseEntity.ok(R.ok("复制成功", workflow));
    }

    /**
     * 导出工作流为JSON
     */
    @GetMapping("/{id}/export")
    @RequirePermission("workflow:read")
    public ResponseEntity<R<WorkflowDetailVO>> exportWorkflow(@PathVariable Long id) {
        WorkflowDetailVO detail = workflowService.getWorkflowDetail(id);
        return ResponseEntity.ok(R.ok(detail));
    }

    /**
     * 导入工作流（从JSON创建）
     */
    @PostMapping("/import")
    @RequirePermission("workflow:create")
    public ResponseEntity<R<WorkflowVO>> importWorkflow(
            @Valid @RequestBody WorkflowCreateRequest request) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.createWorkflow(request, userId);
        return ResponseEntity.ok(R.ok("导入成功", workflow));
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }
}
```

- [ ] **Step 2: 写Controller测试**

```java
// agent-platform/backend/src/test/java/com/superprogrammer/workflow/controller/WorkflowControllerTest.java
package com.superprogrammer.workflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.workflow.dto.*;
import com.superprogrammer.workflow.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowService workflowService;

    @MockBean
    private JwtUtil jwtUtil;

    @Test
    void listWorkflows_returnsWorkflowList() throws Exception {
        WorkflowVO workflowVO = WorkflowVO.builder()
                .id(1L)
                .name("测试工作流")
                .status("DRAFT")
                .ownerId(1L)
                .createdAt(LocalDateTime.now())
                .build();
        when(workflowService.listWorkflows(1L))
                .thenReturn(Arrays.asList(workflowVO));

        mockMvc.perform(get("/api/workflows")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("测试工作流"));
    }

    @Test
    void createWorkflow_success() throws Exception {
        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("新建工作流")
                .description("描述")
                .build();

        WorkflowVO result = WorkflowVO.builder()
                .id(1L)
                .name("新建工作流")
                .status("DRAFT")
                .ownerId(1L)
                .build();

        when(workflowService.createWorkflow(any(WorkflowCreateRequest.class), eq(1L)))
                .thenReturn(result);

        mockMvc.perform(post("/api/workflows")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新建工作流"));
    }

    @Test
    void createWorkflow_emptyName_returns400() throws Exception {
        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("")
                .build();

        mockMvc.perform(post("/api/workflows")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getWorkflowDetail_success() throws Exception {
        WorkflowDetailVO detailVO = WorkflowDetailVO.builder()
                .id(1L)
                .name("测试工作流")
                .status("DRAFT")
                .nodes(Arrays.asList(
                        WorkflowNodeDTO.builder().nodeId("node-1").type("START").label("开始").build()
                ))
                .edges(Arrays.asList())
                .build();

        when(workflowService.getWorkflowDetail(1L)).thenReturn(detailVO);

        mockMvc.perform(get("/api/workflows/1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("测试工作流"))
                .andExpect(jsonPath("$.data.nodes[0].type").value("START"));
    }

    @Test
    void updateWorkflow_success() throws Exception {
        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("更新后")
                .nodes(Arrays.asList())
                .edges(Arrays.asList())
                .build();

        WorkflowVO result = WorkflowVO.builder()
                .id(1L)
                .name("更新后")
                .build();

        when(workflowService.updateWorkflow(eq(1L), any(WorkflowCreateRequest.class), eq(1L)))
                .thenReturn(result);

        mockMvc.perform(put("/api/workflows/1")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("更新后"));
    }

    @Test
    void deleteWorkflow_success() throws Exception {
        mockMvc.perform(delete("/api/workflows/1")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void duplicateWorkflow_success() throws Exception {
        WorkflowVO result = WorkflowVO.builder()
                .id(2L)
                .name("测试工作流 (副本)")
                .build();

        when(workflowService.duplicateWorkflow(1L, 1L)).thenReturn(result);

        mockMvc.perform(post("/api/workflows/1/duplicate")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("测试工作流 (副本)"));
    }

    @Test
    void exportWorkflow_success() throws Exception {
        WorkflowDetailVO detailVO = WorkflowDetailVO.builder()
                .id(1L)
                .name("导出工作流")
                .nodes(Arrays.asList())
                .edges(Arrays.asList())
                .build();

        when(workflowService.getWorkflowDetail(1L)).thenReturn(detailVO);

        mockMvc.perform(get("/api/workflows/1/export")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("导出工作流"));
    }

    @Test
    void importWorkflow_success() throws Exception {
        WorkflowCreateRequest request = WorkflowCreateRequest.builder()
                .name("导入的工作流")
                .nodes(Arrays.asList(
                        WorkflowNodeDTO.builder().nodeId("n1").type("START").build()
                ))
                .edges(Arrays.asList())
                .build();

        WorkflowVO result = WorkflowVO.builder()
                .id(3L)
                .name("导入的工作流")
                .build();

        when(workflowService.createWorkflow(any(WorkflowCreateRequest.class), eq(1L)))
                .thenReturn(result);

        mockMvc.perform(post("/api/workflows/import")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("导入的工作流"));
    }
}
```

- [ ] **Step 3: 运行测试确认通过**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=WorkflowControllerTest -q
```

预期输出：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 4: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/workflow/controller/
git add agent-platform/backend/src/test/java/com/superprogrammer/workflow/controller/WorkflowControllerTest.java
git commit -m "feat: 实现Workflow模块Controller

- WorkflowController: 8个API端点
  GET /api/workflows (列表)
  POST /api/workflows (创建)
  GET /api/workflows/{id} (详情)
  PUT /api/workflows/{id} (更新)
  DELETE /api/workflows/{id} (删除)
  POST /api/workflows/{id}/duplicate (复制)
  GET /api/workflows/{id}/export (导出)
  POST /api/workflows/import (导入)
- 所有接口使用@RequirePermission权限控制
- 9个MockMvc测试全部通过"
```

---

### Task 8: Execution模块（预留）
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/execution/entity/ExecutionLog.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/execution/mapper/ExecutionLogMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/execution/service/ExecutionLogService.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/execution/controller/ExecutionController.java`

- [ ] **Step 1: 创建 ExecutionLog.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/execution/entity/ExecutionLog.java
package com.superprogrammer.execution.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("execution_logs")
public class ExecutionLog extends BaseEntity {

    private Long workflowId;

    private String workflowName;

    private Long triggeredBy;

    private String status;

    private String variables;

    private String nodeLogs;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private Long duration;

    private String errorMessage;
}
```

- [ ] **Step 2: 创建 ExecutionLogMapper.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/execution/mapper/ExecutionLogMapper.java
package com.superprogrammer.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.execution.entity.ExecutionLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExecutionLogMapper extends BaseMapper<ExecutionLog> {
}
```

- [ ] **Step 3: 创建 ExecutionLogService.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/execution/service/ExecutionLogService.java
package com.superprogrammer.execution.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.execution.entity.ExecutionLog;
import com.superprogrammer.execution.mapper.ExecutionLogMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionLogService {

    private final ExecutionLogMapper executionLogMapper;

    /**
     * 开始执行 - 记录日志
     */
    public ExecutionLog startExecution(Long workflowId, String workflowName, Long triggeredBy) {
        ExecutionLog executionLog = new ExecutionLog();
        executionLog.setWorkflowId(workflowId);
        executionLog.setWorkflowName(workflowName);
        executionLog.setTriggeredBy(triggeredBy);
        executionLog.setStatus("RUNNING");
        executionLog.setStartedAt(LocalDateTime.now());
        executionLog.setCreatedBy(triggeredBy);
        executionLog.setUpdatedBy(triggeredBy);
        executionLogMapper.insert(executionLog);

        log.info("执行开始: id={}, workflowId={}", executionLog.getId(), workflowId);
        return executionLog;
    }

    /**
     * 执行完成 - 更新日志
     */
    public void finishExecution(Long executionId, String nodeLogs) {
        ExecutionLog executionLog = executionLogMapper.selectById(executionId);
        if (executionLog == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "执行记录不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        executionLog.setStatus("SUCCESS");
        executionLog.setCompletedAt(now);
        executionLog.setDuration(
                java.time.Duration.between(executionLog.getStartedAt(), now).toMillis());
        executionLog.setNodeLogs(nodeLogs);
        executionLogMapper.updateById(executionLog);

        log.info("执行完成: id={}, duration={}ms", executionId, executionLog.getDuration());
    }

    /**
     * 执行失败 - 记录错误
     */
    public void failExecution(Long executionId, String errorMessage) {
        ExecutionLog executionLog = executionLogMapper.selectById(executionId);
        if (executionLog == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "执行记录不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        executionLog.setStatus("FAILED");
        executionLog.setCompletedAt(now);
        executionLog.setDuration(
                java.time.Duration.between(executionLog.getStartedAt(), now).toMillis());
        executionLog.setErrorMessage(errorMessage);
        executionLogMapper.updateById(executionLog);

        log.error("执行失败: id={}, error={}", executionId, errorMessage);
    }

    /**
     * 查询执行日志
     */
    public ExecutionLog getExecutionLog(Long id) {
        ExecutionLog log = executionLogMapper.selectById(id);
        if (log == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "执行记录不存在");
        }
        return log;
    }

    /**
     * 按工作流ID查询执行日志列表
     */
    public List<ExecutionLog> listByWorkflowId(Long workflowId) {
        LambdaQueryWrapper<ExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExecutionLog::getWorkflowId, workflowId)
                .orderByDesc(ExecutionLog::getStartedAt);
        return executionLogMapper.selectList(wrapper);
    }
}
```

- [ ] **Step 4: 创建 ExecutionController.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/execution/controller/ExecutionController.java
package com.superprogrammer.execution.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.execution.entity.ExecutionLog;
import com.superprogrammer.execution.service.ExecutionLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final ExecutionLogService executionLogService;

    /**
     * 启动执行（仅写日志，实际执行引擎预留）
     */
    @PostMapping
    @RequirePermission("execution:run")
    public ResponseEntity<R<ExecutionLog>> startExecution(
            @RequestParam Long workflowId,
            @RequestParam String workflowName) {
        Long userId = getCurrentUserId();
        ExecutionLog log = executionLogService.startExecution(workflowId, workflowName, userId);
        return ResponseEntity.ok(R.ok("执行已启动", log));
    }

    /**
     * 查询执行详情
     */
    @GetMapping("/{id}")
    @RequirePermission("execution:read")
    public ResponseEntity<R<ExecutionLog>> getExecution(@PathVariable Long id) {
        ExecutionLog log = executionLogService.getExecutionLog(id);
        return ResponseEntity.ok(R.ok(log));
    }

    /**
     * 按工作流ID查询执行列表
     */
    @GetMapping
    @RequirePermission("execution:read")
    public ResponseEntity<R<List<ExecutionLog>>> listByWorkflow(
            @RequestParam Long workflowId) {
        List<ExecutionLog> logs = executionLogService.listByWorkflowId(workflowId);
        return ResponseEntity.ok(R.ok(logs));
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }
}
```

- [ ] **Step 5: 验证编译**

```bash
cd e:\workspace\agent-platform\backend
mvn compile -q
```

预期输出：`BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/execution/
git commit -m "feat: 添加Execution模块（预留）

- ExecutionLog实体 + Mapper: 对应execution_logs表
- ExecutionLogService: start/finish/fail日志记录 + 按工作流查询
- ExecutionController: POST /api/executions, GET /api/executions/{id},
  GET /api/executions?workflowId= (均需权限)
- 实际执行引擎在后续Phase实现"
```

---

### Task 9: 提交最终代码

- [ ] **Step 1: 运行全量编译和测试**

```bash
cd e:\workspace\agent-platform\backend
mvn clean compile -q && echo "=== 编译成功 ===" && mvn test -q && echo "=== 全部测试通过 ==="
```

预期输出：
```
=== 编译成功 ===
=== 全部测试通过 ===
```

- [ ] **Step 2: 确认文件完整性**

```bash
cd e:\workspace\agent-platform\backend
echo "=== Agent模块 ===" && find src/main/java/com/superprogrammer/agent -name "*.java" | sort && echo "=== Workflow模块 ===" && find src/main/java/com/superprogrammer/workflow -name "*.java" | sort && echo "=== Execution模块 ===" && find src/main/java/com/superprogrammer/execution -name "*.java" | sort && echo "=== 测试文件 ===" && find src/test/java/com/superprogrammer -name "*Test.java" | sort
```

预期应包含：
- agent模块：4个entity + 4个mapper + 5个dto + 3个service + 2个controller = 18个文件
- workflow模块：3个entity + 3个mapper + 5个dto + 1个service + 1个controller = 13个文件
- execution模块：1个entity + 1个mapper + 1个service + 1个controller = 4个文件
- 测试：5个Test文件

- [ ] **Step 3: 最终提交**

```bash
git add -A agent-platform/backend/
git commit -m "feat: 完成Agent + Workflow + Execution模块(Plan 2)

Plan 2全部完成，包含：

Agent模块：
- AgentGroup/Agent/Skill/SkillStep 4个实体 + 4个Mapper
- AgentGroupVO/AgentVO/AgentDetailVO/SkillVO/SkillDetailVO 5个DTO
- AgentService: 分组列表/Agent列表(分组+关键词筛选)/详情(含技能)/技能详情(含步骤)
- SkillService: 技能列表/技能详情
- MarkdownSyncService: Markdown解析同步引擎(upsert逻辑)
- AgentGroupController + AgentController: 5个GET + 1个POST(sync) API

Workflow模块：
- Workflow/WorkflowNode/WorkflowEdge 3个实体 + 3个Mapper
- WorkflowVO/WorkflowDetailVO/WorkflowNodeDTO/WorkflowEdgeDTO/WorkflowCreateRequest 5个DTO
- WorkflowService: 列表/详情/创建(自动生成开始结束节点)/更新(全量替换)/删除/复制
- WorkflowController: 8个API端点(CRUD+复制+导出+导入)

Execution模块（预留）：
- ExecutionLog实体 + Mapper + Service(日志记录) + Controller

共35个Java源文件 + 5个测试文件
所有接口使用@RequirePermission权限控制，返回R<T>统一响应"
```

---

## API端点汇总

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | /api/agent-groups | agent:read | 查询Agent分组列表 |
| GET | /api/agents | agent:read | 查询Agent列表(支持分组+关键词筛选) |
| GET | /api/agents/{id} | agent:read | 获取Agent详情(含技能列表) |
| GET | /api/agents/{id}/skills | agent:read | 查询Agent下的技能列表 |
| GET | /api/skills/{id} | agent:read | 获取技能详情(含步骤) |
| POST | /api/agents/sync | agent:create | 触发Markdown同步 |
| GET | /api/workflows | workflow:read | 查询当前用户工作流列表 |
| POST | /api/workflows | workflow:create | 创建工作流(自动生成开始/结束节点) |
| GET | /api/workflows/{id} | workflow:read | 获取工作流详情(含nodes+edges) |
| PUT | /api/workflows/{id} | workflow:update | 更新工作流(全量替换nodes+edges) |
| DELETE | /api/workflows/{id} | workflow:delete | 删除工作流(逻辑删除) |
| POST | /api/workflows/{id}/duplicate | workflow:create | 复制工作流 |
| GET | /api/workflows/{id}/export | workflow:read | 导出工作流为JSON |
| POST | /api/workflows/import | workflow:create | 导入工作流 |
| POST | /api/executions | execution:run | 启动执行(写日志) |
| GET | /api/executions/{id} | execution:read | 查询执行详情 |
| GET | /api/executions?workflowId= | execution:read | 按工作流查询执行列表 |
