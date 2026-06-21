// agent-platform/backend/src/main/java/com/superprogrammer/execution/service/ExecutionLogService.java
package com.superprogrammer.execution.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.execution.entity.ExecutionLog;
import com.superprogrammer.execution.mapper.ExecutionLogMapper;
import com.superprogrammer.execution.vo.ExecutionRecoveryInfoVO;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionLogService {

    private final ExecutionLogMapper executionLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * 开始执行 - 记录日志
     */
    public ExecutionLog startExecution(Long workflowId, String workflowName, Long triggeredBy) {
        ExecutionLog executionLog = new ExecutionLog();
        executionLog.setWorkflowId(workflowId);
        executionLog.setWorkflowName(workflowName);
        executionLog.setTriggeredBy(triggeredBy);
        executionLog.setStatus("RUNNING");
        executionLog.setStartedAt(OffsetDateTime.now());
        executionLog.setCreatedBy(triggeredBy);
        executionLog.setUpdatedBy(triggeredBy);
        executionLogMapper.insert(executionLog);

        log.info("执行开始: id={}, workflowId={}", executionLog.getId(), workflowId);
        return executionLog;
    }

    public ExecutionLog startRuntimeExecution(
            Long workflowId,
            String workflowName,
            Long triggeredBy,
            String sourceType,
            Long sourceId,
            Long parentExecutionId,
            Long rootExecutionId,
            String traceId) {
        ExecutionLog executionLog = new ExecutionLog();
        executionLog.setWorkflowId(workflowId);
        executionLog.setWorkflowName(workflowName);
        executionLog.setTriggeredBy(triggeredBy);
        executionLog.setStatus("RUNNING");
        executionLog.setSourceType(sourceType);
        executionLog.setSourceId(sourceId);
        executionLog.setParentExecutionId(parentExecutionId);
        executionLog.setRootExecutionId(rootExecutionId);
        executionLog.setTraceId(traceId);
        executionLog.setStartedAt(OffsetDateTime.now());
        executionLog.setCreatedBy(triggeredBy);
        executionLog.setUpdatedBy(triggeredBy);
        executionLogMapper.insert(executionLog);

        if (executionLog.getRootExecutionId() == null) {
            executionLog.setRootExecutionId(executionLog.getId());
            executionLogMapper.updateById(executionLog);
        }

        log.info("运行时执行开始: id={}, rootId={}, sourceType={}, sourceId={}",
                executionLog.getId(), executionLog.getRootExecutionId(), sourceType, sourceId);
        return executionLog;
    }

    public void updateRuntimeRefs(Long executionId, String externalThreadId, String checkpointRef) {
        ExecutionLog executionLog = getExecutionLog(executionId);
        if (externalThreadId != null) {
            executionLog.setExternalThreadId(externalThreadId);
        }
        if (checkpointRef != null) {
            executionLog.setCheckpointRef(checkpointRef);
        }
        executionLogMapper.updateById(executionLog);
    }

    public void appendRuntimeEventSnapshot(Long executionId, Map<String, ?> eventSnapshot) {
        ExecutionLog executionLog = getExecutionLog(executionId);
        List<Map<String, Object>> events = readNodeLogEvents(executionLog.getNodeLogs());
        events.add(new java.util.LinkedHashMap<>(eventSnapshot));
        try {
            executionLog.setNodeLogs(objectMapper.writeValueAsString(events));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "执行事件序列化失败");
        }
        executionLogMapper.updateById(executionLog);
    }

    /**
     * 执行完成 - 更新日志
     */
    public void finishExecution(Long executionId, String nodeLogs) {
        ExecutionLog executionLog = executionLogMapper.selectById(executionId);
        if (executionLog == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "执行记录不存在");
        }

        OffsetDateTime now = OffsetDateTime.now();
        executionLog.setStatus("SUCCESS");
        executionLog.setCompletedAt(now);
        executionLog.setDuration(
                java.time.Duration.between(executionLog.getStartedAt(), now).toMillis());
        if (nodeLogs != null && !nodeLogs.isBlank()) {
            executionLog.setNodeLogs(nodeLogs);
        }
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

        OffsetDateTime now = OffsetDateTime.now();
        executionLog.setStatus("FAILED");
        executionLog.setCompletedAt(now);
        executionLog.setDuration(
                java.time.Duration.between(executionLog.getStartedAt(), now).toMillis());
        executionLog.setErrorMessage(errorMessage);
        executionLogMapper.updateById(executionLog);

        log.error("执行失败: id={}, error={}", executionId, errorMessage);
    }

    public void waitForApproval(Long executionId, String nodeId, String approvalKey) {
        ExecutionLog executionLog = executionLogMapper.selectById(executionId);
        if (executionLog == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "执行记录不存在");
        }
        executionLog.setStatus("WAITING_APPROVAL");
        executionLog.setNodeId(nodeId);
        executionLog.setErrorMessage("等待人工审批: " + approvalKey);
        executionLogMapper.updateById(executionLog);

        log.info("执行等待人工审批: id={}, nodeId={}, approvalKey={}", executionId, nodeId, approvalKey);
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

    public ExecutionLog getVisibleExecutionLog(Long id, Long triggeredBy) {
        ExecutionLog log = getExecutionLog(id);
        if (triggeredBy != null && !triggeredBy.equals(log.getTriggeredBy())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该执行任务");
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

    public List<ExecutionLog> listVisibleExecutions(Long triggeredBy) {
        LambdaQueryWrapper<ExecutionLog> wrapper = new LambdaQueryWrapper<>();
        if (triggeredBy != null) {
            wrapper.eq(ExecutionLog::getTriggeredBy, triggeredBy);
        }
        wrapper.orderByDesc(ExecutionLog::getStartedAt);
        return executionLogMapper.selectList(wrapper);
    }

    public ExecutionLog findByCheckpointRef(String checkpointRef) {
        if (checkpointRef == null || checkpointRef.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "checkpointRef不能为空");
        }
        LambdaQueryWrapper<ExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExecutionLog::getCheckpointRef, checkpointRef)
                .orderByDesc(ExecutionLog::getStartedAt)
                .last("LIMIT 1");
        List<ExecutionLog> logs = executionLogMapper.selectList(wrapper);
        if (logs == null || logs.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "checkpoint执行记录不存在");
        }
        return logs.get(0);
    }

    public List<ExecutionLog> listPendingApprovals() {
        LambdaQueryWrapper<ExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExecutionLog::getStatus, "WAITING_APPROVAL")
                .orderByDesc(ExecutionLog::getStartedAt);
        return executionLogMapper.selectList(wrapper);
    }

    public ExecutionRecoveryInfoVO getRecoveryInfo(Long executionId) {
        ExecutionLog executionLog = getExecutionLog(executionId);
        return buildRecoveryInfo(executionLog);
    }

    public ExecutionRecoveryInfoVO getVisibleRecoveryInfo(Long executionId, Long triggeredBy) {
        ExecutionLog executionLog = getVisibleExecutionLog(executionId, triggeredBy);
        return buildRecoveryInfo(executionLog);
    }

    private ExecutionRecoveryInfoVO buildRecoveryInfo(ExecutionLog executionLog) {
        Map<String, Object> failureEvent = latestFailureEvent(executionLog.getNodeLogs());
        Map<String, Object> metadata = metadataOf(failureEvent);
        String failedNodeId = firstString(metadata.get("failedNodeId"), failureEvent.get("nodeId"));
        String errorMessage = firstString(metadata.get("errorMessage"), executionLog.getErrorMessage());
        String checkpointRef = firstString(metadata.get("recoveryCheckpointRef"), executionLog.getCheckpointRef());
        boolean failed = "FAILED".equalsIgnoreCase(executionLog.getStatus());
        boolean recoverable = failed && checkpointRef != null && !checkpointRef.isBlank();
        return ExecutionRecoveryInfoVO.builder()
                .executionId(executionLog.getId())
                .status(executionLog.getStatus())
                .failedNodeId(failedNodeId)
                .errorMessage(errorMessage)
                .checkpointRef(checkpointRef)
                .recoverable(recoverable)
                .recoverySuggestion(recoverySuggestion(failed, recoverable, checkpointRef))
                .build();
    }

    private List<Map<String, Object>> readNodeLogEvents(String nodeLogs) {
        if (nodeLogs == null || nodeLogs.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(nodeLogs, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> latestFailureEvent(String nodeLogs) {
        List<Map<String, Object>> events = readNodeLogEvents(nodeLogs);
        for (int i = events.size() - 1; i >= 0; i--) {
            Map<String, Object> event = events.get(i);
            if ("EXECUTION_FAILED".equals(event.get("type"))) {
                return event;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataOf(Map<String, Object> event) {
        Object metadata = event.get("metadata");
        if (metadata instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String firstString(Object first, Object second) {
        if (first != null && !String.valueOf(first).isBlank()) {
            return String.valueOf(first);
        }
        if (second != null && !String.valueOf(second).isBlank()) {
            return String.valueOf(second);
        }
        return null;
    }

    private String recoverySuggestion(boolean failed, boolean recoverable, String checkpointRef) {
        if (!failed) {
            return "当前执行未失败，无需恢复";
        }
        if (recoverable) {
            return "可从 " + checkpointRef + " 恢复执行";
        }
        return "缺少可用 checkpoint，无法恢复执行";
    }
}
