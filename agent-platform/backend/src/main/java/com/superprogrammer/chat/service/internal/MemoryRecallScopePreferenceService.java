package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryRecallScopeRequest;
import com.superprogrammer.chat.entity.MemoryRecallScopePref;
import com.superprogrammer.chat.mapper.MemoryRecallScopePrefMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计划12 · D-7 · 召回 scope 用户偏好持久化（总体设计 §3.3 line 113「保留上次选择，新会话沿用」）。
 * <p>
 * 1:1 用户偏好（{@code user_id} UNIQUE），upsert 语义（无则插/有则改）：
 * <ul>
 *   <li>{@link #getScope}：取上次偏好 → {@link MemoryRecallScopeRequest}；无记录返 {@code null}
 *       （controller 默认 {个人}，设计 §3.3 line 113「首次无历史默认 {个人}」）。</li>
 *   <li>{@link #saveScope}：selectOne 命中 → updateById；未命中 → insert。不依赖 DB ON CONFLICT（跨库一致）。</li>
 * </ul>
 * <p>
 * <b>null 规范化</b>（对齐 DB NOT NULL DEFAULT 列）：{@code personalOn/direction/includeDeparted/projectIds}
 * 的 null 在写入前兜底默认值（{@code true/BOTH/true/[]}）；{@code relativeDays/twStart/twEnd} 保 nullable（=不限）。
 * 读取时 resolver 再 null→默认（双保险）。
 *
 * @see MemoryRecallScopePrefMapper 数据出口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRecallScopePreferenceService {

    private final MemoryRecallScopePrefMapper prefMapper;

    /**
     * 取用户上次 scope 偏好。
     *
     * @param userId 用户 id
     * @return 上次偏好（无记录 → {@code null}，controller 兜底默认 {个人}）
     */
    public MemoryRecallScopeRequest getScope(Long userId) {
        MemoryRecallScopePref pref = prefMapper.selectOne(new LambdaQueryWrapper<MemoryRecallScopePref>()
                .eq(MemoryRecallScopePref::getUserId, userId));
        if (pref == null) {
            log.debug("getScope userId={} 无历史偏好 → 默认 {{个人}}", userId);
            return null;
        }
        return toRequest(pref);
    }

    /**
     * upsert scope 偏好。
     *
     * @param userId 用户 id
     * @param req    用户勾选（可 null → 全默认 {个人}）
     */
    public void saveScope(Long userId, MemoryRecallScopeRequest req) {
        MemoryRecallScopePref existing = prefMapper.selectOne(new LambdaQueryWrapper<MemoryRecallScopePref>()
                .eq(MemoryRecallScopePref::getUserId, userId));
        if (existing == null) {
            MemoryRecallScopePref p = new MemoryRecallScopePref();
            p.setUserId(userId);
            applyRequest(p, req);
            prefMapper.insert(p);
            log.debug("saveScope insert userId={} personalOn={} projects={}",
                    userId, p.getPersonalOn(), p.getProjectIds().size());
        } else {
            applyRequest(existing, req);
            prefMapper.updateById(existing);
            log.debug("saveScope update userId={} id={}", userId, existing.getId());
        }
    }

    private MemoryRecallScopeRequest toRequest(MemoryRecallScopePref p) {
        MemoryRecallScopeRequest r = new MemoryRecallScopeRequest();
        r.setPersonalOn(p.getPersonalOn());
        r.setProjectIds(p.getProjectIds());
        r.setDirection(p.getDirection());
        r.setRelativeDays(p.getRelativeDays());
        r.setStart(p.getTwStart());
        r.setEnd(p.getTwEnd());
        r.setIncludeDeparted(p.getIncludeDeparted());
        return r;
    }

    /** 把请求字段（null 规范化）应用到 entity（insert 新建 / update 覆盖均用）。 */
    private void applyRequest(MemoryRecallScopePref p, MemoryRecallScopeRequest req) {
        if (req == null) {
            p.setPersonalOn(true);
            p.setProjectIds(List.of());
            p.setDirection("BOTH");
            p.setRelativeDays(null);
            p.setTwStart(null);
            p.setTwEnd(null);
            p.setIncludeDeparted(true);
            return;
        }
        p.setPersonalOn(req.getPersonalOn() == null || req.getPersonalOn());        // null→true
        p.setProjectIds(req.getProjectIds() == null ? List.of() : req.getProjectIds());
        p.setDirection(req.getDirection() == null ? "BOTH" : req.getDirection());  // null→BOTH
        p.setRelativeDays(req.getRelativeDays());                                   // nullable = 不限
        p.setTwStart(req.getStart());                                               // nullable = 不限
        p.setTwEnd(req.getEnd());                                                   // nullable = 不限
        p.setIncludeDeparted(req.getIncludeDeparted() == null || req.getIncludeDeparted());  // null→true
    }
}
