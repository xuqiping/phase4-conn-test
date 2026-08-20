package com.superprogrammer.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.canvas.entity.CanvasVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 画布版本 Mapper（canvas_versions，V135）。
 *
 * <p>ownership 过滤在 service 层（先 {@code CanvasService.loadOwned} 校验画布归属，
 * 再按 canvas_id 查版本），保持 BaseMapper 纯查询风格（同 CanvasMapper）。
 */
@Mapper
public interface CanvasVersionMapper extends BaseMapper<CanvasVersion> {
}
