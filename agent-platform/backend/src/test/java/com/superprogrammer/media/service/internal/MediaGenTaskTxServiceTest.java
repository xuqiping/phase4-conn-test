package com.superprogrammer.media.service.internal;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MediaGenTaskTxService 审计覆盖（问题修复 #1：失败全路径落审计）。
 *
 * <p>markFailed / markDownloadFailed 是失败咽喉（所有 gen 路径失败汇聚于此），
 * 断言二者均触发 recordTask(action=video_gen_fail/image_gen_fail, result=FAIL)。
 * detail 不含 OSS URL（仅 model+kind+reason）。
 *
 * <p>@BeforeAll 注册 MyBatis-Plus Lambda 缓存：纯 Mockito 测试无 Spring 启动，
 * LambdaUpdateWrapper.set(MediaGenTask::getXxx) 取 lambda→列名映射需 TableInfo 预注册，否则报
 * "can not find lambda cache"。
 */
@ExtendWith(MockitoExtension.class)
class MediaGenTaskTxServiceTest {

    @Mock private MediaGenTaskMapper taskMapper;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private MediaGenTaskTxService txService;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""), MediaGenTask.class);
    }

    @Test
    void markFailed_videoTask_auditsVideoGenFail() {
        MediaGenTask task = new MediaGenTask();
        task.setId(7L);
        task.setUserId(100L);
        task.setTaskType(MediaGenTask.TYPE_TEXT2VIDEO);
        task.setModel("doubao-seedance-2-0");
        task.setClientIp("203.0.113.9");
        when(taskMapper.selectById(7L)).thenReturn(task);

        txService.markFailed(7L, "Ark 500 内部错误");

        verify(auditLogService).recordTask(eq("media"), eq("video_gen_fail"), eq("media_gen_task"),
                eq("7"), eq(100L), isNull(), eq("203.0.113.9"), contains("doubao-seedance-2-0"),
                eq(AuditLogEntity.RESULT_FAIL));
    }

    @Test
    void markFailed_imageTask_auditsImageGenFail() {
        MediaGenTask task = new MediaGenTask();
        task.setId(9L);
        task.setUserId(200L);
        task.setTaskType(MediaGenTask.TYPE_TEXT2IMAGE);
        task.setModel("seedream-5.0");
        when(taskMapper.selectById(9L)).thenReturn(task);

        txService.markFailed(9L, "内容审核拦截");

        verify(auditLogService).recordTask(eq("media"), eq("image_gen_fail"), eq("media_gen_task"),
                eq("9"), eq(200L), isNull(), isNull(), contains("seedream-5.0"),
                eq(AuditLogEntity.RESULT_FAIL));
    }

    @Test
    void markDownloadFailed_auditsVideoGenFailWithDownloadReason() {
        MediaGenTask task = new MediaGenTask();
        task.setId(11L);
        task.setUserId(300L);
        task.setTaskType(MediaGenTask.TYPE_IMAGE2VIDEO);
        task.setModel("doubao-seedance-2-0");
        when(taskMapper.selectById(11L)).thenReturn(task);

        txService.markDownloadFailed(11L, "OSS 签名失败");

        verify(auditLogService).recordTask(eq("media"), eq("video_gen_fail"), eq("media_gen_task"),
                eq("11"), eq(300L), isNull(), isNull(), contains("download_failed"),
                eq(AuditLogEntity.RESULT_FAIL));
    }

    @Test
    void markFailed_taskNotFound_skipsAudit() {
        when(taskMapper.selectById(99L)).thenReturn(null);

        txService.markFailed(99L, "已删任务");

        verify(auditLogService, never()).recordTask(anyString(), anyString(), anyString(), anyString(),
                anyLong(), any(), any(), any(), anyString());
    }
}
