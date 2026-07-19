package com.superprogrammer.search.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentExtractorTest {

    private final ContentExtractor extractor = new ContentExtractor();

    @Test
    @DisplayName("article 标签优先：取 article 正文，丢 nav/footer")
    void extract_prefers_article() {
        String html = """
                <html><body>
                  <nav>首页 产品 关于</nav>
                  <article>
                    <p>这是正文第一段，写得足够长以确保越过最小长度阈值，让启发式判定为有效正文内容不落入兜底分支。</p>
                    <p>正文第二段继续，凑足字符数让 hasMeaningfulText 通过。</p>
                  </article>
                  <footer>版权所有 © 2026</footer>
                </body></html>""";
        String out = extractor.extract(html, "https://example.com/a");
        assertThat(out).contains("正文第一段");
        assertThat(out).doesNotContain("版权所有");
        assertThat(out).doesNotContain("首页 产品");
    }

    @Test
    @DisplayName("无 article：取 p 密度最大块（div 含多 p）")
    void extract_densest_block() {
        String html = """
                <html><body>
                  <div class="sidebar"><p>广告</p></div>
                  <div class="main">
                    <p>主区块段落一，足够长度越过阈值。</p>
                    <p>主区块段落二。</p>
                    <p>主区块段落三，凑足 p 数让密度启发式命中。</p>
                  </div>
                </body></html>""";
        String out = extractor.extract(html, null);
        assertThat(out).contains("主区块段落一");
        assertThat(out).doesNotContain("广告");
    }

    @Test
    @DisplayName("无 article 无密度块：fallback 聚合全部 p")
    void extract_fallback_aggregate_p() {
        String html = "<html><body><p>段落A</p><p>段落B</p></body></html>";
        String out = extractor.extract(html, null);
        assertThat(out).contains("段落A", "段落B");
    }

    @Test
    @DisplayName("空/无效 HTML → 空串，不崩")
    void extract_empty() {
        assertThat(extractor.extract(null, null)).isEmpty();
        assertThat(extractor.extract("", null)).isEmpty();
        assertThat(extractor.extract("   ", null)).isEmpty();
    }

    @Test
    @DisplayName("截断到 maxChars")
    void extract_truncates() {
        StringBuilder sb = new StringBuilder("<html><body><article><p>");
        for (int i = 0; i < 5000; i++) {
            sb.append("字");
        }
        sb.append("</p></article></body></html>");
        String out = extractor.extract(sb.toString(), null, 200);
        assertThat(out.length()).isLessThanOrEqualTo(200);
    }

    @Test
    @DisplayName("script/style 内容被剔除（防注入载荷进入正文）")
    void extract_strips_script() {
        String html = """
                <html><body>
                  <article><p>正文内容足够长以越过阈值判定，确保走 article 主分支而不是兜底聚合，长度长度长度。</p></article>
                  <script>alert('xss')</script>
                  <style>.x{color:red}</style>
                </body></html>""";
        String out = extractor.extract(html, null);
        assertThat(out).doesNotContain("alert").doesNotContain("color:red");
    }
}
