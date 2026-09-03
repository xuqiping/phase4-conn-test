package com.superprogrammer.knowledge.connector;

import java.util.List;

/**
 * C6 连接器 SPI（WP6 Step2 / spec §8.2）：外部源 → 条目枚举 + 内容下载。
 * 实现：{@link UrlSiteConnector}（URL_SITE）/ {@link WebDavConnector}（WEBDAV）/ S3Connector（S3）。
 *
 * <p>生命周期：worker（Step3）每轮同步按连接器实例化 → list() 增量枚举 → 对新增/etag 变更项
 * fetch() 拉内容 → close()。所有出站请求必须过 urlGuard（SSRF 每跳校验）+ {@link FetchLimiter}
 * （限速+总字节闸）——两防线由实现自持，调用方不重复施加。
 */
public interface KnowledgeConnectorSpi {

    /** 对应 knowledge_connectors.type。 */
    String type();

    /**
     * 枚举当前源端全量可见条目（URL 页/文档、S3 key、WebDAV path）。
     * 增量判定不在本层——worker 拿全量清单与 connector_docs 账本对 etag 差分
     * （条目量由 maxPages/maxEntries 上限兜底，S3 走 prefix 逐页）。
     */
    List<ExternalDoc> list() throws Exception;

    /** 下载单条内容（限速+字节闸内）。 */
    byte[] fetch(ExternalDoc doc) throws Exception;

    default void close() {}

    /**
     * 源端条目。externalId 统一存原始未编码路径（中文/S3 key 百分号坑）；
     * etag=内容指纹（ETag/Last-Modified/key hash），null=源端未提供（worker 视为「不可增量」按需下载）。
     */
    record ExternalDoc(String externalId, String etag, String displayName) {}
}
