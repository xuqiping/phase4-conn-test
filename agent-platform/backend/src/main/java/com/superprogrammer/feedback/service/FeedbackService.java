package com.superprogrammer.feedback.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.feedback.dto.CreateSuggestionRequest;
import com.superprogrammer.feedback.dto.SuggestionVO;
import com.superprogrammer.feedback.entity.FeedbackSuggestionEntity;
import com.superprogrammer.feedback.mapper.FeedbackSuggestionMapper;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 建议台·用户侧（19x#1）。
 *
 * <p><b>附件属主</b>：提交时逐 fileId 校验属主=提交人（防挂他人 fileId 越权引用；
 * 读侧门控本就在 FileStorageService.load 做 owner-or-admin，本层补提交侧校验防串号）。
 *
 * <p><b>username 快照</b>：提交时从 users 表抄存——改名不影响历史建议的展示与溯源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    /** 我的建议每页上限。 */
    static final int MINE_MAX_SIZE = 50;

    private final FeedbackSuggestionMapper suggestionMapper;
    private final FileStorageService fileStorageService;
    private final UserMapper userMapper;
    private final FeedbackNotificationService notificationService;

    /** 提交建议：附件属主校验 → username 快照 → PENDING 落库。返回新单 id。 */
    public Long submitSuggestion(Long userId, CreateSuggestionRequest req) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        validateAttachments(userId, req.attachmentFileIds());
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号状态异常");
        }
        FeedbackSuggestionEntity e = new FeedbackSuggestionEntity();
        e.setUserId(userId);
        e.setUsername(user.getUsername());
        e.setTitle(req.title().trim());
        e.setContent(req.content());
        e.setAttachmentFileIds(req.attachmentFileIds() == null || req.attachmentFileIds().isEmpty()
                ? null : toJsonArray(req.attachmentFileIds()));
        e.setStatus(FeedbackSuggestionEntity.STATUS_PENDING);
        suggestionMapper.insert(e);
        log.info("建议提交: id={} userId={} title={}", e.getId(), userId, abbrev(e.getTitle()));
        return e.getId();
    }

    /** 我的建议分页（service 层强制 self——不接外部 userId 旁路）。 */
    public PageResult<SuggestionVO> mySuggestions(Long userId, int page, int size) {
        int capped = Math.min(Math.max(size, 1), MINE_MAX_SIZE);
        Page<FeedbackSuggestionEntity> p = suggestionMapper.selectPage(
                new Page<>(Math.max(page, 1), capped),
                Wrappers.<FeedbackSuggestionEntity>lambdaQuery()
                        .eq(FeedbackSuggestionEntity::getUserId, userId)
                        .orderByDesc(FeedbackSuggestionEntity::getId));
        return PageResult.of(p.getRecords().stream().map(FeedbackService::toVo).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    // ==================== admin（19x#1 审核台） ====================

    /** admin 建议分页（按状态筛，可空=全部；带 username 快照+附件）。 */
    public PageResult<com.superprogrammer.feedback.dto.AdminSuggestionVO> adminSuggestions(
            String status, int page, int size) {
        int capped = Math.min(Math.max(size, 1), MINE_MAX_SIZE);
        Page<FeedbackSuggestionEntity> p = suggestionMapper.selectPage(
                new Page<>(Math.max(page, 1), capped),
                Wrappers.<FeedbackSuggestionEntity>lambdaQuery()
                        .eq(status != null && !status.isBlank(), FeedbackSuggestionEntity::getStatus, status)
                        .orderByDesc(FeedbackSuggestionEntity::getId));
        return PageResult.of(p.getRecords().stream().map(FeedbackService::toAdminVo).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * 审核（条件 UPDATE 抢态，同 payment 先例——并发双击只一人成功，0 行=409）：
     * <ul>
     *   <li>PENDING → ADOPTED/REJECTED/CLOSED</li>
     *   <li>ADOPTED ↔ REJECTED 改判（重发通知，拍板联动表）</li>
     *   <li>CLOSED 终态：不可达也不可改（from 集合不含 CLOSED）</li>
     * </ul>
     * 抢态成功 → 同事务发 SUGGESTION_REVIEWED 通知（抢态天然保证同一次审核只发一次）。
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void reviewSuggestion(Long id, String toStatus, String reply, Long reviewerId) {
        FeedbackSuggestionEntity cur = suggestionMapper.selectById(id);
        if (cur == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "建议不存在");
        }
        if (FeedbackSuggestionEntity.STATUS_CLOSED.equals(cur.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "该建议已关闭（终态），不可再审核");
        }
        List<String> from = switch (toStatus) {
            case FeedbackSuggestionEntity.STATUS_ADOPTED ->
                    List.of(FeedbackSuggestionEntity.STATUS_PENDING, FeedbackSuggestionEntity.STATUS_REJECTED);
            case FeedbackSuggestionEntity.STATUS_REJECTED ->
                    List.of(FeedbackSuggestionEntity.STATUS_PENDING, FeedbackSuggestionEntity.STATUS_ADOPTED);
            case FeedbackSuggestionEntity.STATUS_CLOSED ->
                    List.of(FeedbackSuggestionEntity.STATUS_PENDING, FeedbackSuggestionEntity.STATUS_ADOPTED,
                            FeedbackSuggestionEntity.STATUS_REJECTED);
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "结论仅支持 ADOPTED/REJECTED/CLOSED");
        };
        if (suggestionMapper.reviewIfStatusIn(id, toStatus, reply, reviewerId, from) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "该建议已被其他管理员处理，请刷新");
        }
        String conclusion = switch (toStatus) {
            case FeedbackSuggestionEntity.STATUS_ADOPTED -> "已采纳";
            case FeedbackSuggestionEntity.STATUS_REJECTED -> "未采纳";
            default -> "已关闭";
        };
        notificationService.notify(cur.getUserId(),
                com.superprogrammer.feedback.entity.FeedbackNotificationEntity.TYPE_SUGGESTION_REVIEWED,
                id, "您的建议「" + abbrev(cur.getTitle()) + "」" + conclusion
                        + (reply != null && !reply.isBlank() ? "：" + abbrev(reply) : ""));
        log.info("建议审核: id={} {}→{} reviewer={}", id, cur.getStatus(), toStatus, reviewerId);
    }

    static com.superprogrammer.feedback.dto.AdminSuggestionVO toAdminVo(FeedbackSuggestionEntity e) {
        return new com.superprogrammer.feedback.dto.AdminSuggestionVO(
                e.getId(), e.getCreatedAt(), e.getUserId(), e.getUsername(), e.getTitle(), e.getContent(),
                parseJsonArray(e.getAttachmentFileIds()), e.getStatus(), e.getReply(), e.getReviewedAt());
    }

    // ==================== 内部 ====================

    /** 逐 fileId 校验存在且属主=提交人；任一不符 400（不泄露他人文件存在性——统一「附件无效」）。 */
    private void validateAttachments(Long userId, List<String> fileIds) {
        if (fileIds == null) {
            return;
        }
        for (String fileId : fileIds) {
            StoredFileEntity meta = fileId == null ? null : fileStorageService.findMeta(fileId);
            if (meta == null || !userId.equals(meta.getOwnerUserId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "附件无效或不属于当前账号");
            }
        }
    }

    static SuggestionVO toVo(FeedbackSuggestionEntity e) {
        return new SuggestionVO(e.getId(), e.getCreatedAt(), e.getTitle(), e.getContent(),
                parseJsonArray(e.getAttachmentFileIds()), e.getStatus(), e.getReply(), e.getReviewedAt());
    }

    /** 简易 JSON 数组序列化（fileId 为 UUID+ext，无引号/反斜杠，安全拼接）。 */
    static String toJsonArray(List<String> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(ids.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    /** 解析 "[a,b]" 为 List；null/非法 → 空列表（容错展示）。 */
    static List<String> parseJsonArray(String json) {
        if (json == null || json.length() < 2 || !json.startsWith("[") || !json.endsWith("]")) {
            return List.of();
        }
        String body = json.substring(1, json.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(body.split(","))
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String abbrev(String s) {
        return s == null ? "" : (s.length() <= 20 ? s : s.substring(0, 20) + "…");
    }
}
