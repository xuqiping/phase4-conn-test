package com.superprogrammer.knowledge.service.internal;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 跨文档类型的精确定位协议；各解析器只填写能够可靠获取的字段。 */
@Data
@NoArgsConstructor
public class SectionLocator {

    private Integer pageStart;
    private Integer pageEnd;
    private String sheetName;
    private Integer rowStart;
    private Integer rowEnd;
    private String cellStart;
    private String cellEnd;
    private Integer readingOrder;
    private String regionType;
    private Boolean crossPage;
    private List<BoundingBox> boundingBoxes;

    @Builder
    public SectionLocator(Integer pageStart, Integer pageEnd, String sheetName,
                          Integer rowStart, Integer rowEnd, String cellStart, String cellEnd,
                          Integer readingOrder, String regionType, Boolean crossPage,
                          List<BoundingBox> boundingBoxes) {
        if (pageStart != null && pageStart < 1) {
            throw new IllegalArgumentException("pageStart must be >= 1");
        }
        if (pageEnd != null && pageEnd < 1) {
            throw new IllegalArgumentException("pageEnd must be >= 1");
        }
        if (pageStart != null && pageEnd != null && pageStart > pageEnd) {
            throw new IllegalArgumentException("pageStart must not exceed pageEnd");
        }
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.sheetName = sheetName;
        this.rowStart = rowStart;
        this.rowEnd = rowEnd;
        this.cellStart = cellStart;
        this.cellEnd = cellEnd;
        this.readingOrder = readingOrder;
        this.regionType = regionType;
        this.crossPage = crossPage;
        this.boundingBoxes = boundingBoxes;
    }
}
