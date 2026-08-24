package com.superprogrammer.feedback.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.feedback.dto.ArticleDetailVO;
import com.superprogrammer.feedback.dto.ArticleListItemVO;
import com.superprogrammer.feedback.dto.UpsertArticleRequest;
import com.superprogrammer.feedback.entity.HelpArticleEntity;
import com.superprogrammer.feedback.mapper.HelpArticleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 说明台·帮助文章（19x#3）。
 *
 * <p><b>slug</b>：唯一短链；拍板<strong>硬删</strong>（{@code physicalDeleteById}）——
 * 删后同 slug 可立即重建（软删会占坑 409）。更新不允许改 slug（防外链失锚）。
 *
 * <p><b>发布闸</b>：用户侧目录/正文仅 published=true；未发布直链 404（不泄露存在性）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HelpArticleService {

    /** admin 列表每页上限。 */
    static final int LIST_MAX_SIZE = 100;

    private final HelpArticleMapper articleMapper;

    // ==================== admin ====================

    public PageResult<HelpArticleEntity> adminList(int page, int size) {
        int capped = Math.min(Math.max(size, 1), LIST_MAX_SIZE);
        Page<HelpArticleEntity> p = articleMapper.selectPage(
                new Page<>(Math.max(page, 1), capped),
                Wrappers.<HelpArticleEntity>lambdaQuery()
                        .orderByAsc(HelpArticleEntity::getCategory)
                        .orderByAsc(HelpArticleEntity::getSortOrder)
                        .orderByDesc(HelpArticleEntity::getId));
        return PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 新建：slug 冲突 409（uk 索引兜底前的友好路径 + 竞态 DuplicateKey 双保险）。 */
    public Long create(UpsertArticleRequest req) {
        if (articleMapper.selectBySlug(req.slug()) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "slug 已存在：" + req.slug());
        }
        HelpArticleEntity e = new HelpArticleEntity();
        e.setSlug(req.slug());
        e.setTitle(req.title());
        e.setCategory(req.category() == null || req.category().isBlank() ? "通用" : req.category().trim());
        e.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        e.setRequiredPermission(normalizePerm(req.requiredPermission()));
        e.setContentMd(req.contentMd());
        e.setPublished(false);
        try {
            articleMapper.insert(e);
        } catch (DuplicateKeyException dup) {
            throw new BusinessException(ErrorCode.CONFLICT, "slug 已存在：" + req.slug());
        }
        log.info("帮助文章新建: id={} slug={}", e.getId(), e.getSlug());
        return e.getId();
    }

    /** 更新：slug 不可改（身份字段）；其余字段覆盖。 */
    public void update(Long id, UpsertArticleRequest req) {
        HelpArticleEntity e = articleMapper.selectById(id);
        if (e == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文章不存在");
        }
        e.setTitle(req.title());
        e.setCategory(req.category() == null || req.category().isBlank() ? "通用" : req.category().trim());
        e.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        e.setRequiredPermission(normalizePerm(req.requiredPermission()));
        e.setContentMd(req.contentMd());
        articleMapper.updateById(e);
        log.info("帮助文章更新: id={} slug={}", id, e.getSlug());
    }

    /** 发布/下架。 */
    public void setPublished(Long id, boolean published) {
        if (articleMapper.setPublished(id, published) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文章不存在");
        }
        log.info("帮助文章{}: id={}", published ? "发布" : "下架", id);
    }

    /** 硬删（拍板：释放 slug 占坑；前端二次确认）。 */
    public void delete(Long id) {
        HelpArticleEntity e = articleMapper.selectById(id);
        if (e == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文章不存在");
        }
        articleMapper.physicalDeleteById(id);
        log.info("帮助文章硬删: id={} slug={}", id, e.getSlug());
    }

    // ==================== 用户侧 ====================

    /**
     * 已发布目录（可选分类筛；仅目录字段，无 content_md）。
     * V149：按当前用户权限过滤——用户只能看到自己持有权限模块的文章
     * （requiredPermission 为 NULL=全员；否则须持有该码或 ROLE_admin）。
     */
    public List<ArticleListItemVO> listPublished(String category) {
        Set<String> authorities = currentAuthorities();
        return articleMapper.selectList(Wrappers.<HelpArticleEntity>lambdaQuery()
                        .select(HelpArticleEntity::getSlug, HelpArticleEntity::getTitle,
                                HelpArticleEntity::getCategory, HelpArticleEntity::getSortOrder,
                                HelpArticleEntity::getPublishedAt,
                                HelpArticleEntity::getRequiredPermission)
                        .eq(HelpArticleEntity::getPublished, true)
                        .eq(category != null && !category.isBlank(), HelpArticleEntity::getCategory, category)
                        .orderByAsc(HelpArticleEntity::getCategory)
                        .orderByAsc(HelpArticleEntity::getSortOrder)
                        .orderByAsc(HelpArticleEntity::getId))
                .stream()
                .filter(e -> visibleTo(e.getRequiredPermission(), authorities))
                .map(e -> new ArticleListItemVO(e.getSlug(), e.getTitle(), e.getCategory(),
                        e.getSortOrder(), e.getPublishedAt()))
                .toList();
    }

    /** 正文（slug 直达；未发布/不存在/无权限 → 404 不泄露）。 */
    public ArticleDetailVO getPublishedBySlug(String slug) {
        HelpArticleEntity e = articleMapper.selectBySlug(slug);
        if (e == null || !Boolean.TRUE.equals(e.getPublished())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文章不存在或未发布");
        }
        if (!visibleTo(e.getRequiredPermission(), currentAuthorities())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文章不存在或未发布");
        }
        return new ArticleDetailVO(e.getSlug(), e.getTitle(), e.getCategory(),
                e.getContentMd(), e.getPublishedAt());
    }

    // ==================== V149 权限门控 ====================

    /** 空串归一为 null（NULL=全员可见）。 */
    private static String normalizePerm(String perm) {
        return perm == null || perm.isBlank() ? null : perm.trim();
    }

    /** 当前用户 authorities（权限码 + ROLE_ 角色，与 JwtAuthenticationFilter 同源）。 */
    private static Set<String> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    /** 文章对该 authorities 是否可见：NULL=全员；admin 全见；否则须持有该码。 */
    private static boolean visibleTo(String requiredPermission, Set<String> authorities) {
        if (requiredPermission == null || requiredPermission.isBlank()) {
            return true;
        }
        return authorities.contains("ROLE_admin") || authorities.contains(requiredPermission);
    }
}
