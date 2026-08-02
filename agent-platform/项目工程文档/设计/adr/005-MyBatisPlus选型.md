# ADR-005: ORM 选型 — MyBatis-Plus

## 元信息

| 项目 | 内容 |
|------|------|
| 状态 | 已采纳 (Accepted) |
| 日期 | 2026-05-25 |
| 决策者 | 架构组 |
| 上下文 | Java 持久层框架选型 |

---

## 背景

多 Agent 智能体平台的 Spring Boot 后端需要选择 ORM 框架来访问 PostgreSQL 数据库。数据访问场景包括：

### 数据访问特征

| 场景 | 特点 | 示例 |
|------|------|------|
| 单表 CRUD | 高频，结构固定 | 用户、角色、Agent 的增删改查 |
| 多表关联查询 | 中频，关联关系明确 | Agent + Skills + SkillSteps 联查 |
| JSONB 字段读写 | 中频，结构灵活 | 工作流节点配置、执行日志输入输出 |
| 批量操作 | 低频，性能敏感 | 工作流节点批量保存、执行日志批量插入 |
| 动态条件查询 | 高频，条件组合灵活 | Agent 列表筛选（按分组、状态、关键词） |
| 分页查询 | 高频 | 所有列表页面 |

### 数据库规模估算

| 表 | 预估行数 | 增长速度 |
|----|---------|---------|
| users | < 1,000 | 慢 |
| roles | < 10 | 极慢 |
| permissions | < 50 | 极慢 |
| agent_groups | < 20 | 极慢 |
| agents | < 100 | 慢 |
| skills | < 500 | 慢 |
| skill_steps | < 2,000 | 慢 |
| workflows | < 200 | 中 |
| workflow_nodes | < 5,000 | 中 |
| workflow_edges | < 8,000 | 中 |
| execution_logs | 10,000+ | 快 |

---

## 候选方案

### 方案A：MyBatis-Plus — 已采纳

MyBatis-Plus 是 MyBatis 的增强工具，在 MyBatis 基础上只做增强不做改变。

#### 核心特性

| 特性 | 说明 |
|------|------|
| 单表 CRUD | BaseMapper 内置通用 CRUD，无需编写 XML |
| 条件构造器 | LambdaQueryWrapper，类型安全的条件构建 |
| 分页插件 | 内置分页拦截器，支持多种数据库方言 |
| 代码生成器 | 根据数据库表自动生成 Entity、Mapper、Service |
| 逻辑删除 | @TableLogic 注解，删除操作自动变为更新 |
| 自动填充 | @TableField(fill = FieldFill.INSERT) 自动填充创建时间 |
| JSON 类型处理 | TypeHandler 支持 JSONB 字段映射 |
| 多租户 | 内置多租户插件（预留） |

#### 代码示例

```java
// Entity 定义
@Data
@TableName("agents")
public class Agent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;
    private String avatar;
    private Long groupId;
    private String status;  // DRAFT/PUBLISHED/OFFLINE

    @TableField(typeHandler = JacksonTypeHandler.class)
    private AgentConfig config;  // JSONB → Java对象

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

// Mapper 接口 — 继承 BaseMapper 即获得通用 CRUD
@Mapper
public interface AgentMapper extends BaseMapper<Agent> {
    // 自定义复杂查询使用 XML
    List<AgentDetailVO> selectAgentDetailList(@Param("query") AgentQueryDTO query);
}

// Service 层 — 使用 LambdaQueryWrapper 构建类型安全查询
@Service
public class AgentServiceImpl extends ServiceImpl<AgentMapper, Agent>
    implements AgentService {

    public IPage<Agent> listAgents(AgentQueryDTO query, int pageNum, int pageSize) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.isNotBlank(query.getName()), Agent::getName, query.getName())
               .eq(query.getGroupId() != null, Agent::getGroupId, query.getGroupId())
               .eq(StringUtils.isNotBlank(query.getStatus()), Agent::getStatus, query.getStatus())
               .orderByDesc(Agent::getCreatedAt);

        return page(new Page<>(pageNum, pageSize), wrapper);
    }
}
```

#### XML 复杂查询

```xml
<!-- AgentMapper.xml — 复杂关联查询使用 XML -->
<select id="selectAgentDetailList" resultType="com.platform.agent.dto.AgentDetailVO">
    SELECT
        a.id, a.name, a.description, a.avatar, a.status,
        ag.name AS groupName,
        COUNT(DISTINCT s.id) AS skillCount,
        COALESCE(
            json_agg(
                json_build_object('id', s.id, 'name', s.name, 'type', s.type)
            ) FILTER (WHERE s.id IS NOT NULL),
            '[]'::json
        ) AS skills
    FROM agents a
    LEFT JOIN agent_groups ag ON a.group_id = ag.id
    LEFT JOIN skills s ON s.agent_id = a.id AND s.deleted = 0
    WHERE a.deleted = 0
    <if test="query.name != null and query.name != ''">
        AND a.name LIKE CONCAT('%', #{query.name}, '%')
    </if>
    <if test="query.groupId != null">
        AND a.group_id = #{query.groupId}
    </if>
    <if test="query.status != null and query.status != ''">
        AND a.status = #{query.status}
    </if>
    GROUP BY a.id, ag.name
    ORDER BY a.created_at DESC
</select>
```

