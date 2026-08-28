package com.superprogrammer.media.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final long MAX_DATA_URI_BYTES = 8L * 1024 * 1024; // 未知类型兜底（已知类型走 KIND_MAX_BYTES）
    /**
     * 分类型附件上限：图 30MB / 音频 15MB / 视频 50MB。
     * 修复VI（2x#5）：image 8→30MB 对齐官方「单张 ≤30MB」，且图片附件改签名公网 URL 传输
     * （MediaReferenceUrlService，不再 base64）——上限管的是入库/引用闸门而非请求体体积。
     *  package-private：MediaGenTaskService 提交侧按 meta.size 预检复用同一上限表（单一真相）。
     */
    static final Map<String, Long> KIND_MAX_BYTES = Map.of(
            "image", 30L * 1024 * 1024,
            "audio", 15L * 1024 * 1024,
            "video", 50L * 1024 * 1024);
    private static final Map<String, String> KIND_LABEL = Map.of(
            "image", "参考图", "audio", "参考音频", "video", "参考视频");

    private final FileStorageService fileStorageService;
    private final WebClient downloadClient = buildDownloadClient();
    private final WebClient imageDownloadClient = buildImageDownloadClient();
    /** 安全体系 S5（H SSRF）：回源拒绝计数。横切可选依赖，单测无 Bean 直通。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.superprogrammer.common.metrics.BizMetrics bizMetrics;

    /**
     * 安全体系 S5 · SEC-FR-081（H SSRF）：媒体回源前校验目标 URL。
     * Ark 返回的临时链接理论可信，但 url 字段经任务结果 JSON 落库——伪造任务结果（或上游被攻破）
     * 可注入内网/云元数据 URL 借服务器回源。首跳校验后 WebClient 默认不跟随重定向
     * （Reactor Netty followRedirect 默认 false），不存在「校验首跳→重定向跳内网」绕过面。
     */
    private void assertFetchSafe(String url) {
        try {
            com.superprogrammer.common.security.SsrfGuard.validate(url);
        } catch (BusinessException e) {
            if (bizMetrics != null) {
                bizMetrics.ssrfDenied("media-fetch");
            }
            throw e;
        }
    }

    /**
     * 下载 Ark 视频 URL → 落 stored_files(source=MEDIA)。
     *
     * @return fileId（写入 media_gen_tasks.result_file_id）
     */
    public String downloadAndStore(String videoUrl, Long userId, String nameHint) {
        assertFetchSafe(videoUrl);   // S5 H SSRF：回源前校验
        // 流式落盘（2x 修复）：旧实现 bodyToMono(Resource) 整体进内存，受 maxInMemorySize 16MB 上限，
        // 15s 视频即超 → Exceeded limit on max bytes to buffer。改 DataBuffer 流 → 临时文件 → storeStream，
        // 任意时长视频只占常量内存。
        Path tmp = streamToTempFile(downloadClient, videoUrl, "视频");
        String fileName = deriveName(videoUrl, nameHint);
        String mime = guessVideoMime(fileName);
        try (InputStream in = Files.newInputStream(tmp)) {
            return fileStorageService.storeStream(in, fileName, mime, Files.size(tmp), userId, StoredFileEntity.SOURCE_MEDIA);
        } catch (Exception e) {
            throw new IllegalStateException("视频落盘失败: " + rootMessage(e), e);
        } finally {
            deleteQuietly(tmp);
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
        assertFetchSafe(imageUrl);   // S5 H SSRF：回源前校验（生图 24h 临时链接同视频路径）
        // 流式落盘（同 downloadAndStore）：4K/组图单张也可超 64MB 旧内存上限。
        Path tmp = streamToTempFile(imageDownloadClient, imageUrl, "图片");
        String fileName = deriveImageName(imageUrl, nameHint);
        String mime = guessImageMime(fileName);
        try (InputStream in = Files.newInputStream(tmp)) {
            return fileStorageService.storeStream(in, fileName, mime, Files.size(tmp), userId, StoredFileEntity.SOURCE_MEDIA);
        } catch (Exception e) {
            throw new IllegalStateException("图片落盘失败: " + rootMessage(e), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /**
     * 流式下载 URL → 临时文件（DataBuffer 逐块写盘，不经堆内存聚合，无 maxInMemorySize 上限）。
     * 用 URI 对象传，跳过 WebClient 的 UriBuilderFactory 二次编码——预签名链接 query 含已编码字符，
     * 若用 .uri(String) 会被当 URI 模板再次编码，破坏签名 → TOS 返 400 AccessDenied。
     */
    private Path streamToTempFile(WebClient client, String url, String label) {
        Path tmp;
        try {
            tmp = Files.createTempFile("media-dl-", ".bin");
        } catch (Exception e) {
            throw new IllegalStateException(label + "下载失败: " + rootMessage(e), e);
        }
        try {
            Flux<DataBuffer> body = client.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .bodyToFlux(DataBuffer.class);
            DataBufferUtils.write(body, tmp, StandardOpenOption.WRITE).block(DOWNLOAD_TIMEOUT);
            if (Files.size(tmp) == 0) {
                throw new IllegalStateException(label + "下载失败：响应为空");
            }
            return tmp;
        } catch (Exception e) {
            deleteQuietly(tmp);
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException(label + "下载失败: " + rootMessage(e), e);
        }
    }

    private void deleteQuietly(Path tmp) {
        try {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception ignore) { /* 临时文件清理失败不影响主链 */ }
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
     * 多模态参考附件：stored_files.file_id → data URI（audio_url 入参；图片/视频已改签名
     * 公网 URL 传输，修复VI 2x#5——本方法仅音频与兜底路径仍走 base64）。
     * 按类型分别限大小（图 30MB / 音频 15MB / 视频 50MB）。
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
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 仅聚合型读取生效；流式 DataBuffer 下载不经过此上限
                .build();
    }

    /** 图片下载 client：4K/组图单张可能 >16MB，buffer 抬到 64MB。 */
    private static WebClient buildImageDownloadClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024)) // 仅聚合型读取生效；流式下载不经过此上限
                .build();
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}
