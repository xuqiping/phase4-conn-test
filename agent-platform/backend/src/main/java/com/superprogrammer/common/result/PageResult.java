// agent-platform/backend/src/main/java/com/superprogrammer/common/result/PageResult.java
package com.superprogrammer.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;
    private long total;
    private long page;
    private long size;
    private long pages;

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        long pages = (total + size - 1) / size;
        return new PageResult<>(records, total, page, size, pages);
    }
}
