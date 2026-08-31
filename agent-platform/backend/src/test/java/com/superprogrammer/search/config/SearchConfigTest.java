package com.superprogrammer.search.config;

import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SearchConfig 单测：路由读 settings 派生值（tavily/builtin 的派生逻辑在
 * SystemSettingServiceTest 覆盖）+ 默认参数装配。
 */
class SearchConfigTest {

    private SystemSettingService settings(boolean enabled, String active) {
        SystemSettingService s = mock(SystemSettingService.class);
        when(s.getSearchEnabled()).thenReturn(enabled);
        when(s.getActiveSearchProvider()).thenReturn(active);
        return s;
    }

    @Test
    @DisplayName("路由：activeProvider 取 settings 派生值（tavily）")
    void routes_derived_provider_tavily() {
        SearchConfig c = new SearchConfig(settings(true, "tavily"));
        assertThat(c.activeProvider()).isEqualTo("tavily");
        assertThat(c.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("路由：activeProvider 取 settings 派生值（builtin=SearXNG）")
    void routes_derived_provider_builtin() {
        SearchConfig c = new SearchConfig(settings(true, "builtin"));
        assertThat(c.activeProvider()).isEqualTo("builtin");
    }

    @Test
    @DisplayName("默认参数：maxResults/timeoutMs 取 settings，fetchContent 恒 true（BuiltIn 靠它启正文抽取）")
    void default_options_assembled_from_settings() {
        SystemSettingService s = settings(true, "builtin");
        when(s.getSearchMaxResults()).thenReturn(7);
        when(s.getSearchTimeoutMs()).thenReturn(8000);
        SearchConfig c = new SearchConfig(s);

        assertThat(c.defaultOptions().getMaxResults()).isEqualTo(7);
        assertThat(c.defaultOptions().getTimeoutMs()).isEqualTo(8000);
        assertThat(c.defaultOptions().getFetchContent()).isTrue();
    }
}
