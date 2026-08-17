package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 资产评分服务（2x第三轮C6，百分制双轨）。
 *
 * <p>权限矩阵：
 * <ul>
 *   <li>OWNER（含 admin 旁路）→ 可评，落 is_owner_score=TRUE（独立轨）</li>
 *   <li>EDITOR → 项目开关 memberScoringEnabled=TRUE 才可评（参与均分轨）</li>
 *   <li>公共池 VIEWER / 未授权 → 403（loadAccessible 通过后 canWrite 拒）</li>
 * </ul>
 *
 * <p>upsert（ON CONFLICT 每人每资产一票）：改分覆盖旧分；软删行复活（撤销评分后重评场景）。
 * 被移除成员的历史评分保留参与均分（决策 D4：评分属资产维度数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetScoreService {

    private final AssetMapper assetMapper;
    private final AssetProjectMapper projectMapper;
    private final AssetScoreMapper scoreMapper;
    private final AssetAclService aclService;

    /** 提交/修改评分（upsert 覆盖）。返最新双轨聚合 + 我的分。 */
    @Transactional
    public AssetScoreVO submit(Long assetId, Long userId, boolean admin, Integer score) {
        Asset asset = loadAsset(assetId);
        AssetProject project = projectMapper.selectById(asset.getProjectId());
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        AssetRole role = aclService.loadAccessible(asset.getProjectId(), userId, admin);
        // 公共池 VIEWER / 未授权不可评（未授权在 loadAccessible 已 403）
        if (!role.canWrite()) {
            log.warn("asset score denied: assetId={} userId={} role={}", assetId, userId, role);
            throw new BusinessException(ErrorCode.FORBIDDEN, "评分需项目成员权限");
        }
        boolean isOwner = role == AssetRole.OWNER;
        // EDITOR 受项目开关（OWNER 恒可评）
        if (!isOwner && !Boolean.TRUE.equals(project.getMemberScoringEnabled())) {
            log.warn("asset score denied (switch off): assetId={} userId={}", assetId, userId);
            throw new BusinessException(ErrorCode.FORBIDDEN, "项目未开放成员打分");
        }
        if (score == null || score < 0 || score > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评分须为 0-100 的整数");
        }
        scoreMapper.upsertScore(assetId, asset.getProjectId(), userId, score, isOwner);
        log.info("asset scored: assetId={} userId={} score={} isOwner={}", assetId, userId, score, isOwner);
        return getScore(assetId, userId, admin);
    }

    /** 我的评分 + 双轨聚合（读：成员/公共 VIEWER 均可看）。 */
    public AssetScoreVO getScore(Long assetId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.loadAccessible(asset.getProjectId(), userId, admin);
        List<AssetScore> rows = scoreMapper.selectList(new LambdaQueryWrapper<AssetScore>()
                .eq(AssetScore::getAssetId, assetId));
        Integer ownerScore = null;
        Integer myScore = null;
        long memberSum = 0;
        int memberCount = 0;
        for (AssetScore r : rows) {
            if (Boolean.TRUE.equals(r.getIsOwnerScore())) {
                ownerScore = r.getScore();
            } else {
                memberSum += r.getScore();
                memberCount++;
            }
            if (userId != null && userId.equals(r.getScorerUserId())) {
                myScore = r.getScore();
            }
        }
        // 2x#7：均分先取整（展示口径一致），等级现场派生不入库
        Integer memberAvgScore = memberCount == 0 ? null : (int) Math.round((double) memberSum / memberCount);
        return AssetScoreVO.builder()
                .myScore(myScore)
                .ownerScore(ownerScore)
                .memberAvgScore(memberAvgScore)
                .memberCount(memberCount)
                .ownerGrade(AssetGrade.fromScore(ownerScore))
                .memberAvgGrade(AssetGrade.fromScore(memberAvgScore))
                .build();
    }

    private Asset loadAsset(Long assetId) {
        Asset a = assetMapper.selectById(assetId);
        if (a == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        return a;
    }
}