### 方案B：Spring Data JPA (Hibernate)

JPA 是 Java 持久化标准，Hibernate 是最流行的 JPA 实现。

#### 核心特性

| 特性 | 说明 |
|------|------|
| 单表 CRUD | JpaRepository 内置通用 CRUD |
| 方法名查询 | findByStatusAndGroupName，自动生成 SQL |
| JPQL/HQL | 面向对象的查询语言 |
| Criteria API | 类型安全的动态查询构建 |
| 关联映射 | @OneToMany/@ManyToOne 等注解自动管理关联 |
| 延迟加载 | 代理模式按需加载关联数据 |
| 缓存 | 一级缓存（Session）+ 二级缓存（可选） |

#### 代码示例

```java
// Entity 定义
@Entity
@Table(name = "agents")
public class Agent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private AgentGroup group;

    @OneToMany(mappedBy = "agent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Skill> skills = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private AgentStatus status;

    @Column(columnDefinition = "jsonb")
    @Convert(converter = AgentConfigConverter.class)
    private AgentConfig config;
}

// Repository 接口
public interface AgentRepository extends JpaRepository<Agent, Long> {
    List<Agent> findByStatusAndGroup_Id(AgentStatus status, Long groupId);

    @Query("SELECT a FROM Agent a LEFT JOIN FETCH a.skills WHERE a.id = :id")
    Optional<Agent> findByIdWithSkills(@Param("id") Long id);
}
```

#### JPA 的常见问题

```java
// 1. N+1 查询问题
List<Agent> agents = agentRepository.findAll();
// 如果 Agent 有 @OneToMany skills，每次访问 agent.getSkills() 都会触发一次查询
// 解决：需要手动添加 @EntityGraph 或 JOIN FETCH

// 2. 延迟加载异常
@Transactional
public AgentDTO getAgent(Long id) {
    Agent agent = agentRepository.findById(id).get();
    return new AgentDTO(agent, agent.getSkills()); // 可能触发 LazyInitializationException
}

// 3. JSONB 映射复杂
// 需要自定义 AttributeConverter，不如 MyBatis-Plus 的 TypeHandler 直观
@Converter(autoApply = true)
public class AgentConfigConverter implements AttributeConverter<AgentConfig, String> {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(AgentConfig config) {
        try { return mapper.writeValueAsString(config); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }

    @Override
    public AgentConfig convertToEntityAttribute(String json) {
        try { return mapper.readValue(json, AgentConfig.class); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }
}

// 4. 批量操作性能差
// Hibernate 的一级缓存会导致批量操作时内存膨胀
// 需要手动 flush + clear 或配置 batch_size
```

### 方案C：原生 MyBatis

MyBatis 是半自动化的 ORM 框架，所有 SQL 都需要手动编写。

#### 核心特性

| 特性 | 说明 |
|------|------|
| SQL 完全控制 | 所有 SQL 都在 XML 或注解中明确定义 |
| 灵活性最高 | 可以精确控制每一条 SQL |
| 动态 SQL | if/choose/foreach 等 XML 标签构建动态 SQL |
| 结果映射 | ResultMap 灵活映射复杂结果 |

#### 代码示例

```java
// Entity — 纯 POJO，无注解
public class Agent {
    private Long id;
    private String name;
    private String status;
    // getter/setter...
}

// Mapper — 所有方法都需要 XML 实现
@Mapper
public interface AgentMapper {
    Agent selectById(Long id);
    List<Agent> selectList(AgentQuery query);
    int insert(Agent agent);
    int updateById(Agent agent);
    int deleteById(Long id);
    // 即使是最简单的 CRUD 也需要写 XML
}
```

```xml
<!-- AgentMapper.xml — 每个操作都需要手写 SQL -->
<insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO agents (name, description, avatar, group_id, status, config, created_at, updated_at)
    VALUES (#{name}, #{description}, #{avatar}, #{groupId}, #{status},
            #{config, typeHandler=JacksonTypeHandler}, NOW(), NOW())
</insert>

<select id="selectById" resultType="com.platform.agent.entity.Agent">
    SELECT * FROM agents WHERE id = #{id}
</select>

<select id="selectList" resultType="com.platform.agent.entity.Agent">
    SELECT * FROM agents
    <where>
        <if test="name != null">AND name LIKE CONCAT('%', #{name}, '%')</if>
        <if test="groupId != null">AND group_id = #{groupId}</if>
        <if test="status != null">AND status = #{status}</if>
    </where>
    ORDER BY created_at DESC
</select>
```

