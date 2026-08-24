package com.superprogrammer.feedback.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.feedback.dto.UpsertArticleRequest;
import com.superprogrammer.feedback.entity.HelpArticleEntity;
import com.superprogrammer.feedback.mapper.HelpArticleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 说明台单测（19x#3）：slug 冲突 409/硬删后重建/未发布 404/slug 不可改。 */
@ExtendWith(MockitoExtension.class)
class HelpArticleServiceTest {

    @Mock private HelpArticleMapper articleMapper;

    private HelpArticleService service;

    @BeforeEach
    void setUp() {
        // lambdaQuery 单测无 Spring 容器：手动初始化实体 TableInfo（lambda 列名缓存依赖）
        if (TableInfoHelper.getTableInfo(HelpArticleEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), HelpArticleEntity.class);
        }
        service = new HelpArticleService(articleMapper);
    }

    private UpsertArticleRequest req(String slug) {
        return new UpsertArticleRequest(slug, "标题", "分类", 1, null, "正文");
    }

    @Test
    void 新建_slug冲突_409() {
        when(articleMapper.selectBySlug("how-to")).thenReturn(new HelpArticleEntity());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(req("how-to")));
        assertTrue(ex.getMessage().contains("slug 已存在"));
        verify(articleMapper, never()).insert(any());
    }

    @Test
    void 新建_并发撞uk索引_409() {
        when(articleMapper.selectBySlug("how-to")).thenReturn(null);
        when(articleMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_help_article_slug"));

        assertThrows(BusinessException.class, () -> service.create(req("how-to")));
    }

    @Test
    void 硬删后_同slug可重建() {
        // 拍板硬删：删后 selectBySlug 查不到 → 重建成功（软删会占坑 409）
        HelpArticleEntity e = new HelpArticleEntity();
        e.setId(1L);
        e.setSlug("how-to");
        when(articleMapper.selectById(1L)).thenReturn(e);
        when(articleMapper.physicalDeleteById(1L)).thenReturn(1);

        service.delete(1L);
        verify(articleMapper).physicalDeleteById(1L);

        when(articleMapper.selectBySlug("how-to")).thenReturn(null);  // 硬删后查不到
        when(articleMapper.insert(any())).thenReturn(1);
        service.create(req("how-to"));                                 // 不抛 = 重建成功
        verify(articleMapper).insert(any());
    }

    @Test
    void 更新_slug不可改() {
        HelpArticleEntity e = new HelpArticleEntity();
        e.setId(1L);
        e.setSlug("old-slug");
        when(articleMapper.selectById(1L)).thenReturn(e);

        service.update(1L, req("new-slug"));

        var cap = org.mockito.ArgumentCaptor.forClass(HelpArticleEntity.class);
        verify(articleMapper).updateById(cap.capture());
        assertEquals("old-slug", cap.getValue().getSlug());   // 身份字段不动
        assertEquals("标题", cap.getValue().getTitle());
    }

    @Test
    void 用户读_未发布404() {
        HelpArticleEntity e = new HelpArticleEntity();
        e.setSlug("draft");
        e.setPublished(false);
        when(articleMapper.selectBySlug("draft")).thenReturn(e);

        assertThrows(BusinessException.class, () -> service.getPublishedBySlug("draft"));
    }

    @Test
    void 用户读_不存在404不泄露() {
        when(articleMapper.selectBySlug("ghost")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getPublishedBySlug("ghost"));
        assertTrue(ex.getMessage().contains("不存在或未发布"));
    }

    // ==================== V149 权限门控 ====================

    private void loginAs(String... authorities) {
        var auth = new UsernamePasswordAuthenticationToken(1L, "u",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @org.junit.jupiter.api.AfterEach
    void clearCtx() {
        SecurityContextHolder.clearContext();
    }

    private HelpArticleEntity published(String slug, String perm) {
        HelpArticleEntity e = new HelpArticleEntity();
        e.setSlug(slug);
        e.setTitle("t");
        e.setCategory("c");
        e.setSortOrder(0);
        e.setContentMd("m");
        e.setPublished(true);
        e.setRequiredPermission(perm);
        return e;
    }

    @Test
    void 目录_无权限码文章全员可见_有权限码按持有过滤() {
        loginAs("media:gen");
        when(articleMapper.selectList(any())).thenReturn(java.util.List.of(
                published("public-a", null),
                published("video-gen", "media:gen"),
                published("video-edit", "media:edit"),
                published("ops", "ROLE_admin")));

        var list = service.listPublished(null);
        var slugs = list.stream().map(v -> v.slug()).toList();
        assertEquals(java.util.List.of("public-a", "video-gen"), slugs);
    }

    @Test
    void 目录_admin全见() {
        loginAs("ROLE_admin");
        when(articleMapper.selectList(any())).thenReturn(java.util.List.of(
                published("video-edit", "media:edit"),
                published("ops", "ROLE_admin")));

        assertEquals(2, service.listPublished(null).size());
    }

    @Test
    void 直链_无权限按404不泄露() {
        loginAs("media:gen");
        when(articleMapper.selectBySlug("video-edit")).thenReturn(published("video-edit", "media:edit"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getPublishedBySlug("video-edit"));
        assertTrue(ex.getMessage().contains("不存在或未发布"));
    }

    @Test
    void 直链_持码可读() {
        loginAs("media:edit");
        when(articleMapper.selectBySlug("video-edit")).thenReturn(published("video-edit", "media:edit"));

        assertEquals("video-edit", service.getPublishedBySlug("video-edit").slug());
    }

    @Test
    void 直链_admin可读任意门控文章() {
        loginAs("ROLE_admin");
        when(articleMapper.selectBySlug("ops")).thenReturn(published("ops", "ROLE_admin"));

        assertEquals("ops", service.getPublishedBySlug("ops").slug());
    }
}
