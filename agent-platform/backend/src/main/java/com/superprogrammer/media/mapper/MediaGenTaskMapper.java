package com.superprogrammer.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.media.entity.MediaGenTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface MediaGenTaskMapper extends BaseMapper<MediaGenTask> {

    /**
     * 服务端历史筛选：ownership 与筛选条件在同一条 SQL 中完成，避免先查全量再在内存过滤。
     * query 已由 service 转义 LIKE 特殊字符，因此这里执行大小写不敏感的字面子串匹配。
     *
     * <p>kind（service 已白名单校验）：IMAGE=仅图片任务（TEXT2IMAGE/IMAGE2IMAGE），
     * VIDEO=仅视频任务（TEXT2VIDEO/IMAGE2VIDEO），null=全量。kind 只进 &lt;if&gt; 等值比较
     * 不插值进 SQL，无注入面；过滤放 SQL 层（若前端先 LIMIT 再内存过滤会行数不足/仍混杂）。
     */
    @Select({
            "<script>",
            "SELECT * FROM media_gen_tasks",
            "<where>",
            "<if test='!admin'>user_id = #{userId}</if>",
            "<if test='query != null'>AND request_config ->> 'prompt' ILIKE CONCAT('%', #{query}, '%') ESCAPE '\\'</if>",
            "<if test='from != null'>AND created_at &gt;= #{from}</if>",
            "<if test='to != null'>AND created_at &lt; #{to}</if>",
            "<if test='kind == \"IMAGE\"'>AND task_type IN ('TEXT2IMAGE','IMAGE2IMAGE')</if>",
            "<if test='kind == \"VIDEO\"'>AND task_type IN ('TEXT2VIDEO','IMAGE2VIDEO')</if>",
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
                                     @Param("limit") int limit,
                                     @Param("kind") String kind);

    /**
     * 图片任务成功：写 result_meta（JSONB）+ tokens_cost + status_flag + 清锁。
     *
     * <p>必须用显式 {@code ::jsonb} 强转——{@code LambdaUpdateWrapper.set} 生成的 {@code SET result_meta=?}
     * 不带 typeHandler（@TableField.typeHandler 仅实体 insert/updateById 路径生效），String 直入 jsonb 列
     * 报「字段 result_meta 类型 jsonb 但表达式 character varying」。此处原样 SQL + 强转最稳（同 locked_until=NULL 一并清）。
     *
     * <p>C3：WHERE 带 {@code status IN ('PENDING','RUNNING')}（终态迁移守卫）——已终态行影响 0，
     * 调用方以此判断是否 release 并发闸门（防双 worker 重复 DECR 超卖）。
     */
    @Update("UPDATE media_gen_tasks SET status=#{status}, result_meta=#{resultMeta}::jsonb, "
            + "tokens_cost=#{tokensCost}, status_flag=#{statusFlag}, locked_until=NULL, "
            + "updated_at=#{updatedAt} WHERE id=#{taskId} AND status IN ('PENDING','RUNNING')")
    int markImageSucceeded(@Param("taskId") Long taskId,
                           @Param("resultMeta") String resultMeta,
                           @Param("tokensCost") Integer tokensCost,
                           @Param("statusFlag") String statusFlag,
                           @Param("status") String status,
                           @Param("updatedAt") OffsetDateTime updatedAt);

    /**
     * C3 并发对账：每用户每 taskType 的未终态任务数（V123 部分索引覆盖）。
     * 供 MediaInflightGateService.reconcile 与 Redis 在途计数比对，漂移大 → WARN。
     */
    @Select("SELECT user_id AS \"userId\", task_type AS \"taskType\", COUNT(*) AS \"cnt\" "
            + "FROM media_gen_tasks WHERE status IN ('PENDING','RUNNING') AND user_id IS NOT NULL "
            + "GROUP BY user_id, task_type")
    List<Map<String, Object>> countActiveByUserAndType();

    /** POST 前保存脱敏请求快照；快照严禁包含原始 data URI。 */
    @Update("UPDATE media_gen_tasks SET request_config=jsonb_set(COALESCE(request_config,'{}'::jsonb), "
            + "'{providerRequestSnapshot}', #{snapshot}::jsonb, true), updated_at=#{updatedAt} WHERE id=#{taskId}")
    int saveProviderRequestSnapshot(@Param("taskId") Long taskId,
                                    @Param("snapshot") String snapshot,
                                    @Param("updatedAt") OffsetDateTime updatedAt);
}
