package com.superprogrammer.file.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件下载/上传安全策略（安全体系 S1 · F-1 存储型 XSS 修复，SEC-FR-030）。
 *
 * <p>根因：{@code GET /api/files/{fileId}} 原对一切类型 {@code inline} 渲染，
 * 上传 evil.html 后管理员点开链接即同源执行 JS（可窃取 token）——已确认可利用的存储型 XSS。
 *
 * <p>决策矩阵（按 fileId 扩展名，fileId = UUID + 原扩展名）：
 * <ul>
 *   <li>危险类型（html/svg/xml/js…）→ {@code attachment} + {@code application/octet-stream}：
 *       浏览器强制下载，永不渲染；</li>
 *   <li>安全白名单（png/mp4/pdf…纯展示不执行）→ 维持 {@code inline} + 探测 MIME，预览不回归；</li>
 *   <li>其余未知类型 → 默认 {@code attachment}（安全换体验，宁错杀不放行）。</li>
 * </ul>
 * 下载响应统一由 controller 追加 {@code X-Content-Type-Options: nosniff}（防嗅探绕过）。
 *
 * <p>S4 F-2 已叠加 magic number 嗅探映射（{@link #EXT_DETECT_ACCEPTED}，由 FileUploadValidator 调用）；
 * 扩展名比对仍是入口第一关（零解析开销）。
 */
public final class FileSecurityPolicy {

    private FileSecurityPolicy() {
    }

    /**
     * 危险扩展名：浏览器同源渲染即可执行/解析脚本或 markup。
     * svg 危险因可内嵌 {@code <script>}；xml/xhtml/mht 同理可触发解析器。
     */
    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
            "html", "htm", "svg", "xml", "xhtml", "js", "mjs", "vbs", "mht", "mhtml", "jsp", "asp", "aspx", "php");

    /** 安全 inline 白名单：纯展示型媒体/文档，浏览器渲染无脚本执行面。 */
    private static final Set<String> INLINE_SAFE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "avif",
            "mp4", "webm", "mov", "m4v",
            "mp3", "wav", "ogg", "m4a", "flac",
            "pdf");

    /**
     * 上传允许扩展名（正列举，Step 2 入口根治）：文档/图片/音视频/压缩包/字幕等生产资料。
     * 危险类型与可执行文件（exe/bat/sh/dll…）天然不在列 → 拒收。
     */
    private static final Set<String> UPLOAD_ALLOWED_EXTENSIONS = Set.of(
            // 文档（14x-4：补 markdown/html/htm——知识库 DocumentParserService 的 docType 推断本就认这三种，
            // 且前端 accept 已列 .html/.markdown，缺白名单导致上传必被 FILE_TYPE_NOT_ALLOWED 拒；
            // html 下载侧仍走危险类型强制 attachment+nosniff，不新增 inline 暴露面）
            "txt", "md", "markdown", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "json", "srt", "vtt", "html", "htm",
            // 图片
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "avif",
            // 音频
            "mp3", "wav", "ogg", "m4a", "flac", "aac",
            // 视频
            "mp4", "webm", "mov", "m4v", "avi", "mkv",
            // 压缩包
            "zip", "rar", "7z", "tar", "gz");

    /** 下载决策：true=inline 安全白名单；false=attachment（危险或未知类型）。 */
    public static boolean isInlineSafe(String fileId) {
        return INLINE_SAFE_EXTENSIONS.contains(extensionOf(fileId));
    }

    /** 上传准入：扩展名 ∈ 正列举白名单。无扩展名/非法扩展名一律拒收（保守）。 */
    public static boolean isUploadAllowed(String originalFilename) {
        String ext = extensionOf(originalFilename);
        return !ext.isEmpty() && UPLOAD_ALLOWED_EXTENSIONS.contains(ext);
    }

    /**
     * 上传扩展名 → 嗅探可接受 MIME 集合（安全体系 S4 · SEC-FR-031，F-2 magic number）。
     *
     * <p>只收录有<strong>稳定文件头特征</strong>的格式（图片/音视频/压缩包/Office/PDF/HTML）；
     * 纯文本族（txt/md/markdown/csv/json/srt/vtt）无可靠魔数，<strong>不进表=不做内容比对</strong>
     * （防文本误判过度拒收）。zip 容器族（docx/xlsx/pptx）同时收录 OOXML 精确类型与
     * {@code application/zip}/{@code x-tika-ooxml} 变体——Tika 检测深度不同返回不同层级。
     * 嗅探结果 {@code application/octet-stream}（无法判定）不在此判定，由调用方按 UNKNOWN 放行+计数。
     */
    private static final Map<String, Set<String>> EXT_DETECT_ACCEPTED = Map.ofEntries(
            Map.entry("pdf", Set.of("application/pdf")),
            Map.entry("html", Set.of("text/html", "application/xhtml+xml")),
            Map.entry("htm", Set.of("text/html", "application/xhtml+xml")),
            // Office 传统二进制（OLE2 复合文档 → x-tika-msoffice 泛型）
            Map.entry("doc", Set.of("application/msword", "application/x-tika-msoffice")),
            Map.entry("xls", Set.of("application/vnd.ms-excel", "application/x-tika-msoffice")),
            Map.entry("ppt", Set.of("application/vnd.ms-powerpoint", "application/x-tika-msoffice")),
            // Office OOXML（zip 容器）
            Map.entry("docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/x-tika-ooxml", "application/zip")),
            Map.entry("xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/x-tika-ooxml", "application/zip")),
            Map.entry("pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/x-tika-ooxml", "application/zip")),
            // 图片
            Map.entry("png", Set.of("image/png")),
            Map.entry("jpg", Set.of("image/jpeg")),
            Map.entry("jpeg", Set.of("image/jpeg")),
            Map.entry("gif", Set.of("image/gif")),
            Map.entry("bmp", Set.of("image/bmp", "image/x-ms-bmp")),
            Map.entry("webp", Set.of("image/webp")),
            Map.entry("ico", Set.of("image/x-icon", "image/vnd.microsoft.icon")),
            Map.entry("avif", Set.of("image/avif")),
            // 音频
            Map.entry("mp3", Set.of("audio/mpeg")),
            Map.entry("wav", Set.of("audio/x-wav", "audio/wav")),
            Map.entry("ogg", Set.of("audio/ogg", "application/ogg", "video/ogg")),
            Map.entry("m4a", Set.of("audio/mp4", "audio/x-m4a", "audio/m4a", "video/mp4")),
            Map.entry("flac", Set.of("audio/flac", "audio/x-flac")),
            Map.entry("aac", Set.of("audio/aac", "audio/x-hx-aac-adts", "audio/mp4")),
            // 视频（mp4/mov 同为 ftyp 容器，互收录防 brand 差异误拒）
            Map.entry("mp4", Set.of("video/mp4", "video/quicktime")),
            Map.entry("m4v", Set.of("video/mp4", "video/quicktime")),
            Map.entry("mov", Set.of("video/quicktime", "video/mp4")),
            Map.entry("webm", Set.of("video/webm", "audio/webm")),
            Map.entry("avi", Set.of("video/x-msvideo", "video/avi")),
            Map.entry("mkv", Set.of("video/x-matroska", "application/x-matroska")),
            // 压缩包
            Map.entry("zip", Set.of("application/zip")),
            Map.entry("rar", Set.of("application/x-rar-compressed", "application/vnd.rar", "application/x-rar")),
            Map.entry("7z", Set.of("application/x-7z-compressed")),
            Map.entry("tar", Set.of("application/x-tar")),
            Map.entry("gz", Set.of("application/gzip", "application/x-gzip")));

    /** 嗅探兼容判定：ext 无映射（null）不判定；有映射则 detected 必须命中集合。 */
    public static boolean isDetectCompatible(String extension, String detectedMime) {
        if (extension == null || detectedMime == null) {
            return false;
        }
        Set<String> accepted = EXT_DETECT_ACCEPTED.get(extension.toLowerCase(Locale.ROOT));
        return accepted != null && accepted.contains(detectedMime);
    }

    /** ext 是否登记了嗅探映射（无映射=纯文本族等不做内容比对）。 */
    public static boolean hasDetectMapping(String extension) {
        return extension != null && EXT_DETECT_ACCEPTED.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    /** 取小写扩展名（不含点）；无/非法扩展名返回空串。与 FileStorageService 落盘扩展名口径一致。 */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,12}") ? ext : "";
    }
}
