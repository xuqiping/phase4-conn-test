package com.superprogrammer.search.provider;

import com.superprogrammer.search.client.SearxngClient;
import com.superprogrammer.search.client.SearxngClient.SearxngItem;
import com.superprogrammer.search.dto.SearchOptions;
import com.superprogrammer.search.dto.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 自建引擎（对标 Tavily）：SearXNG 元搜索拿 URL → 并发抽正文 → 组装 {@link SearchResult}。
 *
 * - {@link #getName()} 返 "builtin"，对应 system_settings search.active_provider=builtin。
 * - {@link #available()} = SearXNG base_url 已配置（连通性靠集成测/真实调用兜底）。
 * - {@link #search(String, SearchOptions)}：
 *   1. SearXNG 拿 top N（按 opts.maxResults）URL + snippet；
 *   2. opts.fetchContent=true 时并发（线程池 ≤5，单页超时由 SearxngClient 控制）抽正文，失败降级空 content；
 *   3. 按返回序赋递减 score（1.0 起，步长 1/N），组装 VO。
 *
 * 任何失败返回空列表（service 层据此降级纯聊天），不抛异常——符合 {@link WebSearchProvider} 契约。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltInSearchProvider implements WebSearchProvider {

    private final SearxngClient searxngClient;

    /** 抓正文并发上限（plan：≤5 防反爬 + 控压）。 */
    private static final int MAX_CONCURRENCY = 5;

    /** 抓取线程池（固定 5）。@PreDestroy 优雅关停。 */
    private final ExecutorService fetchPool = Executors.newFixedThreadPool(MAX_CONCURRENCY);

    @Override
    public String getName() {
        return "builtin";
    }

    @Override
    public boolean available() {
        return searxngClient.isConfigured();
    }

    @Override
    public List<SearchResult> search(String query, SearchOptions opts) {
        if (!available()) {
            return List.of();
        }
        int max = opts == null || opts.getMaxResults() == null ? 5 : opts.getMaxResults();
        boolean fetch = opts == null || opts.getFetchContent() == null || opts.getFetchContent();

        List<SearxngItem> items = searxngClient.search(query, max);
        if (items.isEmpty()) {
            return List.of();
        }

        // 并发抽正文（fetch=false 跳过，只回 snippet）
        List<CompletableFuture<String>> futures = new ArrayList<>(items.size());
        for (SearxngItem it : items) {
            if (fetch) {
                futures.add(CompletableFuture.supplyAsync(() -> searxngClient.fetchContent(it.url()), fetchPool));
            } else {
                futures.add(CompletableFuture.completedFuture(""));
            }
        }

        List<SearchResult> results = new ArrayList<>(items.size());
        double step = items.isEmpty() ? 0 : 1.0 / items.size();
        for (int i = 0; i < items.size(); i++) {
            SearxngItem it = items.get(i);
            String content;
            try {
                content = futures.get(i).get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("抽取正文超时/失败，降级 snippet: {} {}", it.url(), e.getMessage());
                content = "";
            }
            results.add(SearchResult.builder()
                    .title(it.title())
                    .url(it.url())
                    .snippet(it.snippet())
                    .content(content)
                    .score(1.0 - step * i) // 按序递减相关性
                    .build());
        }
        return results;
    }

    @PreDestroy
    void shutdown() {
        fetchPool.shutdown();
        try {
            if (!fetchPool.awaitTermination(3, TimeUnit.SECONDS)) {
                fetchPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            fetchPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
