package com.superprogrammer.knowledge.service.internal;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 可见文档集（v6 §5.1/§5.2）。
 * all=true 表示身份对 KB 全量可见（admin/owner 或 KB 级 can_read）→ 召回 SQL 省略 document_id 谓词。
 * 否则 docs 为该身份可见的 doc_id 集合。
 * 不可变值对象，JSON 序列化为 Redis 缓存值。
 */
public final class VisibleDocSet {

    private final boolean all;
    private final Set<Long> docs;

    private VisibleDocSet(boolean all, Set<Long> docs) {
        this.all = all;
        this.docs = docs;
    }

    public static VisibleDocSet all() {
        return new VisibleDocSet(true, Set.of());
    }

    public static VisibleDocSet of(Set<Long> docs) {
        return new VisibleDocSet(false, docs);
    }

    public boolean isAll() {
        return all;
    }

    public Set<Long> getDocs() {
        return docs;
    }

    /** 可空保护：null → 空集，便于上游统一处理。 */
    public Set<Long> docsOrEmpty() {
        return docs == null ? new LinkedHashSet<>() : docs;
    }
}
