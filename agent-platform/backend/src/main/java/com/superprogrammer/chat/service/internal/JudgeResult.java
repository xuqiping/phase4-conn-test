package com.superprogrammer.chat.service.internal;

import java.util.List;

/** 冲突判定单条结果。 */
public record JudgeResult(int factIdx, boolean conflict, List<Long> conflictingIds, String askText) {}
