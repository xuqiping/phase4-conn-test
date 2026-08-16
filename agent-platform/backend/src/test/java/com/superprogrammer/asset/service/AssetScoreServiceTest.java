package com.superprogrammer.asset.service;

import com.superprogrammer.asset.dto.AssetScoreVO;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetScore;
import com.superprogrammer.asset.enums.AssetRole;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetScoreMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetScoreService 单测（2x第三轮C6）：双轨评分权限矩阵 + upsert + 聚合。
 * 覆盖 plan C6 验证：开关 FALSE 403 / TRUE 可评、OWNER 恒可评（独立轨）、
 * 公共池 VIEWER / 未授权 403、均分 3 人 80/90/100→90（D4 含被移除成员）、双轨不污染、非法分 400。
 */
@ExtendWith(MockitoExtension.class)
class AssetScoreServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long ASSET_ID = 77L;
    private static final Long OWNER_ID = 10L;
    private static final Long EDITOR_ID = 20L;
    private static final Long VIEWER_ID = 30L;
    private static final Long OUTSIDER_ID = 40L;

    @Mock private AssetMapper assetMapper;
    @Mock private AssetProjectMapper projectMapper;
    @Mock private AssetScoreMapper scoreMapper;
    @Mock private AssetAclService aclService;

    private AssetScoreService service;

    @BeforeEach
    void setUp() {
        service = new AssetScoreService(assetMapper, projectMapper, scoreMapper, aclService);
    }

    // ---------- 提交评分·权限矩阵 ----------

    @Test
    void editor_switchOff_forbidden() {
        stubAssetAndProject(false);
        when(aclService.loadAccessible(PROJECT_ID, EDITOR_ID, false)).thenReturn(AssetRole.EDITOR);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(ASSET_ID, EDITOR_ID, false, 80));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(scoreMapper, never()).upsertScore(any(), any(), any(), any(), any());
    }

    @Test
    void editor_switchOn_scoresMemberTrack() {
        stubAssetAndProject(true);
        when(aclService.loadAccessible(PROJECT_ID, EDITOR_ID, false)).thenReturn(AssetRole.EDITOR);
        when(scoreMapper.selectList(any())).thenReturn(List.of(score(EDITOR_ID, 80, false)));
        AssetScoreVO vo = service.submit(ASSET_ID, EDITOR_ID, false, 80);
        // 成员轨：is_owner_score=FALSE
        verify(scoreMapper).upsertScore(ASSET_ID, PROJECT_ID, EDITOR_ID, 80, false);
        assertEquals(80, vo.getMyScore());
        assertEquals(80, vo.getMemberAvgScore());
        assertEquals(1, vo.getMemberCount());
    }

    @Test
    void owner_switchStillOff_alwaysCanScore_ownerTrack() {
        stubAssetAndProject(false);
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        when(scoreMapper.selectList(any())).thenReturn(List.of(score(OWNER_ID, 88, true)));
        AssetScoreVO vo = service.submit(ASSET_ID, OWNER_ID, false, 88);
        // OWNER 恒可评（开关只约束成员），落独立轨 is_owner_score=TRUE
        verify(scoreMapper).upsertScore(ASSET_ID, PROJECT_ID, OWNER_ID, 88, true);
        assertEquals(88, vo.getOwnerScore());
        assertEquals(0, vo.getMemberCount());
    }

    @Test
    void publicViewer_forbidden() {
        stubAssetAndProject(true);
        when(aclService.loadAccessible(PROJECT_ID, VIEWER_ID, false)).thenReturn(AssetRole.VIEWER);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(ASSET_ID, VIEWER_ID, false, 60));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(scoreMapper, never()).upsertScore(any(), any(), any(), any(), any());
    }

    @Test
    void outsider_deniedByLoadAccessible() {
        // 未授权用户在 loadAccessible 即 403（无权访问该项目），不进评分逻辑
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(false));
        when(aclService.loadAccessible(PROJECT_ID, OUTSIDER_ID, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权访问该项目"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(ASSET_ID, OUTSIDER_ID, false, 60));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    // ---------- 提交评分·校验 ----------

    @Test
    void invalidScore_rejected400() {
        stubAssetAndProject(true);
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        // Arrays.asList 容忍 null 元素（List.of 拒绝）——null 本身就是要测的非法输入
        for (Integer bad : java.util.Arrays.asList(null, -1, 101, 150)) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.submit(ASSET_ID, OWNER_ID, false, bad));
            assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        }
        verify(scoreMapper, never()).upsertScore(any(), any(), any(), any(), any());
    }

    @Test
    void resubmit_overwritesOldScore_viaUpsert() {
        // upsert 语义：二次提交同 (assetId,userId) 覆盖旧分，不产生第二行（mapper 层 ON CONFLICT）
        stubAssetAndProject(true);
        when(aclService.loadAccessible(PROJECT_ID, EDITOR_ID, false)).thenReturn(AssetRole.EDITOR);
        when(scoreMapper.selectList(any())).thenReturn(List.of(score(EDITOR_ID, 95, false)));
        AssetScoreVO vo = service.submit(ASSET_ID, EDITOR_ID, false, 95);
        verify(scoreMapper).upsertScore(ASSET_ID, PROJECT_ID, EDITOR_ID, 95, false);
        assertEquals(95, vo.getMyScore());
    }

    // ---------- 聚合（双轨 + D4） ----------

    @Test
    void aggregate_threeMembersAvg90_ownerSeparate() {
        // 3 成员 80/90/100 → memberAvg=90；OWNER 88 独立轨不混入均分（双轨不污染）
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset());
        when(aclService.loadAccessible(PROJECT_ID, VIEWER_ID, false)).thenReturn(AssetRole.VIEWER);
        when(scoreMapper.selectList(any())).thenReturn(List.of(
                score(10L, 88, true),
                score(EDITOR_ID, 80, false),
                score(31L, 90, false),
                score(32L, 100, false)));
        AssetScoreVO vo = service.getScore(ASSET_ID, VIEWER_ID, false);
        assertEquals(88, vo.getOwnerScore());
        assertEquals(90, vo.getMemberAvgScore());
        assertEquals(3, vo.getMemberCount());
        assertNull(vo.getMyScore()); // VIEWER 未评分
    }

    @Test
    void aggregate_removedMemberStillCounts_D4() {
        // D4：被移除成员（31L 已不在成员表——本测试即无成员表参与）的历史评分仍参与均分
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset());
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        when(scoreMapper.selectList(any())).thenReturn(List.of(
                score(OWNER_ID, 60, true),
                score(EDITOR_ID, 80, false),
                score(31L, 90, false)));
        AssetScoreVO vo = service.getScore(ASSET_ID, OWNER_ID, false);
        assertEquals(2, vo.getMemberCount());
        assertEquals(85, vo.getMemberAvgScore());
        assertEquals(60, vo.getMyScore()); // OWNER 自己的分=ownerScore 行
    }

    @Test
    void aggregate_noScores_allNull() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset());
        when(aclService.loadAccessible(PROJECT_ID, VIEWER_ID, false)).thenReturn(AssetRole.VIEWER);
        when(scoreMapper.selectList(any())).thenReturn(List.of());
        AssetScoreVO vo = service.getScore(ASSET_ID, VIEWER_ID, false);
        assertNull(vo.getOwnerScore());
        assertNull(vo.getMemberAvgScore());
        assertEquals(0, vo.getMemberCount());
        assertNull(vo.getMyScore());
    }

    @Test
    void assetMissing_404() {
        when(assetMapper.selectById(99L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(99L, OWNER_ID, false, 50));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    // ---------- stub ----------

    private void stubAssetAndProject(boolean scoringEnabled) {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(scoringEnabled));
    }

    private Asset asset() {
        Asset a = new Asset();
        a.setId(ASSET_ID);
        a.setProjectId(PROJECT_ID);
        return a;
    }

    private AssetProject project(boolean scoringEnabled) {
        AssetProject p = new AssetProject();
        p.setId(PROJECT_ID);
        p.setOwnerId(OWNER_ID);
        p.setMemberScoringEnabled(scoringEnabled);
        return p;
    }

    private AssetScore score(Long scorerId, int value, boolean ownerTrack) {
        AssetScore s = new AssetScore();
        s.setAssetId(ASSET_ID);
        s.setProjectId(PROJECT_ID);
        s.setScorerUserId(scorerId);
        s.setScore(value);
        s.setIsOwnerScore(ownerTrack);
        return s;
    }
}
