# Phase 3：AI 报告增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让报告模板支持固定工作完成率、逾期日志、IM 录入工作记录、灵感摘要等上下文变量，AI 总结生成更立体的日报/周报，并在前端展示完成率元数据。

**Architecture:** 新增 `FixedWorkCompletionService` 负责按周期统计完成率、逾期与连续未完成天数；扩展 `ReportTemplateEngine` 与 `AiSummaryService` 的上下文；`WorkReportService` 在生成报告时聚合固定工作统计、IM 工作记录与灵感摘要；前端 `ReportViewer` 展示完成率卡片，`ReportConfigForm` 增加“包含灵感摘要”开关。

**Tech Stack:** Spring Boot 3.2.5 / Java 17 / PostgreSQL / Flyway / JdbcTemplate / Vue 3 / TypeScript / Vite / Vitest

---

## File Structure

| 文件 | 操作 | 说明 |
|------|------|------|
| `server/src/main/java/.../dto/FixedWorkCompletionStats.java` | 创建 | 完成率、逾期、连续未完成天数 DTO |
| `server/src/main/java/.../service/FixedWorkCompletionService.java` | 创建 | 固定工作统计服务 |
| `server/src/main/java/.../repository/InboundMessageRepository.java` | 修改 | 查询周期内 CONFIRMED 的 IM 工作记录 |
| `server/src/main/java/.../entity/ReportConfig.java` | 修改 | 增加 `includeInspirationDigest` |
| `server/src/main/java/.../dto/ReportConfigDto.java` | 修改 | 增加 `includeInspirationDigest` |
| `server/src/main/java/.../dto/SaveReportConfigRequest.java` | 修改 | 增加 `includeInspirationDigest` |
| `server/src/main/java/.../repository/ReportConfigRepository.java` | 修改 | 读写 `include_inspiration_digest` |
| `server/src/main/java/.../service/ReportConfigService.java` | 修改 | 处理新字段默认值 |
| `server/src/main/java/.../entity/WorkReport.java` | 修改 | 增加 `completionRate`、`consecutiveMissDays` |
| `server/src/main/java/.../dto/WorkReportDto.java` | 修改 | 增加 `completionRate`、`consecutiveMissDays` |
| `server/src/main/java/.../repository/WorkReportRepository.java` | 修改 | 读写新字段 |
| `server/src/main/java/.../service/ReportTemplateEngine.java` | 修改 | 增加新模板变量 |
| `server/src/main/java/.../service/AiSummaryService.java` | 修改 | 使用增强提示词 |
| `server/src/main/java/.../service/WorkReportService.java` | 修改 | 聚合上下文并生成报告 |
| `server/src/main/resources/db/migration/V14__update_default_templates.sql` | 创建 | 数据库迁移与默认模板升级 |
| `server/src/test/java/.../FixedWorkCompletionServiceTest.java` | 创建 | 固定工作统计单元测试 |
| `server/src/test/java/.../ReportTemplateEngineTest.java` | 修改 | 新变量测试 |
| `server/src/test/java/.../AiSummaryServiceTest.java` | 修改 | 新提示词参数测试 |
| `server/src/test/java/.../WorkReportServiceTest.java` | 修改 | 生成报告含统计元数据测试 |
| `src/types/workReport.ts` | 修改 | 前端类型扩展 |
| `src/api/workReport.ts` | 修改 | 保存配置透传新字段 |
| `src/api/__tests__/workReport.test.ts` | 修改 | API 测试 |
| `src/components/work-report/ReportConfigForm.vue` | 修改 | 新增“包含灵感摘要”开关 |
| `src/components/work-report/ReportViewer.vue` | 修改 | 展示完成率卡片 |
| `src/locales/zh-CN.ts` | 修改 | 中文文案 |
| `src/locales/en.ts` | 修改 | 英文文案 |
| `src/stores/__tests__/workReportStore.test.ts` | 修改 | Store 测试 |
| `docs/superpowers/plans/interactive-ai-work-assistant/phase3/progress.md` | 修改 | 更新阶段进度 |
| `docs/superpowers/plans/interactive-ai-work-assistant/progress.md` | 修改 | 更新总进度 |

---

## Task 1：Add `includeInspirationDigest` to ReportConfig

**Files:**
- Modify: `server/src/main/java/com/superprogrammer/workreport/entity/ReportConfig.java`
- Modify: `server/src/main/java/com/superprogrammer/workreport/dto/ReportConfigDto.java`
- Modify: `server/src/main/java/com/superprogrammer/workreport/dto/SaveReportConfigRequest.java`
- Modify: `server/src/main/java/com/superprogrammer/workreport/repository/ReportConfigRepository.java`
- Modify: `server/src/main/java/com/superprogrammer/workreport/service/ReportConfigService.java`
- Test: `server/src/test/java/com/superprogrammer/workreport/ReportConfigServiceTest.java`

- [ ] **Step 1：在 `ReportConfig` 实体增加字段**

在 `private Long aiConfigId;` 下方添加：

```java
private Boolean includeInspirationDigest;
```

- [ ] **Step 2：在 `ReportConfigDto` 增加字段**

在 `Long aiConfigId` 后面添加：

```java
Boolean includeInspirationDigest,
```

- [ ] **Step 3：在 `SaveReportConfigRequest` 增加字段**

在 `Long aiConfigId` 后面添加：

```java
Boolean includeInspirationDigest,
```

- [ ] **Step 4：修改 `ReportConfigRepository` 的 insert / update / query / mapper**

替换整个文件为：

```java
package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.ReportConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReportConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReportConfig insert(ReportConfig config) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into report_configs (user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setLong(1, config.getUserId());
            ps.setString(2, config.getName());
            ps.setString(3, config.getReportType());
            ps.setLong(4, config.getTemplateId());
            ps.setString(5, config.getCronExpression());
            ps.setString(6, config.getTimezone());
            ps.setBoolean(7, config.getEnabled() != null && config.getEnabled());
            ps.setBoolean(8, config.getAiEnabled() != null && config.getAiEnabled());
            ps.setObject(9, config.getAiConfigId());
            ps.setBoolean(10, config.getIncludeInspirationDigest() != null && config.getIncludeInspirationDigest());
            ps.setObject(11, config.getCreatedBy());
            ps.setObject(12, config.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报告配置保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "报告配置保存后无法查询"));
    }

    public ReportConfig update(ReportConfig config) {
        int rows = jdbcTemplate.update(
                "update report_configs set name = ?, report_type = ?, template_id = ?, cron_expression = ?, timezone = ?, enabled = ?, ai_enabled = ?, ai_config_id = ?, include_inspiration_digest = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                config.getName(), config.getReportType(), config.getTemplateId(), config.getCronExpression(),
                config.getTimezone(), config.getEnabled(), config.getAiEnabled(), config.getAiConfigId(),
                config.getIncludeInspirationDigest(),
                config.getUpdatedBy(), config.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "报告配置不存在");
        }
        return findById(config.getId()).orElseThrow();
    }

    public Optional<ReportConfig> findById(Long id) {
        List<ReportConfig> results = jdbcTemplate.query(
                "select id, user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_configs where id = ? and deleted = 0",
                configMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<ReportConfig> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_configs where user_id = ? and deleted = 0 order by id desc",
                configMapper(), userId
        );
    }

    public List<ReportConfig> findEnabledByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_configs where user_id = ? and enabled = true and deleted = 0 order by id desc",
                configMapper(), userId
        );
    }

    public List<ReportConfig> findEnabled() {
        return jdbcTemplate.query(
                "select id, user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_configs where enabled = true and deleted = 0 order by id desc",
                configMapper()
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update report_configs set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private RowMapper<ReportConfig> configMapper() {
        return (rs, rowNum) -> mapConfig(rs);
    }

    private ReportConfig mapConfig(ResultSet rs) throws SQLException {
        ReportConfig config = new ReportConfig();
        config.setId(rs.getLong("id"));
        config.setUserId(rs.getLong("user_id"));
        config.setName(rs.getString("name"));
        config.setReportType(rs.getString("report_type"));
        config.setTemplateId(rs.getLong("template_id"));
        config.setCronExpression(rs.getString("cron_expression"));
        config.setTimezone(rs.getString("timezone"));
        config.setEnabled(rs.getBoolean("enabled"));
        config.setAiEnabled(rs.getBoolean("ai_enabled"));
        config.setAiConfigId(rs.getObject("ai_config_id", Long.class));
        config.setIncludeInspirationDigest(rs.getBoolean("include_inspiration_digest"));
        config.setCreatedBy(rs.getObject("created_by", Long.class));
        config.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        config.setUpdatedBy(rs.getObject("updated_by", Long.class));
        config.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        config.setDeleted(rs.getInt("deleted"));
        return config;
    }
}
```

