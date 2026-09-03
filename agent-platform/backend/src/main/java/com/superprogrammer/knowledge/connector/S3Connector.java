package com.superprogrammer.knowledge.connector;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * C6 S3 连接器（WP6 Step2）：ListObjectsV2 prefix 枚举（etag 取对象 ETag）+ GetObject 下载。
 * S3 兼容（MinIO/OSS/COS）经 endpointOverride+pathStyle 支持；SDK 传输=url-connection
 * （pom 排除 apache/netty client 控依赖树）。限速/字节闸经 {@link FetchLimiter} 手动施加速
 * （SDK 路径不走 {@link SafeHttpFetch}）。endpoint 的 SSRF 防线与 WebDAV 同判（见工厂装配）。
 */
public class S3Connector implements KnowledgeConnectorSpi {

    private static final Set<String> ALLOWED_EXTENSIONS = UrlSiteConnector.ALLOWED_EXTENSIONS;

    private final S3Client client;
    private final String bucket;
    private final String prefix;
    private final FetchLimiter limiter;
    private final int maxEntries;

    public S3Connector(Map<String, Object> config, FetchLimiter limiter) {
        this(config, limiter, 500);
    }

    public S3Connector(Map<String, Object> config, FetchLimiter limiter, int maxEntries) {
        Object endpoint = config == null ? null : config.get("endpoint");
        Object bucketObj = config == null ? null : config.get("bucket");
        Object accessKey = config == null ? null : config.get("accessKey");
        Object secretKey = config == null ? null : config.get("secretKey");
        Object prefixObj = config == null ? null : config.get("prefix");
        Object regionObj = config == null ? null : config.get("region");
        if (!(endpoint instanceof String ep) || !(bucketObj instanceof String b) || b.isBlank()
                || !(accessKey instanceof String ak) || ak.isBlank()
                || !(secretKey instanceof String sk) || sk.isBlank()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "S3 连接器配置缺 endpoint/bucket/accessKey/secretKey");
        }
        this.bucket = b;
        String rawPrefix = prefixObj instanceof String p ? p.trim() : "";
        this.prefix = rawPrefix.isEmpty() || rawPrefix.endsWith("/") ? rawPrefix : rawPrefix + "/";
        this.limiter = limiter;
        this.maxEntries = maxEntries;
        Region region = Region.of(regionObj instanceof String r && !r.isBlank() ? r.trim() : "us-east-1");
        this.client = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())   // 显式指定：默认会 service-load 到残缺的 apache5
                .endpointOverride(URI.create(ep.trim()))
                .region(region)
                .forcePathStyle(true)   // S3 兼容自建（MinIO 等）主流 path-style
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ak, sk)))
                .build();
    }

    @Override
    public String type() {
        return "S3";
    }

    @Override
    public List<ExternalDoc> list() {
        List<ExternalDoc> docs = new ArrayList<>();
        String continuation = null;
        int rounds = 0;
        do {
            limiter.acquire();
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).maxKeys(200);
            if (continuation != null) {
                request.continuationToken(continuation);
            }
            ListObjectsV2Response response = client.listObjectsV2(request.build());
            for (S3Object object : response.contents()) {
                String key = object.key();
                if (key.endsWith("/") || !hasAllowedExtension(key)) {
                    continue;   // 目录占位 key 与非白名单对象不入列
                }
                docs.add(new ExternalDoc(key, object.eTag(), displayName(key)));
                if (docs.size() >= maxEntries) {
                    return docs;
                }
            }
            continuation = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuation != null && ++rounds < 50);   // 分页闸兜底（500/200≈3 页，50 页防异常分页环）
        return docs;
    }

    @Override
    public byte[] fetch(ExternalDoc doc) {
        limiter.acquire();
        byte[] body = client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket).key(doc.externalId()).build()).asByteArray();
        limiter.chargeBytes(body.length);
        return body;
    }

    @Override
    public void close() {
        client.close();
    }

    private static boolean hasAllowedExtension(String key) {
        int slash = key.lastIndexOf('/');
        String name = slash >= 0 ? key.substring(slash + 1) : key;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && ALLOWED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String displayName(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 && slash < key.length() - 1 ? key.substring(slash + 1) : key;
    }
}
