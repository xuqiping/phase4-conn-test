package com.superprogrammer.file.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全体系 S1 · SEC-FR-030 文件安全策略矩阵测试。
 * AC 对应：SEC-FR-030a（危险类型非 inline）、SEC-FR-030c（上传白名单）。
 */
class FileSecurityPolicyTest {

    // AC-SEC-FR-030a：危险类型绝不 inline（浏览器渲染即可执行脚本）
    @ParameterizedTest
    @ValueSource(strings = {
            "uuid.html", "uuid.htm", "uuid.svg", "uuid.xml", "uuid.xhtml",
            "uuid.js", "uuid.mjs", "uuid.vbs", "uuid.mht", "uuid.mhtml", "uuid.php"
    })
    void dangerousTypesNeverInline(String fileId) {
        assertFalse(FileSecurityPolicy.isInlineSafe(fileId), fileId + " must not be inline");
    }

    // AC-SEC-FR-030a：未知/无扩展名默认 attachment（安全换体验）
    @ParameterizedTest
    @ValueSource(strings = {"uuid", "uuid.unknownext", "uuid.exe", "uuid.bat", "uuid.sh", "uuid.html.bak"})
    void unknownTypesDefaultToAttachment(String fileId) {
        assertFalse(FileSecurityPolicy.isInlineSafe(fileId), fileId + " must default to attachment");
    }

    // 回归保护：预览白名单（图片/视频/音频/pdf）维持 inline，画布/资产库预览不回归
    @ParameterizedTest
    @ValueSource(strings = {
            "uuid.png", "uuid.jpg", "uuid.jpeg", "uuid.webp", "uuid.gif", "uuid.bmp",
            "uuid.mp4", "uuid.webm", "uuid.mov", "uuid.mp3", "uuid.wav", "uuid.pdf",
            "uuid.PNG", "uuid.MP4"  // 大小写不敏感
    })
    void safePreviewTypesStayInline(String fileId) {
        assertTrue(FileSecurityPolicy.isInlineSafe(fileId), fileId + " must stay inline");
    }

    // AC-SEC-FR-030c：上传白名单拒收危险类型与可执行文件。
    // html/htm 不在拒收列——14x-4 决策：KB 解析器认 html/markdown 且前端 accept 已列，
    // 上传放行、下载侧维持强制 attachment+nosniff（下载面测试 dangerousTypesNeverInline 锁死）。
    @ParameterizedTest
    @ValueSource(strings = {
            "evil.svg", "evil.xml", "evil.js", "evil.exe", "evil.bat",
            "evil.sh", "evil.dll", "evil.jsp", "evil.php", "noext", ".hidden"
    })
    void uploadRejectsDangerousAndExecutable(String filename) {
        assertFalse(FileSecurityPolicy.isUploadAllowed(filename), filename + " must be rejected");
    }

    // AC-SEC-FR-030c：生产资料正列举放行
    @ParameterizedTest
    @ValueSource(strings = {
            "doc.pdf", "doc.docx", "sheet.xlsx", "notes.md", "data.csv",
            "pic.png", "pic.webp", "song.mp3", "clip.mp4", "pack.zip"
    })
    void uploadAllowsProductivityFiles(String filename) {
        assertTrue(FileSecurityPolicy.isUploadAllowed(filename), filename + " must be allowed");
    }

    @Test
    void extensionParsingEdgeCases() {
        // fileId 真实形态：UUID + 原扩展名
        assertTrue(FileSecurityPolicy.isInlineSafe("3f2a1b-cccc-dddd.png"));
        // 多点文件名取最后一段
        assertFalse(FileSecurityPolicy.isInlineSafe("archive.tar.html"));
        assertTrue(FileSecurityPolicy.isUploadAllowed("archive.tar.gz"));
        // null / 尾部点 / 超长扩展名
        assertFalse(FileSecurityPolicy.isUploadAllowed(null));
        assertFalse(FileSecurityPolicy.isInlineSafe("file."));
        assertFalse(FileSecurityPolicy.isInlineSafe("file.abcdefghijklmnop"));
    }
}
