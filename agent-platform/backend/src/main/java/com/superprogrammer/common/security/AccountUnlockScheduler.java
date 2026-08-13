// agent-platform/backend/src/main/java/com/superprogrammer/common/security/AccountUnlockScheduler.java
package com.superprogrammer.common.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 自动锁定到期解锁调度器（11x 加固 · P3-C10）：
 * 每分钟扫 status=LOCKED 且 locked_until 到期的账号 → 恢复 ACTIVE + 清 locked_until/ban_reason
 * + BanService.restore 删 ban 标记（用户可重新登录）。
 *
 * <p>只解「自动锁」（locked_until 非空）：人工 LOCKED（locked_until=null 的永久锁）不动。
 * 单实例部署无并发问题；整轮异常吞掉下分钟重试。</p>
 */
@Slf4j
@Component
public class AccountUnlockScheduler {

    private final UserMapper userMapper;
    private final BanService banService;

    public AccountUnlockScheduler(UserMapper userMapper, BanService banService) {
        this.userMapper = userMapper;
        this.banService = banService;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void unlockExpired() {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            List<User> expired = userMapper.selectList(new LambdaQueryWrapper<User>()
                    .select(User::getId)
                    .eq(User::getStatus, "LOCKED")
                    .eq(User::getDeleted, 0)
                    .isNotNull(User::getLockedUntil)
                    .le(User::getLockedUntil, now));
            if (expired.isEmpty()) {
                return;
            }
            for (User u : expired) {
                UpdateWrapper<User> uw = new UpdateWrapper<>();
                uw.eq("id", u.getId()).eq("status", "LOCKED")
                        .set("status", "ACTIVE")
                        .set("locked_until", null)
                        .set("ban_reason", null);
                int rows = userMapper.update(null, uw);
                if (rows > 0) {
                    banService.restore(u.getId());
                    log.warn("自动锁定到期解锁 userId={}", u.getId());
                }
            }
        } catch (Exception e) {
            log.error("自动解锁调度异常(已吞,下分钟重试) : {}", e.toString());
        }
    }
}
