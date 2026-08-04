package com.superprogrammer.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.canvas.entity.Canvas;
import org.apache.ibatis.annotations.Mapper;

/**
 * 无限画布快照 Mapper（canvases，V55）。
 *
 * <p>ownership 硬过滤在 service 层用 {@code LambdaQueryWrapper.eq(Canvas::getUserId, ...)} 做，
 * 不在此写自定义 SQL（保持与 media/knowledge 一致的 BaseMapper 纯查询风格）。
 */
@Mapper
public interface CanvasMapper extends BaseMapper<Canvas> {
}
