package com.superprogrammer.asset.service;

import com.superprogrammer.asset.dto.PublicAccessRequestVO;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetPublicAccessRequest;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetPublicAccessRequestMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetPublicAccessServiceTest {

    @Mock private AssetProjectMapper projectMapper;
    @Mock private AssetPublicAccessRequestMapper requestMapper;
    @Mock private AssetAclService aclService;

    private AssetPublicAccessService service;

    @BeforeEach
    void setUp() {
        service = new AssetPublicAccessService(projectMapper, requestMapper, aclService);
    }

    @Test
    void requestApproval_createsPendingRecord() {
        when(projectMapper.selectById(1L)).thenReturn(approvalProject());
        AssetPublicAccessRequest inserted = request(7L, 20L, AssetPublicAccessRequest.STATUS_PENDING);
        when(requestMapper.selectForUpdate(1L, 20L)).thenReturn(null, inserted);
        when(requestMapper.insertPendingIfAbsent(1L, 20L)).thenReturn(1);

        PublicAccessRequestVO result = service.request(1L, 20L);

        assertEquals(7L, result.getId());
        assertEquals(AssetPublicAccessRequest.STATUS_PENDING, result.getStatus());
        verify(requestMapper).insertPendingIfAbsent(1L, 20L);
    }

    @Test
    void rejectedRequest_canResetToPending() {
        when(projectMapper.selectById(1L)).thenReturn(approvalProject());
        AssetPublicAccessRequest existing = request(7L, 20L, AssetPublicAccessRequest.STATUS_REJECTED);
        existing.setDecidedBy(10L);
        existing.setDecidedAt(java.time.OffsetDateTime.now());
        when(requestMapper.selectForUpdate(1L, 20L)).thenReturn(existing);
        when(requestMapper.resetToPending(7L, 20L)).thenReturn(1);

        PublicAccessRequestVO result = service.request(1L, 20L);

        assertEquals(AssetPublicAccessRequest.STATUS_PENDING, result.getStatus());
        assertNull(existing.getDecidedBy());
        assertNull(existing.getDecidedAt());
        verify(requestMapper).resetToPending(7L, 20L);
    }

    @Test
    void pendingOrApprovedRequest_isIdempotent() {
        when(projectMapper.selectById(1L)).thenReturn(approvalProject());
        AssetPublicAccessRequest existing = request(7L, 20L, AssetPublicAccessRequest.STATUS_APPROVED);
        when(requestMapper.selectForUpdate(1L, 20L)).thenReturn(existing);

        assertEquals(AssetPublicAccessRequest.STATUS_APPROVED, service.request(1L, 20L).getStatus());
        verify(requestMapper, never()).updateById(any());
        verify(requestMapper, never()).insert(any());
    }

    @Test
    void decidePending_usesConditionalUpdate() {
        when(projectMapper.selectById(1L)).thenReturn(approvalProject());
        when(aclService.requireManage(1L, 10L, false)).thenReturn(com.superprogrammer.asset.enums.AssetRole.OWNER);
        when(requestMapper.selectById(7L)).thenReturn(request(7L, 20L, AssetPublicAccessRequest.STATUS_PENDING));
        when(requestMapper.decidePending(7L, AssetPublicAccessRequest.STATUS_APPROVED, 10L)).thenReturn(1);

        service.decide(1L, 7L, 10L, false, AssetPublicAccessRequest.STATUS_APPROVED);

        verify(requestMapper).decidePending(7L, AssetPublicAccessRequest.STATUS_APPROVED, 10L);
    }

    @Test
    void concurrentSecondDecision_returnsConflict() {
        when(projectMapper.selectById(1L)).thenReturn(approvalProject());
        when(aclService.requireManage(1L, 10L, false)).thenReturn(com.superprogrammer.asset.enums.AssetRole.OWNER);
        when(requestMapper.selectById(7L)).thenReturn(request(7L, 20L, AssetPublicAccessRequest.STATUS_PENDING));
        when(requestMapper.decidePending(7L, AssetPublicAccessRequest.STATUS_REJECTED, 10L)).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.decide(1L, 7L, 10L, false, AssetPublicAccessRequest.STATUS_REJECTED));

        assertEquals(ErrorCode.CONFLICT.getCode(), error.getCode());
    }

    @Test
    void requestOnOpenProject_isRejected() {
        AssetProject project = approvalProject();
        project.setPublicAccessMode(AssetProject.PUBLIC_ACCESS_OPEN);
        when(projectMapper.selectById(1L)).thenReturn(project);

        BusinessException error = assertThrows(BusinessException.class, () -> service.request(1L, 20L));

        assertEquals(ErrorCode.UNPROCESSABLE.getCode(), error.getCode());
    }

    private AssetProject approvalProject() {
        AssetProject project = new AssetProject();
        project.setId(1L);
        project.setOwnerId(10L);
        project.setPublicPool(true);
        project.setPublicAccessMode(AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED);
        return project;
    }

    private AssetPublicAccessRequest request(Long id, Long applicantId, String status) {
        AssetPublicAccessRequest request = new AssetPublicAccessRequest();
        request.setId(id);
        request.setProjectId(1L);
        request.setApplicantId(applicantId);
        request.setStatus(status);
        return request;
    }
}
