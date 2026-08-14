package com.superprogrammer.file.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全体系 S4 · SEC-FR-031（F-2 magic number）三态判定测试。
 * 覆盖：不一致拒收 / 兼容放行 / 未知放行+计数 / 纯文本族不比对 / 开关关 / 设置异常降级 /
 * store 咽喉点集成（含 @InjectMocks 构造注入不填可选字段的 ReflectionTestUtils 范式）。
 */
@ExtendWith(MockitoExtension.class)
class FileUploadValidatorTest {

    /** PNG 魔数（8 字节文件头）。 */
    private static final byte[] PNG_MAGIC =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    /** PE 可执行（MZ 头）。 */
    private static final byte[] EXE_MAGIC = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};

    @Mock
    SystemSettingService systemSettingService;

    @Mock
    BizMetrics bizMetrics;

    private FileUploadValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FileUploadValidator(systemSettingService, bizMetrics);
        lenientOn();
    }

    private void lenientOn() {
        org.mockito.Mockito.lenient().when(systemSettingService.getUploadMagicSniffEnabled()).thenReturn(true);
    }

    // AC：exe 改名 .png → 嗅探不一致拒收（F-2 核心用例）
    @Test
    void exeRenamedAsPng_rejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png", EXE_MAGIC);

        assertThatThrownBy(() -> validator.sniff(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不符");
        verify(bizMetrics).uploadMagicDenied("mismatch");
    }

    // AC：真 PNG 放行
    @Test
    void realPngBytes_pass() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", PNG_MAGIC);

        assertThatCode(() -> validator.sniff(file)).doesNotThrowAnyException();
        verify(bizMetrics, never()).uploadMagicDenied(anyString());
    }

    // AC：html 扩展名 + html 内容 = 声明一致放行（14x-4 决策：html 可传，下载侧强制 attachment）
    @Test
    void htmlExtHtmlContent_consistent_pass() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "page.html", "text/html",
                "<html><body>hello</body></html>".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> validator.sniff(file)).doesNotThrowAnyException();
    }

    // AC：zip 容器族变体（docx 字节以 PK 开头）兼容放行
    @Test
    void docxZipContainer_pass() {
        byte[] zipMagic = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 6, 0};
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.docx", "application/octet-stream", zipMagic);

        assertThatCode(() -> validator.sniff(file)).doesNotThrowAnyException();
    }

    // AC：嗅探无法判定（octet-stream）→ 放行 + 观察计数（可用性优先）
    @Test
    void undetectableBytes_passAndCounted() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "odd.png", "image/png", new byte[]{1, 2, 3});

        assertThatCode(() -> validator.sniff(file)).doesNotThrowAnyException();
        verify(bizMetrics).uploadMagicUnknown();
    }

    // AC：纯文本族（txt/md/csv 无稳定魔数）不进映射表不做比对——html 内容存 .txt 也放行
    @Test
    void textFamilyNoMapping_skipsSniff() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> validator.sniff(file)).doesNotThrowAnyException();
        verify(bizMetrics, never()).uploadMagicDenied(anyString());
    }

    // AC：开关关 → exe 改名也放行
    @Test
    void switchOff_passesEverything() {
        when(systemSettingService.getUploadMagicSniffEnabled()).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png", EXE_MAGIC);

        assertThatCode(() -> validator.sniff(file)).doesNotThrowAnyException();
    }

    // AC：设置读取异常 → 按默认开继续嗅探（真正嗅探异常另有兜底）
    @Test
    void settingReadError_stillSniffs() {
        when(systemSettingService.getUploadMagicSniffEnabled())
                .thenThrow(new RuntimeException("db down"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png", EXE_MAGIC);

        assertThatThrownBy(() -> validator.sniff(file))
                .isInstanceOf(BusinessException.class);
    }

    // AC：store 咽喉点集成——嗅探器注入后 exe 改名在 store 即拒
    @Test
    void storeIntegration_rejectsAtThroat() {
        FileStorageService service = new FileStorageService(
                java.nio.file.Paths.get("target", "upload-sniff-test").toString(),
                org.mockito.Mockito.mock(com.superprogrammer.file.mapper.StoredFileMapper.class));
        ReflectionTestUtils.setField(service, "uploadValidator", validator);

        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png", EXE_MAGIC);

        assertThatThrownBy(() -> service.store(file, 7L, "TEST"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不符");
    }

    // AC：store 咽喉点——validator 缺席（手写构造）直通，仅扩展开关生效（不破坏既有切片）
    @Test
    void storeIntegration_validatorAbsent_passes() {
        FileStorageService service = new FileStorageService(
                java.nio.file.Paths.get("target", "upload-sniff-test").toString(),
                org.mockito.Mockito.mock(com.superprogrammer.file.mapper.StoredFileMapper.class));

        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png", EXE_MAGIC);

        assertThatCode(() -> service.store(file, 7L, "TEST")).doesNotThrowAnyException();
    }
}
