package com.superprogrammer.search.util;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * 网页正文抽取（自建引擎对标 Tavily extract）。
 *
 * 算法（自实现精简版 Readability，不引第三方 readability 库）：
 * 1. Jsoup 解析 HTML（带 baseUrl 补全相对链接）。
 * 2. 剔除非正文节点：script/style/nav/footer/header/aside/form/iframe + display:none。
 * 3. 优先取 <article>；否则取 <p> 数最多的容器（"密度最大块"启发式）；再否则聚合全部 <p>。
 * 4. 抽出文本后过 {@link SanitizeUtil#sanitizeText}（去控制字符/折叠空白/截断 maxChars）。
 *
 * 输入是抓来的不可信 HTML，输出是已 sanitize 的纯文本，可直接注入 LLM system context。
 */
@Slf4j
@Component
public class ContentExtractor {

    /** 抽取正文默认字符上限（plan：每页 ≤2000 防 context 爆炸）。 */
    public static final int DEFAULT_MAX_CHARS = 2000;

    /** 剔除的非正文标签（正文抽取前清场）。 */
    private static final String[] DROP_TAGS = {
            "script", "style", "nav", "footer", "header", "aside",
            "form", "iframe", "noscript", "svg"
    };

    /**
     * 从 HTML 抽取正文纯文本。
     *
     * @param html    原始 HTML（可含噪声）；null/空 → 返回 ""
     * @param baseUrl 用于补全相对链接的基 URL；可为 null
     * @return 已 sanitize 的正文文本，最长 maxChars 字符
     */
    public String extract(String html, String baseUrl, int maxChars) {
        if (html == null || html.isBlank()) {
            return "";
        }
        try {
            String base = baseUrl == null ? "" : baseUrl;
            Document doc = Jsoup.parse(html, base);
            for (String tag : DROP_TAGS) {
                doc.select(tag).remove();
            }
            doc.select("[style*=display:none], [hidden]").remove();

            String text = pickMainText(doc);
            return SanitizeUtil.sanitizeText(text, maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS);
        } catch (Exception e) {
            log.warn("正文抽取失败，降级空正文: {}", e.getMessage());
            return "";
        }
    }

    /** 便捷重载：默认上限。 */
    public String extract(String html, String baseUrl) {
        return extract(html, baseUrl, DEFAULT_MAX_CHARS);
    }

    /**
     * 正文选择优先级：article → p 密度最大块 → 全部 p 聚合 → body text。
     * 抽不出有效正文（如 SPA 空壳）返回 ""，由上层降级 snippet。
     */
    private String pickMainText(Document doc) {
        // 1. <article> 优先
        Element article = doc.selectFirst("article");
        if (article != null && hasMeaningfulText(article)) {
            return article.text();
        }
        // 2. p 数最多的容器（密度启发式）
        Element densest = null;
        int maxP = 0;
        for (Element container : doc.select("div, section, main")) {
            int p = container.select("p").size();
            if (p > maxP) {
                maxP = p;
                densest = container;
            }
        }
        if (densest != null && maxP >= 3) {
            return densest.text();
        }
        // 3. 全部 p 聚合（fallback，对老式新闻页最稳）
        StringBuilder sb = new StringBuilder();
        for (Element p : doc.select("p")) {
            String t = p.text();
            if (!t.isBlank()) {
                sb.append(t).append(' ');
            }
        }
        String aggregated = sb.toString().trim();
        if (!aggregated.isEmpty()) {
            return aggregated;
        }
        // 4. 兜底 body 全文（脱敏后多半无用，但保证不空）
        return doc.body() != null ? doc.body().text() : "";
    }

    private boolean hasMeaningfulText(Element el) {
        return el != null && el.text().length() >= 120;
    }

    /**
     * Jsoup Safelist 清洗（保留基础排版标签），供需要保留少量 HTML 的场景；
     * 本引擎注入 LLM 走纯文本路径，此方法预留给后续 UI 预览等。
     */
    public String cleanHtml(String html, String baseUrl) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Jsoup.clean(html, baseUrl, Safelist.relaxed());
    }
}
