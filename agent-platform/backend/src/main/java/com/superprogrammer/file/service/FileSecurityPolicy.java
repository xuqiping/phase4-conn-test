package com.superprogrammer.file.service;

import java.util.Locale;
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
 * <p>S4 F-2 将在此类扩展 magic number 字节嗅探；本阶段仅扩展名比对，零解析开销。
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
