package com.superprogrammer.file.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.system.service.SystemSettingService;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * 上传内容嗅探校验（安全体系 S4 · SEC-FR-031，F-2 magic number）。
 *
 * <p>防线定位：{@link FileStorageService#store} 咽喉点在扩展名白名单（S1 SEC-FR-030c）之后的第二关——
 * 客户端 Content-Type 可伪造、扩展名可改名，但文件头魔数改不了。exe 改名 {@code evil.png}
 * 在此被「声明 png ≠ 嗅探 application/x-dosexec」拒收。
 *
 * <p>三态判定（可用性优先）：
 * <ul>
 *   <li><strong>兼容</strong>：嗅探 MIME ∈ {@link FileSecurityPolicy#isDetectCompatible} 映射集合 → 放行；</li>
 *   <li><strong>不一致</strong>：ext 有映射但嗅探命中他类（如 png 嗅出 PE 可执行）→ 拒收 40010 + 计数；</li>
 *   <li><strong>未知</strong>：嗅探 {@code application/octet-stream}（判不了）→ 放行 + 观察计数
 *       （上线后按 {@code security.upload.magic.unknown} 指标决定是否收紧）。</li>
 * </ul>
 *
 * <p>检测层不自残：开关关 / 设置读失败 / 嗅探抛异常一律放行 + WARN（同 S3 范式——
 * 校验层故障宁可漏检不掐断全部上传）。纯文本族（txt/md/csv 等）无稳定魔数，不进映射表不做比对。
 */
@Component
public class FileUploadValidator {

    private static final Logger log = LoggerFactory.getLogger(FileUploadValidator.class);

    private static final String OCTET_STREAM = "application/octet-stream";

    /** 嗅探只读文件头前 64KB（魔数判定足够，避免大文件全量读）。 */
    private static final int HEAD_LIMIT = 64 * 1024;

    private final SystemSettingService systemSettingService;
    private final BizMetrics bizMetrics;

    public FileUploadValidator(SystemSettingService systemSettingService, BizMetrics bizMetrics) {
        this.systemSettingService = systemSettingService;
        this.bizMetrics = bizMetrics;
    }

    /**
     * 嗅探入口（store 咽喉点调用）。不一致抛 {@link ErrorCode#FILE_TYPE_NOT_ALLOWED}；
     * 其余情况静默放行。
     */
    public void sniff(MultipartFile file) {
        try {
            if (!enabled()) {
                return;
            }
            String ext = FileSecurityPolicy.extensionOf(file.getOriginalFilename());
            if (!FileSecurityPolicy.hasDetectMapping(ext)) {
                return;   // 纯文本族等无魔数格式：扩展名白名单已足够
            }
            // 只读文件头做纯魔数嗅探——刻意不用 detect(stream, name)：Tika 会拿文件名当提示，
            // 「evil.png」按名字猜出 image/png，嗅探就被改名骗过了。魔数才反映真实内容。
            byte[] head = new byte[HEAD_LIMIT];
            int n;
            try (InputStream in = file.getInputStream()) {
                n = in.readNBytes(head, 0, head.length);
            }
            String detected = n == 0 ? null : new Tika().detect(java.util.Arrays.copyOf(head, n));
            if (detected == null || OCTET_STREAM.equals(detected)) {
                if (bizMetrics != null) {
                    bizMetrics.uploadMagicUnknown();
                }
                return;   // 未知：放行观察
            }
            if (!FileSecurityPolicy.isDetectCompatible(ext, detected)) {
                if (bizMetrics != null) {
                    bizMetrics.uploadMagicDenied("mismatch");
                }
                log.warn("上传嗅探不一致拒收 ext={} detected={} name={} size={}",
                        ext, detected, file.getOriginalFilename(), file.getSize());
                throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED,
                        "文件内容与声明类型（." + ext + "）不符，请核对文件后重新上传");
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("上传嗅探异常放行 name={}: {}", file.getOriginalFilename(), e.getMessage());
        }
    }

    private boolean enabled() {
        try {
            return systemSettingService != null && systemSettingService.getUploadMagicSniffEnabled();
        } catch (Exception e) {
            return true;   // 设置读取失败按默认开（嗅探自身异常另有兜底）
        }
    }
}
