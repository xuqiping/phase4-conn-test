package com.superprogrammer.chat.service.internal;

import java.math.BigDecimal;

/** 抽取单条事实（含 block 候选名）。 */
public record ExtractedFact(String category, String key, String value,
                            BigDecimal confidence, String block) {}