---

## 对比决策矩阵

| 评估维度 | 权重 | MyBatis-Plus | JPA | 原生MyBatis |
|---------|------|-------------|-----|-----------|
| 单表 CRUD 效率 | 20% | 10 | 9 | 3 |
| 复杂查询灵活性 | 20% | 9 | 6 | 10 |
| JSONB 支持 | 15% | 9 | 5 | 8 |
| 学习曲线 | 10% | 8 | 5 | 7 |
| 动态条件查询 | 10% | 9 | 5 | 8 |
| 性能可控性 | 10% | 9 | 5 | 10 |
| 代码量 | 10% | 9 | 8 | 3 |
| 社区活跃度(国内) | 5% | 10 | 6 | 8 |
| **加权总分** | 100% | **9.15** | **6.15** | **6.65** |

---

## 决策理由

选择 MyBatis-Plus 的核心理由：

### 1. 单表 CRUD 与复杂查询的完美平衡

MyBatis-Plus 提供了 BaseMapper，单表 CRUD 不需要写一行 SQL：

```java
// 这就是全部代码 — 不需要 XML
agentMapper.selectById(1L);
agentMapper.selectList(wrapper);
agentMapper.insert(agent);
agentMapper.updateById(agent);
agentMapper.deleteById(1L);
```

而复杂查询（如 Agent 关联 Skills 联查）使用 XML，SQL 完全可控。

JPA 的问题在于：简单查询很方便，但遇到复杂查询（多表关联 + 聚合 + JSONB 操作）时，JPQL 和 Criteria API 的表达力不如原生 SQL。

### 2. JSONB 支持优秀

PostgreSQL 的 JSONB 是本项目的核心数据类型（工作流节点配置、执行日志输入输出）。MyBatis-Plus 的 TypeHandler 机制可以简洁地处理 JSONB 映射：

```java
// Entity 字段声明
@TableField(typeHandler = JacksonTypeHandler.class)
private JsonNode config;

// 自动处理 PostgreSQL JSONB ↔ Jackson JsonNode 的转换
// 插入时：JsonNode → JSON字符串 → PostgreSQL JSONB
// 查询时：PostgreSQL JSONB → JSON字符串 → JsonNode
```

### 3. LambdaQueryWrapper 类型安全

```java
// 类型安全的条件构建 — 重构时字段名变更会编译报错
LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
wrapper.eq(Agent::getStatus, "PUBLISHED")
       .like(Agent::getName, "助手")
       .orderByDesc(Agent::getCreatedAt);

// 对比 JPA Criteria API — 更冗长
CriteriaBuilder cb = entityManager.getCriteriaBuilder();
CriteriaQuery<Agent> cq = cb.createQuery(Agent.class);
Root<Agent> root = cq.from(Agent.class);
cq.where(
    cb.equal(root.get("status"), "PUBLISHED"),
    cb.like(root.get("name"), "%助手%")
);
```

### 4. 国内生态优势

MyBatis-Plus 在国内 Java 社区使用率极高：
- 中文文档完善
- 大量中文教程和问题解答
- 与国内常用的数据库设计模式匹配（如逻辑删除、自动填充）
- 团队成员使用经验丰富

---

## MyBatis-Plus 配置要点

### 分页插件配置

```java
@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // PostgreSQL 分页
        interceptor.addInnerInterceptor(
            new PaginationInnerInterceptor(DbType.POSTGRE_SQL)
        );
        return interceptor;
    }
}
```

### 自动填充配置

```java
@Component
public class AutoFillHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
```

### JSON TypeHandler

```java
@MappedTypes(JsonNode.class)
public class JsonNodeTypeHandler extends BaseTypeHandler<JsonNode> {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, JsonNode param, JdbcType type)
            throws SQLException {
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue(param.toString());
        ps.setObject(i, pgObject);
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        return parseJson(json);
    }

    // ... 其他方法
}
```

---

## 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| XML 文件管理混乱 | 中 | 中 | 按模块分包，每个 Mapper 对应一个 XML |
| N+1 查询 | 中 | 中 | 复杂查询使用 XML 手动 JOIN，Review 时检查 |
| Lambda 表达式序列化异常 | 低 | 低 | 使用 LambdaQueryWrapper 而非 QueryWrapper |
| TypeHandler 遗漏 | 低 | 低 | 代码生成器自动配置 JSON 字段的 TypeHandler |
