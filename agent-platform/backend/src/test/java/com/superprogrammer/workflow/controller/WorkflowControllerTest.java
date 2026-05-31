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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(com.superprogrammer.common.config.TestSecurityConfig.class)
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowService workflowService;

    @MockBean(name = "jwtUtil")
    private JwtUtil jwtUtil;

    @Test
    void listWorkflows_returnsWorkflowList() throws Exception {
        WorkflowVO workflowVO = WorkflowVO.builder()
                .id(1L)
                .name("测试工作流")
                .status("DRAFT")
                .ownerId(1L)
                .createdAt(OffsetDateTime.now())
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
