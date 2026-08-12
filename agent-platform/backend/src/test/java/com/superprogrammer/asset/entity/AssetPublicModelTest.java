package com.superprogrammer.asset.entity;

import com.superprogrammer.asset.dto.ProjectVO;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AssetPublicModelTest {

    @Test
    void projectAndVoExposePublicPublicationSnapshot() {
        // AC-F19-01：项目与对外 VO 均保存同一份发布快照字段。
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-08-10T00:00:00Z");
        AssetProject project = new AssetProject();
        project.setPublicPool(true);
        project.setPublicAccessMode(AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED);
        project.setPublishedBy(7L);
        project.setPublishedAt(publishedAt);
        project.setPublishedByAdmin(false);

        ProjectVO vo = ProjectVO.builder()
                .publicPool(project.getPublicPool())
                .publicAccessMode(project.getPublicAccessMode())
                .publishedBy(project.getPublishedBy())
                .publishedAt(project.getPublishedAt())
                .publishedByAdmin(project.getPublishedByAdmin())
                .build();

        assertThat(vo.getPublicPool()).isTrue();
        assertThat(vo.getPublicAccessMode()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(vo.getPublishedBy()).isEqualTo(7L);
        assertThat(vo.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(vo.getPublishedByAdmin()).isFalse();
    }

    @Test
    void accessRequestDefinesAuditableLifecycleStates() {
        // AC-F19-02：同一申请实体承载四状态和审批快照。
        AssetPublicAccessRequest request = new AssetPublicAccessRequest();
        request.setProjectId(11L);
        request.setApplicantId(12L);
        request.setStatus(AssetPublicAccessRequest.STATUS_APPROVED);
        request.setDecidedBy(13L);
        request.setDecidedAt(OffsetDateTime.parse("2026-08-10T01:00:00Z"));

        assertThat(AssetPublicAccessRequest.STATUSES).containsExactlyInAnyOrder(
                "PENDING", "APPROVED", "REJECTED", "REVOKED");
        assertThat(request.getProjectId()).isEqualTo(11L);
        assertThat(request.getApplicantId()).isEqualTo(12L);
        assertThat(request.getStatus()).isEqualTo("APPROVED");
        assertThat(request.getDecidedBy()).isEqualTo(13L);
        assertThat(request.getDecidedAt()).isNotNull();
    }
}
