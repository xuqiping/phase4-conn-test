package com.superprogrammer.execution.service;

import com.superprogrammer.execution.entity.ExecutionLog;
import com.superprogrammer.execution.mapper.ExecutionLogMapper;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionLogServiceTest {

    @Mock
    private ExecutionLogMapper executionLogMapper;

    @Test
    void startRuntimeExecution_createsRootExecutionWithTraceAndSource() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        when(executionLogMapper.insert(any(ExecutionLog.class))).thenAnswer(invocation -> {
            ExecutionLog log = invocation.getArgument(0);
            log.setId(100L);
            return 1;
        });

        ExecutionLog result = executionLogService.startRuntimeExecution(
                10L, "组合流程", 7L, "WORKFLOW", 10L, null, null, null, "trace-1");

        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getRootExecutionId()).isEqualTo(100L);
        assertThat(result.getParentExecutionId()).isNull();
        assertThat(result.getSourceType()).isEqualTo("WORKFLOW");
        assertThat(result.getSourceId()).isEqualTo(10L);
        assertThat(result.getTraceId()).isEqualTo("trace-1");
        assertThat(result.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void startRuntimeExecution_createsChildExecutionWithRootAndParent() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        when(executionLogMapper.insert(any(ExecutionLog.class))).thenAnswer(invocation -> {
            ExecutionLog log = invocation.getArgument(0);
            log.setId(101L);
            return 1;
        });

        ExecutionLog result = executionLogService.startRuntimeExecution(
                10L, "子 Agent", 7L, "AGENT", 3L, null, 100L, 100L, "trace-1");

        assertThat(result.getId()).isEqualTo(101L);
        assertThat(result.getRootExecutionId()).isEqualTo(100L);
        assertThat(result.getParentExecutionId()).isEqualTo(100L);
        assertThat(result.getSourceType()).isEqualTo("AGENT");
        assertThat(result.getSourceId()).isEqualTo(3L);
    }

    @Test
    void updateRuntimeRefs_persistsExternalThreadAndCheckpoint() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(100L);
        when(executionLogMapper.selectById(100L)).thenReturn(existing);

        executionLogService.updateRuntimeRefs(100L, "thread-1", "checkpoint-1");

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(executionLogMapper).updateById(captor.capture());
        assertThat(captor.getValue().getExternalThreadId()).isEqualTo("thread-1");
        assertThat(captor.getValue().getCheckpointRef()).isEqualTo("checkpoint-1");
    }

    @Test
    void updateRuntimeRefs_preservesExistingValuesWhenPartialUpdateArrives() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(100L);
        existing.setExternalThreadId("thread-1");
        existing.setCheckpointRef("checkpoint-1");
        when(executionLogMapper.selectById(100L)).thenReturn(existing);

        executionLogService.updateRuntimeRefs(100L, "thread-2", null);

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(executionLogMapper).updateById(captor.capture());
        assertThat(captor.getValue().getExternalThreadId()).isEqualTo("thread-2");
        assertThat(captor.getValue().getCheckpointRef()).isEqualTo("checkpoint-1");
    }

    @Test
    void updateRuntimeRefs_canPersistRecoveryCheckpointWithoutChangingThread() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(100L);
        existing.setExternalThreadId("thread-1");
        when(executionLogMapper.selectById(100L)).thenReturn(existing);

        executionLogService.updateRuntimeRefs(100L, null, "checkpoint-recovery");

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(executionLogMapper).updateById(captor.capture());
        assertThat(captor.getValue().getExternalThreadId()).isEqualTo("thread-1");
        assertThat(captor.getValue().getCheckpointRef()).isEqualTo("checkpoint-recovery");
    }

    @Test
    void appendRuntimeEventSnapshot_storesEventJsonInNodeLogs() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(100L);
        existing.setNodeLogs("[{\"type\":\"EXECUTION_STARTED\"}]");
        when(executionLogMapper.selectById(100L)).thenReturn(existing);

        executionLogService.appendRuntimeEventSnapshot(100L, Map.of("type", "NODE_STARTED", "nodeId", "agent-1"));

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(executionLogMapper).updateById(captor.capture());
        assertThat(captor.getValue().getNodeLogs()).contains("EXECUTION_STARTED", "NODE_STARTED", "agent-1");
    }

    @Test
    void finishExecution_preservesExistingRuntimeEventArrayWhenNoReplacementLogsProvided() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(100L);
        existing.setStartedAt(java.time.OffsetDateTime.now().minusSeconds(1));
        existing.setNodeLogs("[{\"type\":\"EXECUTION_STARTED\"},{\"type\":\"EXECUTION_COMPLETED\"}]");
        when(executionLogMapper.selectById(100L)).thenReturn(existing);

        executionLogService.finishExecution(100L, null);

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(executionLogMapper).updateById(captor.capture());
        assertThat(captor.getValue().getNodeLogs()).contains("EXECUTION_STARTED", "EXECUTION_COMPLETED");
    }

    @Test
    void findByCheckpointRef_returnsExecutionWithMatchingCheckpoint() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(100L);
        existing.setCheckpointRef("checkpoint-100");
        when(executionLogMapper.selectList(any())).thenReturn(List.of(existing));

        ExecutionLog result = executionLogService.findByCheckpointRef("checkpoint-100");

        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getCheckpointRef()).isEqualTo("checkpoint-100");
    }

    @Test
    void listPendingApprovals_returnsWaitingApprovalExecutions() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(100L);
        existing.setStatus("WAITING_APPROVAL");
        existing.setNodeId("approval-1");
        existing.setCheckpointRef("checkpoint-100");
        when(executionLogMapper.selectList(any())).thenReturn(List.of(existing));

        List<ExecutionLog> result = executionLogService.listPendingApprovals();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("WAITING_APPROVAL");
        assertThat(result.get(0).getNodeId()).isEqualTo("approval-1");
    }

    @Test
    void listVisibleExecutions_withoutUserScopeReturnsAllExecutions() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog first = new ExecutionLog();
        first.setId(100L);
        first.setTriggeredBy(1L);
        ExecutionLog second = new ExecutionLog();
        second.setId(101L);
        second.setTriggeredBy(2L);
        when(executionLogMapper.selectList(any())).thenReturn(List.of(first, second));

        List<ExecutionLog> result = executionLogService.listVisibleExecutions(null);

        assertThat(result).extracting(ExecutionLog::getId).containsExactly(100L, 101L);
    }

    @Test
    void listVisibleExecutions_withUserScopeReturnsUserExecutions() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(100L);
        existing.setTriggeredBy(7L);
        when(executionLogMapper.selectList(any())).thenReturn(List.of(existing));

        List<ExecutionLog> result = executionLogService.listVisibleExecutions(7L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTriggeredBy()).isEqualTo(7L);
    }

    @Test
    void getVisibleExecutionLog_rejectsOtherUsersExecution() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(100L);
        existing.setTriggeredBy(7L);
        when(executionLogMapper.selectById(100L)).thenReturn(existing);

        assertThatThrownBy(() -> executionLogService.getVisibleExecutionLog(100L, 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权查看该执行任务");
    }

    @Test
    void getRecoveryInfo_extractsFailureMetadataFromRuntimeEvents() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(100L);
        existing.setStatus("FAILED");
        existing.setCheckpointRef("checkpoint-100");
        existing.setErrorMessage("节点 agent-1 执行失败: forced failure");
        existing.setNodeLogs("""
                [
                  {
                    "type": "EXECUTION_FAILED",
                    "status": "FAILED",
                    "nodeId": "agent-1",
                    "metadata": {
                      "failedNodeId": "agent-1",
                      "errorMessage": "forced failure",
                      "recoveryCheckpointRef": "checkpoint-100"
                    }
                  }
                ]
                """);
        when(executionLogMapper.selectById(100L)).thenReturn(existing);

        var result = executionLogService.getRecoveryInfo(100L);

        assertThat(result.getExecutionId()).isEqualTo(100L);
        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getFailedNodeId()).isEqualTo("agent-1");
        assertThat(result.getErrorMessage()).isEqualTo("forced failure");
        assertThat(result.getCheckpointRef()).isEqualTo("checkpoint-100");
        assertThat(result.isRecoverable()).isTrue();
        assertThat(result.getRecoverySuggestion()).isEqualTo("可从 checkpoint-100 恢复执行");
    }

    @Test
    void getRecoveryInfo_marksFailedExecutionWithoutCheckpointAsNotRecoverable() {
        ExecutionLogService executionLogService = new ExecutionLogService(executionLogMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        ExecutionLog existing = new ExecutionLog();
        existing.setId(101L);
        existing.setStatus("FAILED");
        existing.setErrorMessage("network failure");
        existing.setNodeLogs("""
                [
                  {
                    "type": "EXECUTION_FAILED",
                    "nodeId": "llm-1",
                    "metadata": {
                      "failedNodeId": "llm-1",
                      "errorMessage": "network failure"
                    }
                  }
                ]
                """);
        when(executionLogMapper.selectById(101L)).thenReturn(existing);

        var result = executionLogService.getRecoveryInfo(101L);

        assertThat(result.getFailedNodeId()).isEqualTo("llm-1");
        assertThat(result.getErrorMessage()).isEqualTo("network failure");
        assertThat(result.getCheckpointRef()).isNull();
        assertThat(result.isRecoverable()).isFalse();
        assertThat(result.getRecoverySuggestion()).isEqualTo("缺少可用 checkpoint，无法恢复执行");
    }
}
