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
