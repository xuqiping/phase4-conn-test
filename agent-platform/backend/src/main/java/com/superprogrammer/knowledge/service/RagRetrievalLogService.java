package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.knowledge.dto.RagRetrievalLogVO;
import com.superprogrammer.knowledge.entity.RagRetrievalLog;
import com.superprogrammer.knowledge.mapper.RagRetrievalLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * rag_retrieval_logs 审计查询/清理（管理员，knowledge:manage）。
 * 对应 trace gap：原仅 writeTrace 只追加，无查询/清理端点。
 *
 * <p>过滤：userId/mode/时间范围 走 SQL（强类型列）；kbId 走 Java post-filter
 * （kb_ids 存 text "[1,2]"，SQL LIKE 误匹配 1↔10，故解析后精确判定，注释：仅作用于当前页）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalLogService {

    private final RagRetrievalLogMapper logMapper;

    public PageResult<RagRetrievalLogVO> page(Long userId, Long kbId, String mode,
                                              OffsetDateTime from, OffsetDateTime to,
                                              long page, long size) {
        LambdaQueryWrapper<RagRetrievalLog> w = new LambdaQueryWrapper<RagRetrievalLog>()
                .eq(userId != null, RagRetrievalLog::getUserId, userId)
                .eq(mode != null && !mode.isBlank(), RagRetrievalLog::getMode, mode)
                .ge(from != null, RagRetrievalLog::getCreatedAt, from)
                .lt(to != null, RagRetrievalLog::getCreatedAt, to)
                .orderByDesc(RagRetrievalLog::getCreatedAt);
        Page<RagRetrievalLog> p = logMapper.selectPage(new Page<>(page, size), w);

        List<RagRetrievalLogVO> records = p.getRecords().stream()
                .map(RagRetrievalLogService::toVO)
                .filter(vo -> kbId == null || kbIdSetContains(vo.getKbIds(), kbId))
                .collect(Collectors.toList());
        return PageResult.of(records, p.getTotal(), page, size);
    }

    public boolean delete(Long id) {
        return logMapper.deleteById(id) > 0;
    }

    /** 按时间清理（before 之前全删），返回删除条数。 */
    public int deleteBefore(OffsetDateTime before) {
        return logMapper.delete(new LambdaQueryWrapper<RagRetrievalLog>()
                .lt(RagRetrievalLog::getCreatedAt, before));
    }

    private static boolean kbIdSetContains(String kbIdsText, Long kbId) {
        if (kbIdsText == null || kbIdsText.isBlank()) {
            return false;
        }
        String body = kbIdsText.replaceAll("[\\[\\]\\s]", "");
        return Arrays.stream(body.split(","))
                .filter(s -> !s.isEmpty())
                .map(String::trim)
                .anyMatch(s -> s.equals(String.valueOf(kbId)));
    }

    private static RagRetrievalLogVO toVO(RagRetrievalLog l) {
        RagRetrievalLogVO vo = new RagRetrievalLogVO();
        vo.setId(l.getId());
        vo.setTraceId(l.getTraceId());
        vo.setUserId(l.getUserId());
        vo.setIdentityType(l.getIdentityType());
        vo.setKbIds(l.getKbIds());
        vo.setQuery(l.getQuery());
        vo.setMode(l.getMode());
        vo.setL2LexicalFallback(l.getL2LexicalFallback());
        vo.setCragVerdict(l.getCragVerdict());
        vo.setLatencyMs(l.getLatencyMs());
        vo.setCreatedAt(l.getCreatedAt());
        vo.setCandidatesL0(l.getCandidatesL0());
        vo.setEvidenceL2(l.getEvidenceL2());
        vo.setTokenBudget(l.getTokenBudget());
        return vo;
    }
}
