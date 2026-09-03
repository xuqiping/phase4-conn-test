package com.superprogrammer.knowledge.connector;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * C6 URL 站点连接器（WP6 Step2）：种子 URL 同域 BFS 爬取——
 * 深度 ≤2（种子=0，其链接=1，再一层=2）、单轮 ≤50 页（可收紧注入）、后缀白名单
 * （html/htm/md/pdf/txt/docx/xlsx）、同域（scheme+host+port 全等）出界即弃。
 * 四重闸防爬虫黑洞：已访 URL 去重 + 深度闸 + 页数闸 + FetchLimiter 总字节闸（坑点表）。
 * 每页取数经 {@link SafeHttpFetch}（重定向逐跳 SSRF 校验）。
 */
public class UrlSiteConnector implements KnowledgeConnectorSpi {

    /** 计划口径后缀白名单（正文类文档；js/css/图片/压缩包一概不进）。 */
    static final Set<String> ALLOWED_EXTENSIONS = Set.of("html", "htm", "md", "pdf", "txt", "docx", "xlsx");
    /** 可继续展开链接的页面类型（pdf 等只作为文档条目，不解析链接）。 */
    private static final Set<String> CRAWLABLE = Set.of("html", "htm");

    private final String seedUrl;
    private final int maxPages;
    private final int maxDepth;
    private final Predicate<String> urlGuard;
    private final FetchLimiter limiter;
    private final Duration timeout = Duration.ofSeconds(15);

    public UrlSiteConnector(Map<String, Object> config, Predicate<String> urlGuard, FetchLimiter limiter) {
        this(config, urlGuard, limiter, 50, 2);
    }

    /** maxPages/maxDepth 可收紧（测试与运维调小用）。 */
    public UrlSiteConnector(Map<String, Object> config, Predicate<String> urlGuard, FetchLimiter limiter,
                            int maxPages, int maxDepth) {
        Object seed = config == null ? null : config.get("seedUrl");
        if (!(seed instanceof String s) || s.isBlank()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "URL_SITE 连接器缺 seedUrl");
        }
        this.seedUrl = s.trim();
        this.urlGuard = urlGuard;
        this.limiter = limiter;
        this.maxPages = maxPages;
        this.maxDepth = maxDepth;
    }

    @Override
    public String type() {
        return "URL_SITE";
    }

    @Override
    public java.util.List<ExternalDoc> list() throws Exception {
        URI seed = URI.create(seedUrl);
        String origin = seed.getScheme() + "://" + seed.getRawAuthority();   // 同域口径：scheme+host+port
        Map<String, ExternalDoc> docs = new HashMap<>();                     // externalId 去重
        Set<String> visited = new HashSet<>();
        Deque<String[]> queue = new ArrayDeque<>();                          // [url, depth]
        queue.add(new String[]{seedUrl, "0"});
        visited.add(stripFragment(seedUrl));
        while (!queue.isEmpty() && docs.size() < maxPages) {
            String[] head = queue.poll();
            String url = head[0];
            int depth = Integer.parseInt(head[1]);
            SafeHttpFetch.Fetched page = SafeHttpFetch.get(url, urlGuard, limiter, Map.of(), timeout);
            String finalUrl = stripFragment(page.finalUrl());
            String etag = extractEtag(page);
            docs.putIfAbsent(finalUrl, new ExternalDoc(finalUrl, etag, displayName(finalUrl)));
            if (!isCrawlable(finalUrl)) {
                continue;   // pdf 等不可解析页：只作条目，不提链接
            }
            boolean expandFurther = depth < maxDepth;   // 深度到顶仍提本页链接为条目（深度 3 的 pdf 挂深度 2 页照样同步），只是不再下钻
            Document dom = Jsoup.parse(new String(page.body(), java.nio.charset.StandardCharsets.UTF_8), finalUrl);
            for (org.jsoup.nodes.Element a : dom.select("a[href]")) {
                String abs = a.absUrl("href");
                if (abs.isEmpty()) {
                    continue;
                }
                String candidate = stripFragment(abs);
                if (!candidate.startsWith(origin) || !hasAllowedExtension(candidate)) {
                    continue;   // 出域或非白名单后缀
                }
                if (visited.add(candidate)) {
                    docs.putIfAbsent(candidate, new ExternalDoc(candidate, null, displayName(candidate)));
                    if (docs.size() >= maxPages) {
                        break;
                    }
                    if (expandFurther) {
                        queue.add(new String[]{candidate, String.valueOf(depth + 1)});
                    }
                }
            }
        }
        return new java.util.ArrayList<>(docs.values());
    }

    @Override
    public byte[] fetch(ExternalDoc doc) throws Exception {
        SafeHttpFetch.Fetched fetched = SafeHttpFetch.get(doc.externalId(), urlGuard, limiter, Map.of(), timeout);
        return fetched.body();
    }

    // ============================ 工具 ============================

    static String stripFragment(String url) {
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash) : url;
    }

    static boolean hasAllowedExtension(String url) {
        String path = URI.create(url).getPath();
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return true;   // 目录式 URL 视为 html（站点常见无尾斜杠由服务端补）
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return true;   // 无扩展名按页面处理（如 /about）
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.contains(ext);
    }

    private static boolean isCrawlable(String url) {
        String path = URI.create(url).getPath();
        if (path == null || path.endsWith("/") || path.isEmpty()) {
            return true;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return true;
        }
        return CRAWLABLE.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** ETag 优先，Last-Modified 兜底（前缀区分指纹来源；URL 站点两者皆无则 null=不可增量）。 */
    private static String extractEtag(SafeHttpFetch.Fetched fetched) {
        String etag = fetched.headers().firstValue("ETag").orElse(null);
        if (etag != null && !etag.isBlank()) {
            return etag;
        }
        String lastModified = fetched.headers().firstValue("Last-Modified").orElse(null);
        return lastModified == null || lastModified.isBlank() ? null : "LM:" + lastModified;
    }

    private static String displayName(String url) {
        String path = URI.create(url).getPath();
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return url;
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? url : name;
    }
}
