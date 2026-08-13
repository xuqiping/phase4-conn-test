package com.superprogrammer.knowledge.service.internal;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 文档区域坐标；未取得可靠坐标时保持为空，禁止伪造。 */
@Data
@NoArgsConstructor
public class BoundingBox {

    private Integer page;
    private Double x;
    private Double y;
    private Double width;
    private Double height;
    private String coordinateSystem;

    @Builder
    public BoundingBox(Integer page, Double x, Double y, Double width, Double height,
                       String coordinateSystem) {
        if (page != null && page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (width != null && width < 0) {
            throw new IllegalArgumentException("width must be >= 0");
        }
        if (height != null && height < 0) {
            throw new IllegalArgumentException("height must be >= 0");
        }
        this.page = page;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.coordinateSystem = coordinateSystem;
    }
}
