package com.superprogrammer.search.config;

import com.superprogrammer.search.dto.SearchOptions;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 联网搜索默认参数装配器：从 system_settings 读默认值（top-N / 超时 / 总开关 / active provider），
 * 组装 {@link SearchOptions} 交给 {@link com.superprogrammer.search.service.WebSearchService}。
 *
 * 单独抽出来便于：service 路由逻辑不掺参数装配；测试可直接 mock SystemSettingService 验证默认值回退。
 */
@Component
@RequiredArgsConstructor
public class SearchConfig {

    private final SystemSettingService settingService;

    /** 联网搜索总开关（false=整体禁用，service 直接返空）。 */
    public boolean isEnabled() {
        return settingService.getSearchEnabled();
    }

    /** 当前生效 provider（派生逻辑在 {@link SystemSettingService#getActiveSearchProvider}：
     *  Tavily 开关开 → tavily；否则 builtin 自建 SearXNG。key 错误/未配的兜底在 WebSearchService 降级链）。 */
    public String activeProvider() {
        return settingService.getActiveSearchProvider();
    }

    /** 按 system_settings 默认值组装 SearchOptions（maxResults / timeoutMs）。
     *  fetchContent 固定 true（外部 provider 自带 content 时抓取对它是 no-op，BuiltIn 靠它启正文抽取）。 */
    public SearchOptions defaultOptions() {
        return SearchOptions.builder()
                .maxResults(settingService.getSearchMaxResults())
                .timeoutMs(settingService.getSearchTimeoutMs())
                .fetchContent(true)
                .build();
    }
}
