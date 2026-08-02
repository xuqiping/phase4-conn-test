package com.superprogrammer.search.provider;

import com.superprogrammer.search.client.SearxngClient;
import com.superprogrammer.search.client.SearxngClient.SearxngItem;
import com.superprogrammer.search.dto.SearchOptions;
import com.superprogrammer.search.dto.SearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuiltInSearchProviderTest {

    @Test
    @DisplayName("available：SearXNG 未配置 → false")
    void available_false_when_not_configured() {
        SearxngClient c = mock(SearxngClient.class);
        when(c.isConfigured()).thenReturn(false);
        BuiltInSearchProvider p = new BuiltInSearchProvider(c);
        assertThat(p.available()).isFalse();
        assertThat(p.getName()).isEqualTo("builtin");
    }

    @Test
    @DisplayName("search：fetchContent=true → 调 SearXNG + 抽正文，按序赋递减 score")
    void search_with_content() {
        SearxngClient c = mock(SearxngClient.class);
        when(c.isConfigured()).thenReturn(true);
        when(c.search("q", 3)).thenReturn(List.of(
                new SearxngItem("t1", "https://a.example.com/1", "s1"),
                new SearxngItem("t2", "https://b.example.com/2", "s2")));
        when(c.fetchContent("https://a.example.com/1")).thenReturn("正文A");
        when(c.fetchContent("https://b.example.com/2")).thenReturn("");

        BuiltInSearchProvider p = new BuiltInSearchProvider(c);
        List<SearchResult> results = p.search("q", SearchOptions.builder().maxResults(3).fetchContent(true).build());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getTitle()).isEqualTo("t1");
        assertThat(results.get(0).getContent()).isEqualTo("正文A");
        assertThat(results.get(1).getContent()).isEmpty(); // 抽取失败降级空
        assertThat(results.get(0).getScore()).isGreaterThan(results.get(1).getScore()); // 递减
    }

    @Test
    @DisplayName("search：fetchContent=false → 不抽正文，content 全空")
    void search_snippet_only() {
        SearxngClient c = mock(SearxngClient.class);
        when(c.isConfigured()).thenReturn(true);
        when(c.search("q", 5)).thenReturn(List.of(
                new SearxngItem("t1", "https://a.example.com/1", "s1")));

        BuiltInSearchProvider p = new BuiltInSearchProvider(c);
        List<SearchResult> results = p.search("q", SearchOptions.builder().maxResults(5).fetchContent(false).build());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEmpty();
        assertThat(results.get(0).getSnippet()).isEqualTo("s1");
    }

    @Test
    @DisplayName("search：SearXNG 返空 → 空列表（零结果分支）")
    void search_empty_results() {
        SearxngClient c = mock(SearxngClient.class);
        when(c.isConfigured()).thenReturn(true);
        when(c.search("q", 5)).thenReturn(List.of());

        BuiltInSearchProvider p = new BuiltInSearchProvider(c);
        assertThat(p.search("q", SearchOptions.builder().build())).isEmpty();
    }

    @Test
    @DisplayName("search：未配置 → 直接返空，不调 SearXNG")
    void search_unconfigured() {
        SearxngClient c = mock(SearxngClient.class);
        when(c.isConfigured()).thenReturn(false);
        BuiltInSearchProvider p = new BuiltInSearchProvider(c);
        assertThat(p.search("q", SearchOptions.builder().build())).isEmpty();
    }
}
