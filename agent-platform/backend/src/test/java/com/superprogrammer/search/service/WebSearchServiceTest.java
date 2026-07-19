package com.superprogrammer.search.service;

import com.superprogrammer.search.config.SearchConfig;
import com.superprogrammer.search.dto.SearchOptions;
import com.superprogrammer.search.dto.SearchResult;
import com.superprogrammer.search.provider.WebSearchProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSearchService 单测：路由 + 降级链 + 重试 + 审计。
 * 全程 mock provider，验证 service 层编排逻辑（不触网）。
 */
class WebSearchServiceTest {

    private static SearchResult r(String t) {
        return SearchResult.builder().title(t).url("https://x.example/" + t).snippet("s").content("c").build();
    }

    private WebSearchProvider provider(String name, boolean available) {
        WebSearchProvider p = mock(WebSearchProvider.class);
        when(p.getName()).thenReturn(name);
        when(p.available()).thenReturn(available);
        return p;
    }

    private SearchConfig config(boolean enabled, String active) {
        SearchConfig c = mock(SearchConfig.class);
        when(c.isEnabled()).thenReturn(enabled);
        when(c.activeProvider()).thenReturn(active);
        when(c.defaultOptions()).thenReturn(SearchOptions.builder().build());
        return c;
    }

    // ============================ 总开关 / query 校验 ============================

    @Test
    @DisplayName("总开关关 → 直接返空，不调任何 provider")
    void disabled_returns_empty() {
        WebSearchProvider tavily = provider("tavily", true);
        WebSearchService svc = new WebSearchService(config(false, "tavily"), List.of(tavily));
        assertThat(svc.search("hello")).isEmpty();
        verify(tavily, never()).search(anyString(), any());
    }

    @Test
    @DisplayName("query 空白/纯控制字符 → sanitize 后返空")
    void empty_query_returns_empty() {
        WebSearchProvider tavily = provider("tavily", true);
        WebSearchService svc = new WebSearchService(config(true, "tavily"), List.of(tavily));
        assertThat(svc.search("   ")).isEmpty();
        assertThat(svc.search(null)).isEmpty();
        verify(tavily, never()).search(anyString(), any());
    }

    // ============================ 路由 + 降级 ============================

    @Test
    @DisplayName("active=外部不可用 → 降级 builtin")
    void active_unavailable_fallback_builtin() {
        WebSearchProvider tavily = provider("tavily", false); // 无 key
        WebSearchProvider builtin = provider("builtin", true);
        when(builtin.search(anyString(), any())).thenReturn(List.of(r("b1")));
        WebSearchService svc = new WebSearchService(config(true, "tavily"), List.of(tavily, builtin));

        List<SearchResult> out = svc.search("q");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getTitle()).isEqualTo("b1");
        verify(tavily, never()).search(anyString(), any()); // 不可用直接不调
        verify(builtin, times(1)).search(anyString(), any());
    }

    @Test
    @DisplayName("外部 provider 返空 → 降级 builtin")
    void external_empty_fallback_builtin() {
        WebSearchProvider tavily = provider("tavily", true);
        when(tavily.search(anyString(), any())).thenReturn(List.of());
        WebSearchProvider builtin = provider("builtin", true);
        when(builtin.search(anyString(), any())).thenReturn(List.of(r("b1")));
        WebSearchService svc = new WebSearchService(config(true, "tavily"), List.of(tavily, builtin));

        List<SearchResult> out = svc.search("q");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getTitle()).isEqualTo("b1");
        verify(builtin, times(1)).search(anyString(), any());
    }

    @Test
    @DisplayName("外部返空 + builtin 也空 → 返空（走零结果分支）")
    void all_empty_returns_empty() {
        WebSearchProvider tavily = provider("tavily", true);
        when(tavily.search(anyString(), any())).thenReturn(List.of());
        WebSearchProvider builtin = provider("builtin", true);
        when(builtin.search(anyString(), any())).thenReturn(List.of());
        WebSearchService svc = new WebSearchService(config(true, "tavily"), List.of(tavily, builtin));

        assertThat(svc.search("q")).isEmpty();
    }

    @Test
    @DisplayName("active=builtin 直接走 builtin，不二次降级")
    void builtin_direct() {
        WebSearchProvider builtin = provider("builtin", true);
        when(builtin.search(anyString(), any())).thenReturn(List.of(r("b1")));
        WebSearchService svc = new WebSearchService(config(true, "builtin"), List.of(builtin));

        List<SearchResult> out = svc.search("q");
        assertThat(out).hasSize(1);
        verify(builtin, times(1)).search(anyString(), any());
    }

    @Test
    @DisplayName("builtin 不可用（无 SearXNG URL）+ 外部空 → 返空不崩")
    void builtin_unavailable_no_crash() {
        WebSearchProvider tavily = provider("tavily", true);
        when(tavily.search(anyString(), any())).thenReturn(List.of());
        WebSearchProvider builtin = provider("builtin", false); // 未配置
        WebSearchService svc = new WebSearchService(config(true, "tavily"), List.of(tavily, builtin));

        assertThat(svc.search("q")).isEmpty();
        verify(builtin, never()).search(anyString(), any()); // 不可用不调
    }

    // ============================ 重试 ============================

    @Test
    @DisplayName("外部 provider 首次返空 → 重试 1 次；命中第二次返回结果")
    void external_retry_on_empty() {
        WebSearchProvider tavily = provider("tavily", true);
        when(tavily.search(anyString(), any()))
                .thenReturn(List.of())           // 第 1 次
                .thenReturn(List.of(r("hit")));  // 第 2 次
        WebSearchProvider builtin = provider("builtin", true);
        WebSearchService svc = new WebSearchService(config(true, "tavily"), List.of(tavily, builtin));

        List<SearchResult> out = svc.search("q");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getTitle()).isEqualTo("hit");
        verify(tavily, times(2)).search(anyString(), any());
        verify(builtin, never()).search(anyString(), any()); // 重试命中不再降级
    }

    @Test
    @DisplayName("builtin 不重试（即使返空也只调 1 次）")
    void builtin_no_retry() {
        WebSearchProvider builtin = provider("builtin", true);
        when(builtin.search(anyString(), any())).thenReturn(List.of());
        WebSearchService svc = new WebSearchService(config(true, "builtin"), List.of(builtin));

        assertThat(svc.search("q")).isEmpty();
        verify(builtin, times(1)).search(anyString(), any());
    }

    @Test
    @DisplayName("provider 抛异常（契约破坏）→ 吞掉当空，不阻塞降级链")
    void provider_throws_treated_as_empty() {
        WebSearchProvider tavily = provider("tavily", true);
        AtomicInteger calls = new AtomicInteger();
        when(tavily.search(anyString(), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        });
        WebSearchProvider builtin = provider("builtin", true);
        when(builtin.search(anyString(), any())).thenReturn(List.of(r("b1")));
        WebSearchService svc = new WebSearchService(config(true, "tavily"), List.of(tavily, builtin));

        List<SearchResult> out = svc.search("q");
        assertThat(out).hasSize(1); // 降级 builtin 拿到
        assertThat(calls.get()).isEqualTo(2); // 外部重试 1 次共调 2 次
        verify(builtin, times(1)).search(anyString(), any());
    }
}
