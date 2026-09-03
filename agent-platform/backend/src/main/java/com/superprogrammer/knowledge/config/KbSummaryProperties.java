package com.superprogrammer.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * C7 库级摘要 L-KB 旋钮（WP4 Step1，prefix {@code rag.global.summary}）。
 * 全部成本节流参数集中于此：出问题关 enabled 即停生成（kill switch），旧版摘要继续服务。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.global.summary")
public class KbSummaryProperties {

    /** 总开关：false=低峰任务零 LLM 调用，既有摘要只读不更新。 */
    private boolean enabled = true;

    /** 低峰窗口 cron（默认每天 04:30，避开白天检索/上传高峰）。 */
    private String cron = "0 30 4 * * *";

    /** map 每批文档 L1 数上限（控制单次 LLM 输入规模）。 */
    private int batchSize = 20;

    /** 触发阈值：库内文档数较上次生成变更 ≥ 此百分比（0-100）。 */
    private int changeThresholdPct = 10;

    /** 触发阈值：距上次生成超过此天数即重生成（容忍窗口）。 */
    private int staleDays = 7;

    /** 单库生成重试上限（map/reduce 任意环节失败计一次），超限置 ERROR 行待手动。 */
    private int maxAttempts = 3;

    /** reduce 输出 max_tokens（摘要 ≤2000 字+主题清单，给足 3000 防烂尾）。 */
    private int reduceMaxTokens = 3000;

    /** map 输出 max_tokens（每批要点浓缩）。 */
    private int mapMaxTokens = 2000;

    /** 显式摘要模型；null=网关默认对话模型。 */
    private String model;
}
