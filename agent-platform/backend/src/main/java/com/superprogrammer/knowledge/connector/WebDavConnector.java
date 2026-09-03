package com.superprogrammer.knowledge.connector;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * C6 WebDAV 连接器（WP6 Step2）：PROPFIND Depth:1 逐目录递归枚举（目录集合
 * {@code <d:resourcetype><d:collection/>} 判定，文件取 {@code <d:getetag>}），
 * 目录深度 ≤5、条目 ≤500 兜底；Basic 认证头随行；URL 站点同款四重闸（去重/深度/条目/字节）。
 * 命名空间宽松匹配（href/getetag/resourcollection 大小写与前缀均可变）。
 */
public class WebDavConnector implements KnowledgeConnectorSpi {

    private static final Set<String> ALLOWED_EXTENSIONS = UrlSiteConnector.ALLOWED_EXTENSIONS;
    private final String baseUrl;
    private final String authHeader;
    private final Predicate<String> urlGuard;
    private final FetchLimiter limiter;
    private final int maxEntries;
    private final int maxDepth;
    private final Duration timeout = Duration.ofSeconds(15);

    public WebDavConnector(Map<String, Object> config, Predicate<String> urlGuard, FetchLimiter limiter) {
        this(config, urlGuard, limiter, 500, 5);
    }

    public WebDavConnector(Map<String, Object> config, Predicate<String> urlGuard, FetchLimiter limiter,
                           int maxEntries, int maxDepth) {
        Object base = config == null ? null : config.get("baseUrl");
        if (!(base instanceof String s) || s.isBlank()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "WEBDAV 连接器缺 baseUrl");
        }
        this.baseUrl = trimTrailingSlash(s.trim());
        Object username = config.get("username");
        Object password = config.get("password");
        if (username instanceof String u && !u.isBlank() && password instanceof String p && !p.isBlank()) {
            this.authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8));
        } else {
            this.authHeader = null;   // 匿名 WebDAV（公共目录）
        }
        this.urlGuard = urlGuard;
        this.limiter = limiter;
        this.maxEntries = maxEntries;
        this.maxDepth = maxDepth;
    }

    @Override
    public String type() {
        return "WEBDAV";
    }

    @Override
    public List<ExternalDoc> list() throws Exception {
        Map<String, ExternalDoc> docs = new HashMap<>();
        Set<String> visitedDirs = new HashSet<>();
        Deque<String[]> queue = new ArrayDeque<>();   // [dirPath(url), depth]
        queue.add(new String[]{baseUrl, "0"});
        visitedDirs.add(baseUrl);
        while (!queue.isEmpty() && docs.size() < maxEntries) {
            String[] head = queue.poll();
            String dirUrl = head[0];
            int depth = Integer.parseInt(head[1]);
            Map<String, String> headers = authHeader == null ? Map.of() : Map.of("Authorization", authHeader);
            SafeHttpFetch.Fetched resp = SafeHttpFetch.propfind(dirUrl, urlGuard, limiter, headers, "1", timeout);
            String xml = new String(resp.body(), StandardCharsets.UTF_8);
            // XML 专用解析器（HTML 解析会小写化/重排树）；命名空间前缀标签 CSS select 不匹配 → 按本地名递归找
            org.jsoup.nodes.Document dom = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser());
            for (Element response : findByLocalName(dom, "response")) {
                String href = childText(response, "href");
                if (href == null || href.isBlank()) {
                    continue;
                }
                String absolute = URI.create(resp.finalUrl()).resolve(href).toString();
                String normalized = trimTrailingSlash(UrlSiteConnector.stripFragment(absolute));
                if (normalized.equals(trimTrailingSlash(resp.finalUrl())) || visitedDirs.contains(normalized)) {
                    continue;   // 自身引用（PROPFIND 目录本身也在 multistatus 里）与已访去重
                }
                if (isCollection(response)) {
                    if (depth < maxDepth && visitedDirs.add(normalized + "/")) {
                        queue.add(new String[]{normalized + "/", String.valueOf(depth + 1)});
                    }
                } else if (hasAllowedFileExtension(normalized)) {
                    String etag = childText(response, "getetag");
                    docs.putIfAbsent(normalized, new ExternalDoc(normalized,
                            etag == null || etag.isBlank() ? null : etag, displayName(normalized)));
                    if (docs.size() >= maxEntries) {
                        break;
                    }
                }
            }
        }
        return new ArrayList<>(docs.values());
    }

    @Override
    public byte[] fetch(ExternalDoc doc) throws Exception {
        Map<String, String> headers = authHeader == null ? Map.of() : Map.of("Authorization", authHeader);
        return SafeHttpFetch.get(doc.externalId(), urlGuard, limiter, headers, timeout).body();
    }

    // ============================ 工具 ============================

    /** 按本地名递归收集元素（命名空间前缀 d:/D:/无前缀皆有，CSS select 按字面匹配不中前缀标签）。 */
    private static List<Element> findByLocalName(org.jsoup.nodes.Element root, String localName) {
        List<Element> found = new ArrayList<>();
        collectByLocalName(root, localName, found);
        return found;
    }

    private static void collectByLocalName(Element element, String localName, List<Element> out) {
        if (localName.equalsIgnoreCase(element.tagName().replaceFirst("^[a-zA-Z]+:", ""))) {
            out.add(element);
        }
        for (Element child : element.children()) {
            collectByLocalName(child, localName, out);
        }
    }

    /** 命名空间宽松取子树文本（response > propstat > prop > getetag 任意嵌套，按本地名匹配叶子）。 */
    private static String childText(Element parent, String localName) {
        for (Element child : parent.children()) {
            if (localName.equalsIgnoreCase(child.tagName().replaceFirst("^[a-zA-Z]+:", ""))
                    && child.children().isEmpty()) {
                return child.text();
            }
            String nested = childText(child, localName);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /** 子树任意层出现 collection 元素即目录（jsoup CSS select 不匹配带命名空间前缀的标签，须递归按本地名判）。 */
    private static boolean isCollection(Element element) {
        if ("collection".equalsIgnoreCase(element.tagName().replaceFirst("^[a-zA-Z]+:", ""))) {
            return true;
        }
        for (Element child : element.children()) {
            if (isCollection(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAllowedFileExtension(String url) {
        String path = URI.create(url).getPath();
        int dot = path == null ? -1 : path.lastIndexOf('.');
        return dot >= 0 && UrlSiteConnector.ALLOWED_EXTENSIONS
                .contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String displayName(String url) {
        String path = URI.create(url).getPath();
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : url;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") && url.length() > 1 ? url.substring(0, url.length() - 1) : url;
    }
}
