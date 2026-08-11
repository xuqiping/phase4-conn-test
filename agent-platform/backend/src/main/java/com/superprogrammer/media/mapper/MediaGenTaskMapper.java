package com.superprogrammer.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.media.entity.MediaGenTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface MediaGenTaskMapper extends BaseMapper<MediaGenTask> {

    /**
     * 服务端历史筛选：ownership 与筛选条件在同一条 SQL 中完成，避免先查全量再在内存过滤。
     * query 已由 service 转义 LIKE 特殊字符，因此这里执行大小写不敏感的字面子串匹配。
     */
    @Select({
            "<script>",
            "SELECT * FROM media_gen_tasks",
            "<where>",
            "<if test='!admin'>user_id = #{userId}</if>",
            "<if test='query != null'>AND request_config ->> 'prompt' ILIKE CONCAT('%', #{query}, '%') ESCAPE '\\'</if>",
            "<if test='from != null'>AND created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND created_at &lt; #{to}</if>",
            "</where>",
            "ORDER BY created_at DESC",
            "LIMIT #{limit}",
            "</script>"
    })
    List<MediaGenTask> selectHistory(@Param("userId") Long userId,
                                     @Param("admin") boolean admin,
                                     @Param("query") String query,
                                     @Param("from") OffsetDateTime from,
                                     @Param("to") OffsetDateTime to,
                                     @Param("limit") int limit);

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

    /** POST 前保存脱敏请求快照；快照严禁包含原始 data URI。 */
    @Update("UPDATE media_gen_tasks SET request_config=jsonb_set(COALESCE(request_config,'{}'::jsonb), "
            + "'{providerRequestSnapshot}', #{snapshot}::jsonb, true), updated_at=#{updatedAt} WHERE id=#{taskId}")
    int saveProviderRequestSnapshot(@Param("taskId") Long taskId,
                                    @Param("snapshot") String snapshot,
                                    @Param("updatedAt") OffsetDateTime updatedAt);
}
