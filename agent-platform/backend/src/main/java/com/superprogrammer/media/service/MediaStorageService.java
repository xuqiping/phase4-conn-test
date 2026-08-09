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
import java.util.Map;

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
    /** 分类型 data URI 上限：图 8MB / 音频 15MB / 视频 50MB（base64 体积 ×4/3，官方参考视频上限 50MB）。
     *  package-private：MediaGenTaskService 提交侧按 meta.size 预检复用同一上限表（单一真相）。 */
    static final Map<String, Long> KIND_MAX_BYTES = Map.of(
            "image", 8L * 1024 * 1024,
            "audio", 15L * 1024 * 1024,
            "video", 50L * 1024 * 1024);
    private static final Map<String, String> KIND_LABEL = Map.of(
            "image", "参考图", "audio", "参考音频", "video", "参考视频");

    private final FileStorageService fileStorageService;
    private final WebClient downloadClient = buildDownloadClient();
    /** 图片下载专用 client：4K/组图单张可能 >16MB，buffer 抬到 64MB（视频 client 仅 16MB 不够）。 */
    private final WebClient imageDownloadClient = buildImageDownloadClient();

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
        return readAsDataUri(fileId, userId, "image");
    }

    /**
     * 下载生图 URL（Ark 24h 临时链接）→ 落 stored_files(source=MEDIA)。
     *
     * <p>与 {@link #downloadAndStore}（视频）区别：图片用 64MB buffer（4K/组图单张可能超 16MB）、
     * 按 url/name 推断图片 mime 与扩展名（.png/.jpg/.jpeg/.webp）。返回 fileId 写入 result_meta.imageFileIds。
     *
     * @param imageUrl Ark 返回的图片临时 URL
     * @param nameHint 命名提示（如 img-task-12-0）
     * @return fileId
     */
    public String downloadImageAndStore(String imageUrl, Long userId, String nameHint) {
        Resource resource;
        try {
            // URI 对象防二次编码（同 downloadAndStore，预签名链接含已编码字符）。
            resource = imageDownloadClient.get()
                    .uri(URI.create(imageUrl))
                    .retrieve()
                    .bodyToMono(Resource.class)
                    .block(RESPONSE_TIMEOUT);
        } catch (Exception e) {
            throw new IllegalStateException("图片下载失败: " + rootMessage(e), e);
        }
        if (resource == null) {
            throw new IllegalStateException("图片下载失败：响应为空");
        }
        String fileName = deriveImageName(imageUrl, nameHint);
        String mime = guessImageMime(fileName);
        long size;
        try (InputStream in = resource.getInputStream()) {
            size = in.available();
            return fileStorageService.storeStream(in, fileName, mime, size, userId, StoredFileEntity.SOURCE_MEDIA);
        } catch (Exception e) {
            throw new IllegalStateException("图片落盘失败: " + rootMessage(e), e);
        }
    }

    private String deriveImageName(String url, String hint) {
        String ext = ".png";
        try {
            int q = url.indexOf('?');
            String path = q >= 0 ? url.substring(0, q) : url;
            int slash = path.lastIndexOf('/');
            String base = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = base.lastIndexOf('.');
            if (dot >= 0 && dot < base.length() - 1) {
                String e = base.substring(dot).toLowerCase(Locale.ROOT);
                if (e.equals(".jpg") || e.equals(".jpeg") || e.equals(".png") || e.equals(".webp")) {
                    ext = e;
                }
            }
        } catch (Exception ignore) { /* fallback */ }
        return (hint != null && !hint.isBlank() ? hint : "image") + ext;
    }

    private String guessImageMime(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    /**
     * 多模态参考附件：stored_files.file_id → data URI（image_url / video_url / audio_url 入参）。
     * 按类型分别限大小（图 8MB / 音频 15MB / 视频 50MB）。
     * F2：先按落库 meta.size 预检再读流——超限文件不再全量进堆后才拒。
     */
    public String readAsDataUri(String fileId, Long userId, String kind) {
        long maxBytes = KIND_MAX_BYTES.getOrDefault(kind, MAX_DATA_URI_BYTES);
        String label = KIND_LABEL.getOrDefault(kind, "参考附件");
        StoredFileEntity meta = fileStorageService.findMeta(fileId);
        if (meta != null && meta.getSize() != null && meta.getSize() > maxBytes) {
            throw new BusinessException(com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST,
                    label + "过大（>" + (maxBytes / 1024 / 1024) + "MB）");
        }
        Resource resource = fileStorageService.load(fileId, userId, true);
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length > maxBytes) { // meta.size 缺失时的兜底（不应到达）
                throw new BusinessException(com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST,
                        label + "过大（>" + (maxBytes / 1024 / 1024) + "MB）");
            }
            String mime = meta != null && meta.getMime() != null && !meta.getMime().isBlank()
                    ? meta.getMime() : ("image".equals(kind) ? "image/png" : "application/octet-stream");
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new IllegalStateException("读取" + label + "失败: " + rootMessage(e), e);
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

    /** 图片下载 client：4K/组图单张可能 >16MB，buffer 抬到 64MB。 */
    private static WebClient buildImageDownloadClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024)) // 图片 buffer 上限（4K/组图）
                .build();
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}
