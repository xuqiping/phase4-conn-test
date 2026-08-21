package com.superprogrammer.feedback.dto;

import java.time.OffsetDateTime;

/**
 * FAQ 公开视图行（19x#2 脱敏关键）：<b>无 username/userId 字段</b>——
 * 不是置空，是字段不存在，模板/前端误取也拿不到提问人。
 */
public record FaqVO(Long id,
                    String title,
                    String content,
                    String answer,
                    OffsetDateTime answeredAt) {
}
