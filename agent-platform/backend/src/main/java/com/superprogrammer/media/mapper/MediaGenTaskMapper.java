package com.superprogrammer.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.media.entity.MediaGenTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
public interface MediaGenTaskMapper extends BaseMapper<MediaGenTask> {

    /**
     * 图片任务成功：写 result_meta（JSONB）+ tokens_cost + status_flag + 清锁。
     *
     * <p>必须用显式 {@code ::jsonb} 强转——{@code LambdaUpdateWrapper.set} 生成的 {@code SET result_meta=?}
     * 不带 typeHandler（@TableField.typeHandler 仅实体 insert/updateById 路径生效），String 直入 jsonb 列
     * 报「字段 result_meta 类型 jsonb 但表达式 character varying」。此处原样 SQL + 强转最稳（同 locked_until=NULL 一并清）。
     */
    @Update("UPDATE media_gen_tasks SET status=#{status}, result_meta=#{resultMeta}::jsonb, "
            + "tokens_cost=#{tokensCost}, status_flag=#{statusFlag}, locked_until=NULL, "
            + "updated_at=#{updatedAt} WHERE id=#{taskId}")
    int markImageSucceeded(@Param("taskId") Long taskId,
                           @Param("resultMeta") String resultMeta,
                           @Param("tokensCost") Integer tokensCost,
                           @Param("statusFlag") String statusFlag,
                           @Param("status") String status,
                           @Param("updatedAt") OffsetDateTime updatedAt);
}