- [ ] **Step 5：修改 `ReportConfigService` 默认值与 DTO 转换**

在 `create` 方法中 `config.setAiConfigId(request.aiConfigId());` 后添加：

```java
config.setIncludeInspirationDigest(request.includeInspirationDigest() == null ? true : request.includeInspirationDigest());
```

在 `update` 方法中 `config.setAiConfigId(...)` 后添加：

```java
config.setIncludeInspirationDigest(request.includeInspirationDigest() == null ? config.getIncludeInspirationDigest() : request.includeInspirationDigest());
```

在 `toDto` 返回的 `ReportConfigDto` 构造参数中 `config.getAiConfigId()` 后添加 `config.getIncludeInspirationDigest()`。

- [ ] **Step 6：更新 `ReportConfigServiceTest` 与 `ReportPushServiceImplTest` 中的 `SaveReportConfigRequest` 构造调用**

所有 `new SaveReportConfigRequest(...)` 调用需要在 `aiConfigId` 参数后增加 `includeInspirationDigest` 参数。例如：

```java
new SaveReportConfigRequest(
        null,
        "日报",
        "DAILY",
        templateId,
        "0 9 * * *",
        "Asia/Shanghai",
        true,
        true,
        null,
        null,   // includeInspirationDigest
        List.of(targetId)
)
```

- [ ] **Step 7：运行后端编译与相关测试**

Run: `cd file-keeper/server && mvn test -Dtest=ReportConfigServiceTest,ReportPushServiceImplTest`
Expected: 编译通过，测试 PASS

- [ ] **Step 8：Commit**

```bash
git add server/src/main/java/com/superprogrammer/workreport/entity/ReportConfig.java \
  server/src/main/java/com/superprogrammer/workreport/dto/ReportConfigDto.java \
  server/src/main/java/com/superprogrammer/workreport/dto/SaveReportConfigRequest.java \
  server/src/main/java/com/superprogrammer/workreport/repository/ReportConfigRepository.java \
  server/src/main/java/com/superprogrammer/workreport/service/ReportConfigService.java
git commit -m "feat: 报告配置增加包含灵感摘要开关"
```

---

## Task 2：Add completion metadata to WorkReport

**Files:**
- Modify: `server/src/main/java/com/superprogrammer/workreport/entity/WorkReport.java`
- Modify: `server/src/main/java/com/superprogrammer/workreport/dto/WorkReportDto.java`
- Modify: `server/src/main/java/com/superprogrammer/workreport/repository/WorkReportRepository.java`
- Modify: `server/src/main/java/com/superprogrammer/workreport/service/WorkReportService.java`

- [ ] **Step 1：在 `WorkReport` 实体增加字段**

在 `private String status;` 下方添加：

```java
private Double completionRate;

private Integer consecutiveMissDays;
```

- [ ] **Step 2：在 `WorkReportDto` 增加字段**

在 `String status` 后面添加：

```java
Double completionRate,
Integer consecutiveMissDays
```

- [ ] **Step 3：修改 `WorkReportRepository`**

替换整个文件为：

```java
package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.WorkReport;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WorkReportRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkReport insert(WorkReport report) {
        Timestamp generatedAt = report.getGeneratedAt() != null ? Timestamp.from(report.getGeneratedAt().toInstant()) : null;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into work_reports (user_id, config_id, report_type, title, content, generated_at, status, completion_rate, consecutive_miss_days, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setLong(1, report.getUserId());
            ps.setLong(2, report.getConfigId());
            ps.setString(3, report.getReportType());
            ps.setString(4, report.getTitle());
            ps.setString(5, report.getContent());
            ps.setTimestamp(6, generatedAt);
            ps.setString(7, report.getStatus());
            ps.setObject(8, report.getCompletionRate());
            ps.setObject(9, report.getConsecutiveMissDays());
            ps.setObject(10, report.getCreatedBy());
            ps.setObject(11, report.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报告保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "报告保存后无法查询"));
    }

    public WorkReport update(WorkReport report) {
        int rows = jdbcTemplate.update(
                "update work_reports set title = ?, content = ?, status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                report.getTitle(), report.getContent(), report.getStatus(),
                report.getUpdatedBy(), report.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "报告不存在");
        }
        return findById(report.getId()).orElseThrow();
    }

    public Optional<WorkReport> findById(Long id) {
        List<WorkReport> results = jdbcTemplate.query(
                "select id, user_id, config_id, report_type, title, content, generated_at, status, completion_rate, consecutive_miss_days, created_by, created_at, updated_by, updated_at, deleted " +
                        "from work_reports where id = ? and deleted = 0",
                reportMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<WorkReport> findByUserId(Long userId, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (safePage - 1) * safeSize;
        return jdbcTemplate.query(
                "select id, user_id, config_id, report_type, title, content, generated_at, status, completion_rate, consecutive_miss_days, created_by, created_at, updated_by, updated_at, deleted " +
                        "from work_reports where user_id = ? and deleted = 0 order by generated_at desc limit ? offset ?",
                reportMapper(), userId, safeSize, offset
        );
    }

    public Long countByUserId(Long userId) {
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from work_reports where user_id = ? and deleted = 0",
                Long.class, userId
        );
        return total == null ? 0 : total;
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update work_reports set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private RowMapper<WorkReport> reportMapper() {
        return (rs, rowNum) -> mapReport(rs);
    }

    private WorkReport mapReport(ResultSet rs) throws SQLException {
        WorkReport report = new WorkReport();
        report.setId(rs.getLong("id"));
        report.setUserId(rs.getLong("user_id"));
        report.setConfigId(rs.getLong("config_id"));
        report.setReportType(rs.getString("report_type"));
        report.setTitle(rs.getString("title"));
        report.setContent(rs.getString("content"));
        Timestamp generatedAt = rs.getTimestamp("generated_at");
        if (generatedAt != null) {
            report.setGeneratedAt(generatedAt.toInstant().atOffset(java.time.ZoneOffset.UTC));
        }
        report.setStatus(rs.getString("status"));
        report.setCompletionRate(rs.getObject("completion_rate", Double.class));
        report.setConsecutiveMissDays(rs.getObject("consecutive_miss_days", Integer.class));
        report.setCreatedBy(rs.getObject("created_by", Long.class));
        report.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        report.setUpdatedBy(rs.getObject("updated_by", Long.class));
        report.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        report.setDeleted(rs.getInt("deleted"));
        return report;
    }
}
```

- [ ] **Step 4：修改 `WorkReportService.toDto`**

在 `report.getStatus()` 后添加：

```java
report.getCompletionRate(),
report.getConsecutiveMissDays()
```

- [ ] **Step 5：编译检查**

Run: `cd file-keeper/server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 6：Commit**

```bash
git add server/src/main/java/com/superprogrammer/workreport/entity/WorkReport.java \
  server/src/main/java/com/superprogrammer/workreport/dto/WorkReportDto.java \
  server/src/main/java/com/superprogrammer/workreport/repository/WorkReportRepository.java \
  server/src/main/java/com/superprogrammer/workreport/service/WorkReportService.java
git commit -m "feat: 报告实体增加完成率与连续未完成天数字段"
```

---

## Task 3：Create FixedWorkCompletion DTOs and Service

**Files:**
- Create: `server/src/main/java/com/superprogrammer/workreport/dto/FixedWorkCompletionStats.java`
- Create: `server/src/main/java/com/superprogrammer/workreport/service/FixedWorkCompletionService.java`
- Test: `server/src/test/java/com/superprogrammer/workreport/FixedWorkCompletionServiceTest.java`

- [ ] **Step 1：创建 `FixedWorkCompletionStats.java`**

```java
package com.superprogrammer.workreport.dto;

