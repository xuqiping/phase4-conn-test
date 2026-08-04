package com.superprogrammer.media.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * 媒体产物存储（Step4）：Ark 临时 URL → 本地 stored_files。
 *
 * <p>Ark 返回的 video_url 是 OSS 临时链接（有时效），任务 SUCCEEDED 时须即时流式下载落盘，
 * 之后只依赖本地 fileId（设计 §5 关键决策 5）。复用 {@link FileStorageService#storeStream}
 * 单一存储咽喉点（防路径穿越 + 登记 owner）。
 *
 * <p>另提供图生视频参考图 file_id → data URI 转换（Ark image_url 接受 data:base64）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaStorageService {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);
    private static final long MAX_DATA_URI_BYTES = 8L * 1024 * 1024; // 参考图 ≤8MB（防超大图打爆 Ark）

    private final FileStorageService fileStorageService;
    private final WebClient downloadClient = buildDownloadClient();

    /**
     * 下载 Ark 视频 URL → 落 stored_files(source=MEDIA)。
     *
     * @return fileId（写入 media_gen_tasks.result_file_id）
     */
    public String downloadAndStore(String videoUrl, Long userId, String nameHint) {
        Resource resource;
        try {
            // 用 URI 对象传，跳过 WebClient 的 UriBuilderFactory 二次编码——
            // Ark/ctaigw 返回的 video_url 是预签名 TOS 链接，query 里含 %2F 等已编码字符，
            // 若用 .uri(String) 会被当 URI 模板再次编码（% → %25），破坏签名 → TOS 返 400 AccessDenied。
            resource = downloadClient.get()
                    .uri(URI.create(videoUrl))
                    .retrieve()
                    .bodyToMono(Resource.class)
                    .block(RESPONSE_TIMEOUT);
        } catch (Exception e) {
            throw new IllegalStateException("视频下载失败: " + rootMessage(e), e);
        }
        if (resource == null) {
            throw new IllegalStateException("视频下载失败：响应为空");
        }
        String fileName = deriveName(videoUrl, nameHint);
        String mime = guessVideoMime(fileName);
        long size;
        try (InputStream in = resource.getInputStream()) {
            size = in.available(); // 尽力而为；storeStream 兜底用 copied 字节数
            return fileStorageService.storeStream(in, fileName, mime, size, userId, StoredFileEntity.SOURCE_MEDIA);
        } catch (Exception e) {
            throw new IllegalStateException("视频落盘失败: " + rootMessage(e), e);
        }
    }

    /**
     * 图生视频参考图：stored_files.file_id → data URI（Ark image_url 入参）。
     * worker 系统态读取（admin=true 旁路归属，文件归属已在提交/上传时校验）。
     */
    public String readAsDataUri(String fileId, Long userId) {
        Resource resource = fileStorageService.load(fileId, userId, true);
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length > MAX_DATA_URI_BYTES) {
                throw new BusinessException(com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST,
                        "参考图过大（>8MB）");
            }
            StoredFileEntity meta = fileStorageService.findMeta(fileId);
            String mime = meta != null && meta.getMime() != null && !meta.getMime().isBlank()
                    ? meta.getMime() : "image/png";
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new IllegalStateException("读取参考图失败: " + rootMessage(e), e);
        }
    }

    private String deriveName(String url, String hint) {
        if (hint != null && !hint.isBlank()) {
            return ensureExt(hint, ".mp4");
        }
        String name = "video.mp4";
        try {
            int q = url.indexOf('?');
            String path = q >= 0 ? url.substring(0, q) : url;
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1) {
                name = path.substring(slash + 1);
            }
        } catch (Exception ignore) {
            // fallback
        }
        return name.isBlank() ? "video.mp4" : name;
    }

    private String ensureExt(String name, String defaultExt) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov")) {
            return name;
        }
        return name + defaultExt;
    }

    private String guessVideoMime(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "video/mp4";
    }

    private static WebClient buildDownloadClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 视频 buffer 上限
                .build();
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}
