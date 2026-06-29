package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inspiration_notes")
public class InspirationNote extends BaseEntity {

    private Long userId;

    private String content;

    private List<String> tags;

    private String source;

    private String platformMessageId;

    private List<Long> reportConfigIds;

    private OffsetDateTime reviewedAt;
}