import java.time.LocalDate;
import java.util.List;

public record FixedWorkCompletionStats(
        double overallCompletionRate,
        List<ItemCompletionRate> itemRates,
        List<MissLogEntry> missLog,
        int maxConsecutiveMissDays
) {

    public record ItemCompletionRate(
            Long itemId,
            String content,
            double rate,
            int expectedCount,
            int completedCount
    ) {
    }

    public record MissLogEntry(
            LocalDate date,
            String itemContent
    ) {
    }
}
```

- [ ] **Step 2：创建 `FixedWorkCompletionService.java`**

```java
package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.dto.FixedWorkCompletionStats.ItemCompletionRate;
import com.superprogrammer.workreport.dto.FixedWorkCompletionStats.MissLogEntry;
import com.superprogrammer.workreport.entity.FixedWorkCompletion;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.repository.FixedWorkCompletionRepository;
import com.superprogrammer.workreport.repository.FixedWorkItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FixedWorkCompletionService {

    private final FixedWorkItemRepository itemRepository;
    private final FixedWorkCompletionRepository completionRepository;

    public FixedWorkCompletionStats calculateStats(Long userId, LocalDate startDate, LocalDate endDate) {
        List<FixedWorkItem> items = itemRepository.findByUserId(userId);
        List<LocalDate> expectedDates = expandDateRange(startDate, endDate);
        Set<String> completedKeys = completionRepository.findByUserIdAndDateRange(userId, startDate, endDate).stream()
                .filter(c -> Boolean.TRUE.equals(c.getCompleted()))
                .map(c -> c.getItemId() + ":" + c.getCompletionDate())
                .collect(Collectors.toSet());

        List<ItemCompletionRate> itemRates = new ArrayList<>();
        List<MissLogEntry> missLog = new ArrayList<>();
        int totalExpected = 0;
        int totalCompleted = 0;

        for (FixedWorkItem item : items) {
            List<LocalDate> itemExpectedDates = filterExpectedDates(item, expectedDates);
            int expected = itemExpectedDates.size();
            int completed = 0;
            for (LocalDate date : itemExpectedDates) {
                if (completedKeys.contains(item.getId() + ":" + date)) {
                    completed++;
                } else {
                    missLog.add(new MissLogEntry(date, item.getContent()));
                }
            }
            totalExpected += expected;
            totalCompleted += completed;
            if (expected > 0) {
                double rate = (double) completed / expected;
                itemRates.add(new ItemCompletionRate(item.getId(), item.getContent(), rate, expected, completed));
            }
        }

        double overallRate = totalExpected == 0 ? 0.0 : (double) totalCompleted / totalExpected;
        int maxConsecutiveMissDays = calculateMaxConsecutiveMissDays(items, userId, endDate, completedKeys);
        return new FixedWorkCompletionStats(overallRate, itemRates, missLog, maxConsecutiveMissDays);
    }

    public List<MissLogEntry> findMissLog(Long userId, LocalDate startDate, LocalDate endDate) {
        return calculateStats(userId, startDate, endDate).missLog();
    }

    public int findConsecutiveMissDays(Long userId, Long itemId, LocalDate endDate) {
        FixedWorkItem item = itemRepository.findById(itemId).orElse(null);
        if (item == null || !item.getUserId().equals(userId)) {
            return 0;
        }
        LocalDate startDate = endDate.minusDays(31);
        List<LocalDate> expectedDates = expandDateRange(startDate, endDate);
        Set<String> completedKeys = completionRepository.findByUserIdAndDateRange(userId, startDate, endDate).stream()
                .filter(c -> Boolean.TRUE.equals(c.getCompleted()))
                .map(c -> c.getItemId() + ":" + c.getCompletionDate())
                .collect(Collectors.toSet());
        return countConsecutiveMissDays(item, expectedDates, endDate, completedKeys);
    }

    private int calculateMaxConsecutiveMissDays(List<FixedWorkItem> items, Long userId, LocalDate endDate, Set<String> completedKeys) {
        return items.stream()
                .mapToInt(item -> {
                    LocalDate startDate = endDate.minusDays(31);
                    List<LocalDate> expectedDates = expandDateRange(startDate, endDate);
                    return countConsecutiveMissDays(item, expectedDates, endDate, completedKeys);
                })
                .max()
                .orElse(0);
    }

    private int countConsecutiveMissDays(FixedWorkItem item, List<LocalDate> expectedDates, LocalDate endDate, Set<String> completedKeys) {
        List<LocalDate> itemExpectedDates = filterExpectedDates(item, expectedDates).stream()
                .filter(d -> !d.isAfter(endDate))
                .sorted((a, b) -> -a.compareTo(b))
                .toList();
        int count = 0;
        for (LocalDate date : itemExpectedDates) {
            if (completedKeys.contains(item.getId() + ":" + date)) {
                break;
            }
            count++;
        }
        return count;
    }

    private List<LocalDate> expandDateRange(LocalDate startDate, LocalDate endDate) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            dates.add(current);
            current = current.plusDays(1);
        }
        return dates;
    }

    private List<LocalDate> filterExpectedDates(FixedWorkItem item, List<LocalDate> dates) {
        String recurrenceType = item.getRecurrenceType();
        if ("WEEKLY".equals(recurrenceType)) {
            Set<Integer> days = parseReminderDays(item.getReminderDays());
            return dates.stream()
                    .filter(d -> days.contains(d.getDayOfWeek().getValue()))
                    .toList();
        }
        if ("MONTHLY".equals(recurrenceType)) {
            Set<Integer> days = parseReminderDays(item.getReminderDays());
            return dates.stream()
                    .filter(d -> days.contains(d.getDayOfMonth()))
                    .toList();
        }
        return dates;
    }

    private Set<Integer> parseReminderDays(String reminderDays) {
        if (reminderDays == null || reminderDays.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(reminderDays.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parseIntOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Integer parseIntOrNull(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 3：创建单元测试 `FixedWorkCompletionServiceTest.java`**

```java
package com.superprogrammer.workreport;

import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.entity.FixedWorkCompletion;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.repository.FixedWorkCompletionRepository;
import com.superprogrammer.workreport.repository.FixedWorkItemRepository;
import com.superprogrammer.workreport.service.FixedWorkCompletionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixedWorkCompletionServiceTest {

    private final FixedWorkItemRepository itemRepository = mock(FixedWorkItemRepository.class);
    private final FixedWorkCompletionRepository completionRepository = mock(FixedWorkCompletionRepository.class);
    private final FixedWorkCompletionService service = new FixedWorkCompletionService(itemRepository, completionRepository);

    @Test
    void dailyItemCompletionRateIsFiftyPercent() {
        FixedWorkItem item = dailyItem(1L, "晨会");
        when(itemRepository.findByUserId(1L)).thenReturn(List.of(item));
        when(completionRepository.findByUserIdAndDateRange(1L, LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 23)))
                .thenReturn(List.of(completion(1L, LocalDate.of(2026, 6, 22), true)));

        FixedWorkCompletionStats stats = service.calculateStats(1L, LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 23));

        assertEquals(0.5, stats.overallCompletionRate(), 0.001);
        assertEquals(1, stats.itemRates().get(0).completedCount());
        assertEquals(2, stats.itemRates().get(0).expectedCount());
        assertEquals(1, stats.missLog().size());
    }

    @Test
    void weeklyItemOnlyCountsConfiguredDays() {
        FixedWorkItem item = weeklyItem(2L, "周报", "1,5");
        when(itemRepository.findByUserId(1L)).thenReturn(List.of(item));
        when(completionRepository.findByUserIdAndDateRangeAllStatuses(1L, LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 28)))
                .thenReturn(List.of(completion(2L, LocalDate.of(2026, 6, 22), true)));

        FixedWorkCompletionStats stats = service.calculateStats(1L, LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 28));

        assertEquals(1, stats.itemRates().get(0).expectedCount());
        assertEquals(1, stats.itemRates().get(0).completedCount());
        assertTrue(stats.missLog().isEmpty());
    }

    @Test
    void consecutiveMissDaysCalculatedBackwards() {
        FixedWorkItem item = dailyItem(3L, "日报");
        when(itemRepository.findById(3L)).thenReturn(Optional.of(item));
        LocalDate endDate = LocalDate.of(2026, 6, 24);
        when(completionRepository.findByUserIdAndDateRangeAllStatuses(1L, endDate.minusDays(31), endDate))
                .thenReturn(List.of(
                        completion(3L, endDate, false),
                        completion(3L, endDate.minusDays(1), false),
                        completion(3L, endDate.minusDays(2), true)
                ));

        int days = service.findConsecutiveMissDays(1L, 3L, endDate);

        assertEquals(2, days);
    }

    private FixedWorkItem dailyItem(Long id, String content) {
        FixedWorkItem item = new FixedWorkItem();
        item.setId(id);
        item.setUserId(1L);
        item.setContent(content);
        item.setRecurrenceType("DAILY");
        item.setReminderTime(LocalTime.of(9, 0));
        return item;
    }

    private FixedWorkItem weeklyItem(Long id, String content, String reminderDays) {
        FixedWorkItem item = new FixedWorkItem();
        item.setId(id);
        item.setUserId(1L);
        item.setContent(content);
        item.setRecurrenceType("WEEKLY");
        item.setReminderDays(reminderDays);
        item.setReminderTime(LocalTime.of(9, 0));
        return item;
    }

    private FixedWorkCompletion completion(Long itemId, LocalDate date, boolean completed) {
        FixedWorkCompletion c = new FixedWorkCompletion();
        c.setItemId(itemId);
        c.setUserId(1L);
        c.setCompletionDate(date);
        c.setCompleted(completed);
        c.setCompletedAt(completed ? OffsetDateTime.now() : null);
        return c;
    }
}
```

- [ ] **Step 4：运行新增测试**

Run: `cd file-keeper/server && mvn test -Dtest=FixedWorkCompletionServiceTest`
Expected: 3 tests PASS

- [ ] **Step 5：Commit**

```bash
git add server/src/main/java/com/superprogrammer/workreport/dto/FixedWorkCompletionStats.java \
  server/src/main/java/com/superprogrammer/workreport/service/FixedWorkCompletionService.java \
  server/src/test/java/com/superprogrammer/workreport/FixedWorkCompletionServiceTest.java
git commit -m "feat: 固定工作完成率、逾期、连续未完成天数统计服务"
```

---

## Task 4：Add InboundMessage repository query method

**Files:**
- Modify: `server/src/main/java/com/superprogrammer/workreport/repository/InboundMessageRepository.java`

- [ ] **Step 1：在 `InboundMessageRepository` 增加 IM 工作记录查询**

在 `findPendingByUserId` 方法后添加：

```java
public List<InboundMessage> findConfirmedWorkLogsByUserIdAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
    return jdbcTemplate.query(
        "select id, user_id, platform, platform_message_id, sender_id, sender_name, raw_text, intent, confidence, parsed_payload, status, target_module, target_id, created_by, created_at, updated_by, updated_at, deleted " +
            "from inbound_messages where user_id = ? and status = ? and target_module = ? and deleted = 0 and created_at::date between ? and ? order by created_at desc",
        messageMapper(), userId, InboundMessageStatus.CONFIRMED.name(), "work_log", java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate)
    );
}
```

- [ ] **Step 2：编译检查**

Run: `cd file-keeper/server && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3：Commit**

```bash
git add server/src/main/java/com/superprogrammer/workreport/repository/InboundMessageRepository.java
git commit -m "feat: IM 工作记录周期查询"
```

---

## Task 5：Extend ReportTemplateEngine

**Files:**
- Modify: `server/src/main/java/com/superprogrammer/workreport/service/ReportTemplateEngine.java`
- Modify: `server/src/test/java/com/superprogrammer/workreport/ReportTemplateEngineTest.java`

- [ ] **Step 1：修改 `ReportTemplateEngine` 增加上下文变量**

替换整个文件为：

```java
package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.InspirationNote;
import com.superprogrammer.workreport.entity.WorkLog;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ReportTemplateEngine {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)\\}\\}");

    public String render(String templateContent, Map<String, Object> context) {
        String result = templateContent;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return PLACEHOLDER_PATTERN.matcher(result).replaceAll("");
    }

    public Map<String, Object> buildContext(
            String aiSummary,
            List<WorkLog> logs,
            List<FixedWorkItem> fixedWorkItems,
            String reportType) {
        return buildContext(aiSummary, logs, fixedWorkItems, null, List.of(), List.of(), reportType);
    }

    public Map<String, Object> buildContext(
            String aiSummary,
            List<WorkLog> logs,
            List<FixedWorkItem> fixedWorkItems,
            FixedWorkCompletionStats completionStats,
            List<InboundMessage> inboxWorkLogs,
            List<InspirationNote> inspirationNotes,
            String reportType) {
        Map<String, Object> context = new HashMap<>();
        context.put("ai_summary", aiSummary);
        context.put("logs", formatLogs(logs));
        context.put("fixed_work", formatFixedWork(fixedWorkItems));
        context.put("plans", formatFixedWork(fixedWorkItems));
        context.put("report_type", reportType);
        context.put("generated_at", LocalDateTime.now().toString());
        context.put("issues", "");
        context.put("highlights", "");
        context.put("fixed_work_completion_rate", formatCompletionRate(completionStats));
        context.put("fixed_work_miss_log", formatMissLog(completionStats));
        context.put("fixed_work_consecutive_miss_days", formatConsecutiveMissDays(completionStats));
        context.put("inbox_work_logs", formatInboxWorkLogs(inboxWorkLogs));
        context.put("inspiration_digest", formatInspirationDigest(inspirationNotes));
        return context;
    }

    private String formatLogs(List<WorkLog> logs) {
        return logs.stream()
                .map(log -> "- " + log.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String formatFixedWork(List<FixedWorkItem> items) {
        return items.stream()
                .map(item -> "- " + item.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String formatCompletionRate(FixedWorkCompletionStats stats) {
        if (stats == null || stats.itemRates().isEmpty()) {
            return "暂无固定工作数据";
        }
        int percentage = (int) Math.round(stats.overallCompletionRate() * 100);
        int totalExpected = stats.itemRates().stream().mapToInt(FixedWorkCompletionStats.ItemCompletionRate::expectedCount).sum();
        int totalCompleted = stats.itemRates().stream().mapToInt(FixedWorkCompletionStats.ItemCompletionRate::completedCount).sum();
        return percentage + "% (" + totalCompleted + "/" + totalExpected + ")";
    }

    private String formatMissLog(FixedWorkCompletionStats stats) {
        if (stats == null || stats.missLog().isEmpty()) {
            return "无逾期记录";
        }
        return stats.missLog().stream()
                .map(entry -> "- " + entry.date() + ": " + entry.itemContent())
                .collect(Collectors.joining("\n"));
    }

    private String formatConsecutiveMissDays(FixedWorkCompletionStats stats) {
        if (stats == null || stats.maxConsecutiveMissDays() == 0) {
            return "0 天";
        }
        return stats.maxConsecutiveMissDays() + " 天";
    }

    private String formatInboxWorkLogs(List<InboundMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "无 IM 录入工作记录";
        }
        return messages.stream()
                .map(m -> "- " + m.getRawText())
                .collect(Collectors.joining("\n"));
    }

    private String formatInspirationDigest(List<InspirationNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return "无灵感记录";
        }
        return notes.stream()
                .map(n -> "- " + n.getContent())
                .collect(Collectors.joining("\n"));
    }
}
```

- [ ] **Step 2：在 `ReportTemplateEngineTest` 增加新变量测试**

在 `nullValueBecomesEmpty` 测试后添加：

```java
@Test
void newContextVariablesAreRendered() {
    String template = "{{fixed_work_completion_rate}}\n{{fixed_work_miss_log}}\n{{fixed_work_consecutive_miss_days}}\n{{inbox_work_logs}}\n{{inspiration_digest}}";

    FixedWorkCompletionStats stats = new FixedWorkCompletionStats(
            0.5,
            List.of(new FixedWorkCompletionStats.ItemCompletionRate(1L, "晨会", 0.5, 2, 1)),
            List.of(new FixedWorkCompletionStats.MissLogEntry(LocalDate.of(2026, 6, 23), "晨会")),
            1
    );

    InboundMessage message = new InboundMessage();
    message.setRawText("完成接口");

    InspirationNote note = new InspirationNote();
    note.setContent("AI 接入想法");

    Map<String, Object> context = engine.buildContext(
            "AI总结", List.of(), List.of(), stats, List.of(message), List.of(note), "DAILY"
    );
    String result = engine.render(template, context);

    assertTrue(result.contains("50%"));
    assertTrue(result.contains("2026-06-23: 晨会"));
    assertTrue(result.contains("1 天"));
    assertTrue(result.contains("完成接口"));
    assertTrue(result.contains("AI 接入想法"));
}
```

- [ ] **Step 3：运行测试**

Run: `cd file-keeper/server && mvn test -Dtest=ReportTemplateEngineTest`
Expected: 5 tests PASS

- [ ] **Step 4：Commit**

```bash
git add server/src/main/java/com/superprogrammer/workreport/service/ReportTemplateEngine.java \
  server/src/test/java/com/superprogrammer/workreport/ReportTemplateEngineTest.java
git commit -m "feat: 报告模板引擎增加完成率、逾期、IM 记录、灵感摘要变量"
```

---

## Task 6：Enhance AiSummaryService

**Files:**
- Modify: `server/src/main/java/com/superprogrammer/workreport/service/AiSummaryService.java`
- Modify: `server/src/test/java/com/superprogrammer/workreport/AiSummaryServiceTest.java`

- [ ] **Step 1：在 `AiSummaryService` 增加上下文 record 并改造 summarize / buildPrompt**

在类顶部 `private final AiConfigService aiConfigService;` 后添加：

```java
public record AiSummaryContext(
        List<WorkLog> logs,
        List<FixedWorkItem> fixedWorkItems,
        String reportType,
        FixedWorkCompletionStats completionStats,
        List<InboundMessage> inboxWorkLogs,
        List<InspirationNote> inspirationNotes
) {
}
```

替换 `summarize(List<WorkLog> logs, List<FixedWorkItem> fixedWorkItems, String reportType, Long aiConfigId, Long userId)` 为 delegating 方法：

```java
public String summarize(List<WorkLog> logs, List<FixedWorkItem> fixedWorkItems,
                        String reportType, Long aiConfigId, Long userId) {
    return summarize(new AiSummaryContext(logs, fixedWorkItems, reportType, null, List.of(), List.of()), aiConfigId, userId);
}

public String summarize(AiSummaryContext context, Long aiConfigId, Long userId) {
    if (isEmptyContext(context)) {
        return "";
    }

    AiConfigVO config = aiConfigService.getEffectiveConfig(userId, aiConfigId);
    if (config == null || !Boolean.TRUE.equals(config.enabled())) {
        log.warn("未找到有效 AI 配置，使用降级策略");
        return fallbackSummary(context);
    }

    String apiKey = aiConfigService.getDecryptedApiKey(userId, aiConfigId);
    if (apiKey == null || apiKey.isBlank()) {
        log.warn("AI 配置未设置 API Key，使用降级策略");
        return fallbackSummary(context);
    }

    String prompt = buildPrompt(context);

    try {
        return callAiApi(prompt, config, apiKey);
    } catch (Exception e) {
        log.error("AI 总结失败，降级为简单拼接", e);
        return fallbackSummary(context);
    }
}

private boolean isEmptyContext(AiSummaryContext context) {
    return context.logs().isEmpty()
            && context.fixedWorkItems().isEmpty()
            && (context.inboxWorkLogs() == null || context.inboxWorkLogs().isEmpty())
            && (context.inspirationNotes() == null || context.inspirationNotes().isEmpty());
}
```

替换 `buildPrompt` 方法为：

```java
private String buildPrompt(AiSummaryContext context) {
    String reportName = "DAILY".equals(context.reportType()) ? "日报" : "周报";
    String periodLabel = "DAILY".equals(context.reportType()) ? "今日" : "本周";
    String nextLabel = "DAILY".equals(context.reportType()) ? "明日" : "下周";

    StringBuilder sb = new StringBuilder();
    sb.append("请根据以下信息生成一份简洁规范的").append(reportName).append("：\n\n");

    sb.append("【工作记录】\n");
    for (WorkLog log : context.logs()) {
        sb.append("- ").append(log.getContent()).append("\n");
    }
    if (context.logs().isEmpty()) {
        sb.append("无\n");
    }

    sb.append("\n【固定工作完成情况】\n");
    for (FixedWorkItem item : context.fixedWorkItems()) {
        sb.append("- ").append(item.getContent()).append("（已完成）\n");
    }
    if (context.fixedWorkItems().isEmpty()) {
        sb.append("无\n");
    }

    FixedWorkCompletionStats stats = context.completionStats();
    if (stats != null) {
        sb.append("\n【固定工作完成率】\n");
        int percentage = (int) Math.round(stats.overallCompletionRate() * 100);
        sb.append(percentage).append("%\n");

        if (!stats.missLog().isEmpty()) {
            sb.append("\n【逾期/未完成记录】\n");
            for (var entry : stats.missLog()) {
                sb.append("- ").append(entry.date()).append(": ").append(entry.itemContent()).append("\n");
            }
        }
    }

    if (context.inboxWorkLogs() != null && !context.inboxWorkLogs().isEmpty()) {
        sb.append("\n【IM 录入工作记录】\n");
        for (InboundMessage message : context.inboxWorkLogs()) {
            sb.append("- ").append(message.getRawText()).append("\n");
        }
    }

    if (context.inspirationNotes() != null && !context.inspirationNotes().isEmpty()) {
        sb.append("\n【灵感随记】\n");
        for (InspirationNote note : context.inspirationNotes()) {
            sb.append("- ").append(note.getContent()).append("\n");
        }
    }

    sb.append("\n要求：\n");
    sb.append("1. 用第一人称\n");
    sb.append("2. 分四部分：").append(periodLabel).append("已完成、").append(periodLabel).append("未完成/逾期、").append(nextLabel).append("计划、灵感速览\n");
    sb.append("3. 语言简洁专业\n");
    return sb.toString();
}
```

替换 `fallbackSummary` 方法为：

```java
private String fallbackSummary(AiSummaryContext context) {
    StringBuilder sb = new StringBuilder();
    sb.append("## 工作记录\n");
    context.logs().forEach(log -> sb.append("- ").append(log.getContent()).append("\n"));
    sb.append("\n## 固定工作完成情况\n");
    context.fixedWorkItems().forEach(item -> sb.append("- ").append(item.getContent()).append("\n"));
    if (context.completionStats() != null && !context.completionStats().missLog().isEmpty()) {
        sb.append("\n## 逾期/未完成记录\n");
        context.completionStats().missLog().forEach(entry ->
                sb.append("- ").append(entry.date()).append(": ").append(entry.itemContent()).append("\n"));
    }
    if (context.inspirationNotes() != null && !context.inspirationNotes().isEmpty()) {
        sb.append("\n## 灵感速览\n");
        context.inspirationNotes().forEach(note -> sb.append("- ").append(note.getContent()).append("\n"));
    }
    return sb.toString();
}
```

在 imports 中增加：

```java
import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.InspirationNote;
```

- [ ] **Step 2：更新 `AiSummaryServiceTest` 的 dailyPrompt 测试以验证新结构**

在 `dailyPromptContainsContent` 测试后添加：

```java
@Test
void enhancedPromptContainsCompletionStatsAndInspirations() {
    WorkLog log = new WorkLog();
    log.setContent("工作记录");
    log.setLogDate(LocalDate.now());

    FixedWorkCompletionStats stats = new FixedWorkCompletionStats(
            0.5,
            List.of(new FixedWorkCompletionStats.ItemCompletionRate(1L, "晨会", 0.5, 2, 1)),
            List.of(new FixedWorkCompletionStats.MissLogEntry(LocalDate.now().minusDays(1), "晨会")),
            1
    );

    InspirationNote note = new InspirationNote();
    note.setContent("新想法");

    when(aiConfigService.getEffectiveConfig(1L, null)).thenReturn(null);

    String result = aiSummaryService.summarize(
            new AiSummaryService.AiSummaryContext(List.of(log), List.of(), "WEEKLY", stats, List.of(), List.of(note)),
            null, 1L
    );

    assertTrue(result.contains("工作记录"));
    assertTrue(result.contains("新想法"));
    assertTrue(result.contains("晨会"));
}
```

- [ ] **Step 3：运行测试**

Run: `cd file-keeper/server && mvn test -Dtest=AiSummaryServiceTest`
Expected: 测试 PASS

- [ ] **Step 4：Commit**

```bash
git add server/src/main/java/com/superprogrammer/workreport/service/AiSummaryService.java \
  server/src/test/java/com/superprogrammer/workreport/AiSummaryServiceTest.java
git commit -m "feat: AI 总结提示词增强，支持完成率、逾期、IM 记录、灵感摘要"
```

---

## Task 7：Wire WorkReportService

**Files:**
- Modify: `server/src/main/java/com/superprogrammer/workreport/service/WorkReportService.java`
- Modify: `server/src/test/java/com/superprogrammer/workreport/WorkReportServiceTest.java`

- [ ] **Step 1：修改 `WorkReportService` 注入依赖并聚合上下文**

在 imports 区添加：

```java
import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.InspirationNote;
import com.superprogrammer.workreport.repository.InboundMessageRepository;
import com.superprogrammer.workreport.repository.InspirationNoteRepository;
```

替换整个文件为：

```java
package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.dto.WorkReportDto;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.InspirationNote;
import com.superprogrammer.workreport.entity.ReportConfig;
import com.superprogrammer.workreport.entity.ReportTemplate;
import com.superprogrammer.workreport.entity.WorkLog;
import com.superprogrammer.workreport.entity.WorkReport;
import com.superprogrammer.workreport.repository.FixedWorkCompletionRepository;
import com.superprogrammer.workreport.repository.FixedWorkItemRepository;
import com.superprogrammer.workreport.repository.InboundMessageRepository;
import com.superprogrammer.workreport.repository.InspirationNoteRepository;
import com.superprogrammer.workreport.repository.ReportConfigRepository;
import com.superprogrammer.workreport.repository.ReportTemplateRepository;
import com.superprogrammer.workreport.repository.WorkLogRepository;
import com.superprogrammer.workreport.repository.WorkReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkReportService {

    private final WorkReportRepository workReportRepository;
    private final ReportConfigRepository reportConfigRepository;
    private final ReportTemplateRepository reportTemplateRepository;
    private final WorkLogRepository workLogRepository;
    private final FixedWorkItemRepository fixedWorkItemRepository;
    private final FixedWorkCompletionRepository fixedWorkCompletionRepository;
    private final FixedWorkCompletionService fixedWorkCompletionService;
    private final InboundMessageRepository inboundMessageRepository;
    private final InspirationNoteRepository inspirationNoteRepository;
    private final AiSummaryService aiSummaryService;
    private final ReportTemplateEngine templateEngine;

    public PageResult<WorkReportDto> pageByUser(Long userId, int page, int size) {
        long total = workReportRepository.countByUserId(userId);
        var records = workReportRepository.findByUserId(userId, page, size).stream()
                .map(this::toDto)
                .toList();
        return new PageResult<>(records, total, page, size);
    }

    public WorkReport getEntityById(Long id) {
        return workReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
    }

    public WorkReport getEntityByUserAndId(Long userId, Long id) {
        return requireOwnedByUser(id, userId);
    }

    @Transactional
    public void updateStatus(WorkReport report) {
        WorkReport existing = workReportRepository.findById(report.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        existing.setStatus(report.getStatus());
        existing.setUpdatedBy(report.getUpdatedBy());
        workReportRepository.update(existing);
    }

    public WorkReportDto getById(Long userId, Long id) {
        WorkReport report = requireOwnedByUser(id, userId);
        return toDto(report);
    }

    public void delete(Long userId, Long id) {
        requireOwnedByUser(id, userId);
        workReportRepository.softDeleteById(id, userId);
    }

    @Transactional
    public WorkReportDto generate(Long userId, Long configId) {
        ReportConfig config = reportConfigRepository.findById(configId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告配置不存在"));
        if (!config.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该报告配置");
        }

        ReportTemplate template = reportTemplateRepository.findById(config.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告模板不存在"));

        DateRange logRange = calculateDateRange(config.getReportType());
        DateRange planRange = calculatePlanDateRange(config.getReportType());

        List<WorkLog> logs = workLogRepository.findByUserIdAndDateRange(userId, logRange.start(), logRange.end());
        List<FixedWorkItem> completedFixedWork = findCompletedFixedWork(userId, planRange.start(), planRange.end());
        FixedWorkCompletionStats completionStats = fixedWorkCompletionService.calculateStats(userId, planRange.start(), planRange.end());
        List<InboundMessage> inboxWorkLogs = inboundMessageRepository.findConfirmedWorkLogsByUserIdAndDateRange(userId, logRange.start(), logRange.end());
        List<InspirationNote> inspirationNotes = Boolean.TRUE.equals(config.getIncludeInspirationDigest())
                ? inspirationNoteRepository.findByUserIdAndDateRange(userId, logRange.start(), logRange.end())
                : List.of();

        AiSummaryService.AiSummaryContext aiContext = new AiSummaryService.AiSummaryContext(
                logs, completedFixedWork, config.getReportType(), completionStats, inboxWorkLogs, inspirationNotes
        );
        String aiSummary = Boolean.TRUE.equals(config.getAiEnabled())
                ? aiSummaryService.summarize(aiContext, config.getAiConfigId(), userId)
                : "";

        Map<String, Object> context = templateEngine.buildContext(
                aiSummary, logs, completedFixedWork, completionStats, inboxWorkLogs, inspirationNotes, config.getReportType()
        );
        String content = templateEngine.render(template.getContent(), context);

        String title = generateTitle(config.getReportType(), logRange);

        WorkReport report = new WorkReport();
        report.setUserId(userId);
        report.setConfigId(configId);
        report.setReportType(config.getReportType());
        report.setTitle(title);
        report.setContent(content);
        report.setGeneratedAt(OffsetDateTime.now());
        report.setStatus("GENERATED");
        report.setCompletionRate(completionStats.overallCompletionRate());
        report.setConsecutiveMissDays(completionStats.maxConsecutiveMissDays());
        report.setCreatedBy(userId);
        report.setUpdatedBy(userId);

        WorkReport saved = workReportRepository.insert(report);
        return toDto(saved);
    }

    private DateRange calculateDateRange(String reportType) {
        LocalDate today = LocalDate.now();
        if ("WEEKLY".equals(reportType)) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new DateRange(monday, today);
        }
        return new DateRange(today, today);
    }

    private DateRange calculatePlanDateRange(String reportType) {
        return calculateDateRange(reportType);
    }

    private List<FixedWorkItem> findCompletedFixedWork(Long userId, LocalDate startDate, LocalDate endDate) {
        var completions = fixedWorkCompletionRepository.findByUserIdAndDateRange(userId, startDate, endDate);
        if (completions.isEmpty()) {
            return List.of();
        }
        java.util.Set<Long> itemIds = completions.stream()
                .map(com.superprogrammer.workreport.entity.FixedWorkCompletion::getItemId)
                .collect(java.util.stream.Collectors.toSet());
        return fixedWorkItemRepository.findByUserId(userId).stream()
                .filter(item -> itemIds.contains(item.getId()))
                .toList();
    }

    private String generateTitle(String reportType, DateRange range) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        if ("WEEKLY".equals(reportType)) {
            return range.start().format(formatter) + " ~ " + range.end().format(formatter) + " 周报";
        }
        return range.start().format(formatter) + " 日报";
    }

    private WorkReport requireOwnedByUser(Long id, Long userId) {
        WorkReport report = workReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        if (!report.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该报告");
        }
        return report;
    }

    private WorkReportDto toDto(WorkReport report) {
        return new WorkReportDto(
                report.getId(),
                report.getReportType(),
                report.getTitle(),
                report.getContent(),
                toLocalDateTime(report.getGeneratedAt()),
                report.getStatus(),
                report.getCompletionRate(),
                report.getConsecutiveMissDays()
        );
    }

    private LocalDateTime toLocalDateTime(java.time.OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private record DateRange(LocalDate start, LocalDate end) {
    }
}
```

- [ ] **Step 2：更新 `WorkReportServiceTest` 的 insertConfig helper 添加新字段**

替换 `insertConfig` 方法为：

```java
private Long insertConfig(Long userId, Long templateId, String reportType, boolean aiEnabled) {
    jdbcTemplate.update(
            "insert into report_configs (user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, include_inspiration_digest, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, '0 9 * * *', 'Asia/Shanghai', true, ?, true, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
            userId, reportType + "配置", reportType, templateId, aiEnabled, userId, userId
    );
    return jdbcTemplate.queryForObject("select id from report_configs where user_id = ? order by id desc limit 1", Long.class, userId);
}
```

- [ ] **Step 3：运行测试**

Run: `cd file-keeper/server && mvn test -Dtest=WorkReportServiceTest`
Expected: 测试 PASS

- [ ] **Step 4：Commit**

```bash
git add server/src/main/java/com/superprogrammer/workreport/service/WorkReportService.java \
  server/src/test/java/com/superprogrammer/workreport/WorkReportServiceTest.java
git commit -m "feat: 报告生成聚合固定工作统计、IM 记录与灵感摘要上下文"
```

---

## Task 8：Create Flyway migration V14

**Files:**
- Create: `server/src/main/resources/db/migration/V14__update_default_templates.sql`

- [ ] **Step 1：创建迁移文件**

```sql
-- =============================================================================
-- V14__update_default_templates.sql
-- 用途：Phase 3 AI 报告增强
-- 1. 报告配置增加 include_inspiration_digest 开关
-- 2. 已生成报告增加 completion_rate 与 consecutive_miss_days 元数据
-- 3. 升级系统默认报告模板，支持新上下文变量
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 报告配置：是否在新报告中包含灵感摘要
-- -----------------------------------------------------------------------------
ALTER TABLE report_configs ADD COLUMN IF NOT EXISTS include_inspiration_digest BOOLEAN NOT NULL DEFAULT TRUE;

-- -----------------------------------------------------------------------------
-- 2. 已生成报告：固定工作完成率与连续未完成天数
-- -----------------------------------------------------------------------------
ALTER TABLE work_reports ADD COLUMN IF NOT EXISTS completion_rate DECIMAL(5,4);
ALTER TABLE work_reports ADD COLUMN IF NOT EXISTS consecutive_miss_days INT;

-- -----------------------------------------------------------------------------
-- 3. 升级默认模板：在 {{fixed_work}} 后追加新变量块
-- -----------------------------------------------------------------------------
UPDATE report_templates
SET content = REPLACE(content, '{{fixed_work}}', '{{fixed_work}}

## 固定工作完成率
{{fixed_work_completion_rate}}

## 逾期记录
{{fixed_work_miss_log}}

## 连续未完成天数
{{fixed_work_consecutive_miss_days}}

## IM 录入工作
{{inbox_work_logs}}

## 灵感速览
{{inspiration_digest}}')
WHERE is_default = TRUE AND content LIKE '%{{fixed_work}}%';
```

- [ ] **Step 2：本地启动服务验证 Flyway 执行**

Run: `cd file-keeper/server && mvn spring-boot:run -Dspring-boot.run.profiles=dev`
Expected: 控制台无 Flyway 报错，应用正常启动

（或运行集成测试，见 Task 13 全量测试）

- [ ] **Step 3：Commit**

```bash
git add server/src/main/resources/db/migration/V14__update_default_templates.sql
git commit -m "feat: V14 迁移增加灵感摘要开关、报告元数据、升级默认模板"
```

---

## Task 9：Backend integration tests

**Files:**
- Modify: `server/src/test/java/com/superprogrammer/workreport/WorkReportServiceTest.java`
- Modify: `server/src/test/java/com/superprogrammer/workreport/ReportConfigServiceTest.java`

- [ ] **Step 1：在 `WorkReportServiceTest` 增加生成报告含元数据断言**

在 `generateDailyReportWithAiDisabled` 测试末尾追加：

```java
assertNotNull(report.completionRate());
assertTrue(report.consecutiveMissDays() != null && report.consecutiveMissDays() >= 0);
```

- [ ] **Step 2：在 `ReportConfigServiceTest` 中验证 includeInspirationDigest 默认值（如已有测试则更新，否则新增）**

新增测试：

```java
@Test
void saveConfigDefaultsIncludeInspirationDigestToTrue(@Autowired ReportConfigService reportConfigService, @Autowired JdbcTemplate jdbcTemplate) {
    Long userId = insertUser("inspiration-default@example.com");
    Long templateId = insertTemplate();

    ReportConfigDto saved = reportConfigService.save(userId, new com.superprogrammer.workreport.dto.SaveReportConfigRequest(
            null, "默认配置", "DAILY", templateId, "0 9 * * *", "Asia/Shanghai", true, true, null, null, List.of()
    ));

    assertTrue(Boolean.TRUE.equals(saved.includeInspirationDigest()));
}
```

（`insertUser` / `insertTemplate`  helper 已存在或按同类测试补齐）

- [ ] **Step 3：运行全量后端测试**

Run: `cd file-keeper/server && mvn test`
Expected: 后端测试全部 PASS

- [ ] **Step 4：Commit**

```bash
git add server/src/test/java/com/superprogrammer/workreport/WorkReportServiceTest.java \
  server/src/test/java/com/superprogrammer/workreport/ReportConfigServiceTest.java
git commit -m "test: Phase 3 后端集成测试补充"
```

---

## Task 10：Frontend types and API

**Files:**
- Modify: `src/types/workReport.ts`
- Modify: `src/api/workReport.ts`
- Modify: `src/api/__tests__/workReport.test.ts`

- [ ] **Step 1：修改 `src/types/workReport.ts`**

在 `ReportConfig` 接口中 `aiConfigId?: number` 后添加：

```typescript
includeInspirationDigest: boolean
```

在 `WorkReport` 接口中 `status: string` 后添加：

```typescript
completionRate?: number
consecutiveMissDays?: number
```

- [ ] **Step 2：修改 `src/api/workReport.ts` 的 `saveReportConfig`**

替换 `saveReportConfig` 为：

```typescript
export async function saveReportConfig(
  baseUrl: string,
  token: string,
  deviceId: string,
  config: Partial<ReportConfig>,
): Promise<ReportConfig> {
  const payload = {
    ...config,
    pushTargets: undefined,
    pushTargetIds: config.pushTargetIds || [],
    includeInspirationDigest: config.includeInspirationDigest ?? true,
  }
  return request<ReportConfig>(baseUrl, token, deviceId, '/configs', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
```

- [ ] **Step 3：在 `src/api/__tests__/workReport.test.ts` 增加保存配置透传字段测试**

在 `saves report config` 测试后添加：

```typescript
it('saves report config with includeInspirationDigest', async () => {
  mocks.fetch.mockResolvedValueOnce({
    ok: true,
    status: 200,
    json: () => Promise.resolve({ code: 200, msg: 'success', data: { id: 1, name: '配置' } }),
  })

  await saveReportConfig('http://localhost:8088', 'token', 'device-1', {
    name: '配置',
    includeInspirationDigest: false,
  })

  expect(mocks.fetch).toHaveBeenCalledWith(
    'http://localhost:8088/api/client/work-report/configs?deviceId=device-1',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer token',
      },
      body: JSON.stringify({ name: '配置', includeInspirationDigest: false, pushTargetIds: [] }),
    },
  )
})
```

- [ ] **Step 4：运行前端 API 测试**

Run: `cd file-keeper && npm test -- src/api/__tests__/workReport.test.ts`
Expected: 测试 PASS

- [ ] **Step 5：Commit**

```bash
git add src/types/workReport.ts src/api/workReport.ts src/api/__tests__/workReport.test.ts
git commit -m "feat: 前端类型与 API 支持灵感摘要开关和报告元数据"
```

---

## Task 11：ReportConfigForm.vue add inspiration switch

**Files:**
- Modify: `src/components/work-report/ReportConfigForm.vue`

- [ ] **Step 1：在编辑表单的 AI 配置区下方增加开关**

在编辑表单 `</div>`（AI 配置区结束）后、推送目标区之前插入：

```vue
<div class="flex items-center space-x-4 pt-2">
  <label class="flex items-center space-x-2 text-sm">
    <input v-model="editingConfig.includeInspirationDigest" type="checkbox" />
    <span>{{ t('workReport.includeInspirationDigest') }}</span>
  </label>
</div>
```

- [ ] **Step 2：在新增表单的 AI 配置区下方增加同样开关**

在新增表单对应位置插入：

```vue
<div class="flex items-center space-x-4 pt-2">
  <label class="flex items-center space-x-2 text-sm">
    <input v-model="newConfig.includeInspirationDigest" type="checkbox" />
    <span>{{ t('workReport.includeInspirationDigest') }}</span>
  </label>
</div>
```

- [ ] **Step 3：更新 `defaultConfig` 默认值**

在 `defaultConfig` 中添加：

```typescript
includeInspirationDigest: true,
```

- [ ] **Step 4：编译检查**

Run: `cd file-keeper && npm run build`
Expected: 无 TypeScript 错误

- [ ] **Step 5：Commit**

```bash
git add src/components/work-report/ReportConfigForm.vue
git commit -m "feat: 报告配置表单增加包含灵感摘要开关"
```

---

## Task 12：ReportViewer.vue show completion rate

**Files:**
- Modify: `src/components/work-report/ReportViewer.vue`
- Test: `src/components/__tests__/reportViewer.test.ts`

- [ ] **Step 1：在 `ReportViewer.vue` 报告内容下方增加元数据卡片**

在 `<pre>` 标签后、外层的 `</div>` 之前插入：

```vue
<div v-if="report?.completionRate !== undefined" class="mt-3 p-3 rounded-lg bg-gray-50 dark:bg-dark-bg border border-gray-200 dark:border-dark-border">
  <div class="flex items-center justify-between text-xs">
    <span class="font-medium text-gray-700 dark:text-gray-300">{{ t('workReport.completionRate') }}</span>
    <span class="text-[var(--accent-subtle-text)]">{{ Math.round(report.completionRate * 100) }}%</span>
  </div>
  <div v-if="(report.consecutiveMissDays ?? 0) > 0" class="flex items-center justify-between text-xs mt-2">
    <span class="font-medium text-red-600 dark:text-red-400">{{ t('workReport.consecutiveMissDays') }}</span>
    <span class="text-red-600 dark:text-red-400">{{ report.consecutiveMissDays }} {{ t('workReport.days') }}</span>
  </div>
</div>
```

- [ ] **Step 2：创建组件测试 `src/components/__tests__/reportViewer.test.ts`**

```typescript
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ReportViewer from '@/components/work-report/ReportViewer.vue'

vi.mock('@/composables/useI18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

describe('ReportViewer', () => {
  it('renders completion rate card', () => {
    const wrapper = mount(ReportViewer, {
      global: { plugins: [createPinia()] },
      props: {
        report: {
          id: 1,
          title: '日报',
          content: '内容',
          reportType: 'DAILY',
          generatedAt: '2026-06-29T10:00:00Z',
          status: 'GENERATED',
          completionRate: 0.75,
          consecutiveMissDays: 0,
        },
      },
    })

    expect(wrapper.text()).toContain('workReport.completionRate')
    expect(wrapper.text()).toContain('75%')
  })

  it('shows consecutive miss days when greater than zero', () => {
    const wrapper = mount(ReportViewer, {
      global: { plugins: [createPinia()] },
      props: {
        report: {
          id: 1,
          title: '周报',
          content: '内容',
          reportType: 'WEEKLY',
          generatedAt: '2026-06-29T10:00:00Z',
          status: 'GENERATED',
          completionRate: 0.5,
          consecutiveMissDays: 2,
        },
      },
    })

    expect(wrapper.text()).toContain('workReport.consecutiveMissDays')
    expect(wrapper.text()).toContain('2')
  })
})
```

- [ ] **Step 3：运行组件测试**

Run: `cd file-keeper && npm test -- src/components/__tests__/reportViewer.test.ts`
Expected: 2 tests PASS

- [ ] **Step 4：Commit**

```bash
git add src/components/work-report/ReportViewer.vue \
  src/components/__tests__/reportViewer.test.ts
git commit -m "feat: 报告预览展示完成率与连续未完成天数卡片"
```

---

## Task 13：Locale updates

**Files:**
- Modify: `src/locales/zh-CN.ts`
- Modify: `src/locales/en.ts`

- [ ] **Step 1：在 `zh-CN.ts` 的 `workReport` 区块添加文案**

在 `aiConfig` 前、即 `emptyInbox: '暂无待确认消息'` 后添加：

```typescript
includeInspirationDigest: '包含灵感摘要',
completionRate: '固定工作完成率',
consecutiveMissDays: '连续未完成天数',
days: '天',
```

- [ ] **Step 2：在 `en.ts` 的 `workReport` 区块添加对应文案**

```typescript
includeInspirationDigest: 'Include Inspiration Digest',
completionRate: 'Fixed Work Completion Rate',
consecutiveMissDays: 'Consecutive Miss Days',
days: 'days',
```

- [ ] **Step 3：运行前端测试**

Run: `cd file-keeper && npm test`
Expected: 前端测试全部 PASS

- [ ] **Step 4：Commit**

```bash
git add src/locales/zh-CN.ts src/locales/en.ts
git commit -m "feat: 中英文文案补充"
```

---

## Task 14：Update store tests

**Files:**
- Modify: `src/stores/__tests__/workReportStore.test.ts`

- [ ] **Step 1：更新 store 测试中 saveConfig 的 mock 断言（如需要）**

当前 `saveConfig` 测试未校验 payload 细节，但 TypeScript 类型已变化。确认 mock 列表已包含 `saveReportConfig` 即可。

- [ ] **Step 2：运行 store 测试**

Run: `cd file-keeper && npm test -- src/stores/__tests__/workReportStore.test.ts`
Expected: 测试 PASS

- [ ] **Step 3：Commit**

```bash
git add src/stores/__tests__/workReportStore.test.ts
git commit -m "test: 同步 workReportStore 类型测试"
```

---

## Task 15：Update progress documents

**Files:**
- Modify: `docs/superpowers/plans/interactive-ai-work-assistant/phase3/progress.md`
- Modify: `docs/superpowers/plans/interactive-ai-work-assistant/progress.md`

- [ ] **Step 1：更新 Phase 3 进度**

将 `docs/superpowers/plans/interactive-ai-work-assistant/phase3/progress.md` 状态改为：

```markdown
**状态：** 🟡 进行中
**进度：** 100%（实现完成）
```

并将所有 `- [ ]` 改为 `- [x]`。

- [ ] **Step 2：更新总进度**

在 `docs/superpowers/plans/interactive-ai-work-assistant/progress.md` 中：

```markdown
| Phase 3 | 🟢 已完成 | 100% | AI 报告增强全部任务完成 |
```

并在最近更新追加：

```markdown
- 2026-06-29：Phase 3 全部任务完成，后端测试、前端测试全部通过
```

- [ ] **Step 3：Commit**

```bash
git add docs/superpowers/plans/interactive-ai-work-assistant/phase3/progress.md \
  docs/superpowers/plans/interactive-ai-work-assistant/progress.md
git commit -m "docs: 更新 Phase 3 与总进度"
```

---

## Self-Review

**1. Spec coverage:**
- 固定工作统计服务（完成率、逾期日志、连续未完成天数）→ Task 3
- 报告上下文扩展（`fixed_work_completion_rate`、`fixed_work_miss_log`、`inbox_work_logs`、`inspiration_digest`）→ Task 5
- AI 提示词增强（已完成/未完成/下周计划/灵感速览）→ Task 6
- 默认模板升级 → Task 8
- 前端展示完成率元数据 → Task 12
- 报告配置可选“包含灵感摘要” → Task 11
- 里程碑全部有对应任务覆盖

**2. Placeholder scan:**
- 无 TBD / TODO
- 所有代码步骤给出完整代码
- 所有命令给出预期输出

**3. Type consistency:**
- `FixedWorkCompletionStats` 字段名在 service、template engine、AiSummaryService 中一致
- `includeInspirationDigest` 在 entity / dto / request / repository / service 中一致
- `completionRate` / `consecutiveMissDays` 在 entity / dto / repository / service 中一致

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-29-ai-report-enhancement.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints

**Which approach?**
