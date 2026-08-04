package com.superprogrammer.canvas.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建画布请求。name 可空（默认「未命名画布」）。
 */
@Data
public class CanvasCreateRequest {

    @Size(max = 128, message = "画布名最长 128 字符")
    private String name;
}
