package com.superprogrammer.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 文件归属与生命周期登记（stored_files，V40）。
 *
 * <p>file_id（UUID+ext）作自然主键，故不继承 {@code BaseEntity}（无自增 Long id）。
 * 不用 {@code @TableLogic}：文件删除即硬删行（status 已表达生命周期，软删冗余）。
 *
 * <p>load 咽喉点 {@code FileStorageService.load(fileId, userId, admin)} 据此强校验 owner，
 * 根治 {@code GET /api/files/{id}} authenticated IDOR（Excel多Sheet导入设计 §10）。
 */
@Data
@TableName("stored_files")
public class StoredFileEntity {

    /** 来源：KB / WORKFLOW / CHAT / PREVIEW / MEDIA / CANVAS / ASSET / EDIT / REVERSE */
    public static final String SOURCE_KB = "KB";
    public static final String SOURCE_WORKFLOW = "WORKFLOW";
    public static final String SOURCE_CHAT = "CHAT";
    public static final String SOURCE_PREVIEW = "PREVIEW";
    /** 媒体生成（SeedDance 视频）产物，owner=提交用户。 */
    public static final String SOURCE_MEDIA = "MEDIA";
    /** 无限画布产物（抽帧/截取/衍生/上传图等），owner=画布归属用户。 */
    public static final String SOURCE_CANVAS = "CANVAS";
    /** 项目资产库文件（上传入库/画布入库复用），owner=入库用户。复用原 file_id 不复制文件。 */
    public static final String SOURCE_ASSET = "ASSET";
    /** 视频剪辑渲染产物（media.edit FFmpeg 多轨合成输出），owner=提交用户。 */
    public static final String SOURCE_EDIT = "EDIT";
    /** 视频反推关键帧产物（media.reverse FFmpeg 场景检测抽帧），owner=发起用户。 */
    public static final String SOURCE_REVERSE = "REVERSE";

    /** 生命周期：ACTIVE（落盘在用）/ CLEANED（已删字节）/ EXPIRED（PREVIEW 过期） */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CLEANED = "CLEANED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @TableId(type = IdType.INPUT)
    private String fileId;

    private Long tenantId;

    private Long ownerUserId;

    private Long kbId;

    private String source;

    private String status;

    private String originalName;

    private String mime;

    private Long size;

    private OffsetDateTime expiresAt;

    private OffsetDateTime createdAt;
}
