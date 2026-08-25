package com.superprogrammer.projectgroup.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 层级额度预算公式单测（17x 未解决#1，V156）。
 * 用户拍板口径：管理 5000 → 分给 A 1000 → A 用 500 → 管理总额度剩 4500、A 剩 500 → 可分配 4000。
 */
@ExtendWith(MockitoExtension.class)
class MemberBudgetServiceTest {

    private static final long GROUP_ID = 10L;
    private static final long MANAGER = 4L;

    @Mock private ProjectGroupMemberMapper memberMapper;
    @InjectMocks private MemberBudgetService service;

    private ProjectGroupMemberEntity mgrRow(String quota, String used) {
        ProjectGroupMemberEntity m = new ProjectGroupMemberEntity();
        m.setGroupId(GROUP_ID);
        m.setUserId(MANAGER);
        m.setRole(ProjectGroupMemberEntity.ROLE_MANAGER);
        m.setQuotaLimitPoints(quota == null ? null : new BigDecimal(quota));
        m.setUsedPoints(new BigDecimal(used));
        return m;
    }

    @Test
    void 可分配_用户拍板算例() {
        ProjectGroupMemberEntity mgr = mgrRow("5000", "0");
        // A 用 500（子树已耗 500）；A 预留 = 1000−500=500
        when(memberMapper.countChildUnbounded(GROUP_ID, MANAGER, null)).thenReturn(0L);
        when(memberMapper.sumChildUsed(GROUP_ID, MANAGER)).thenReturn(new BigDecimal("500"));
        when(memberMapper.sumChildReserved(GROUP_ID, MANAGER, null)).thenReturn(new BigDecimal("500"));

        // 总额度剩 4500；可分配 = 5000 − 500 − 500 = 4000
        assertThat(service.subtreeUsed(GROUP_ID, mgr)).isEqualByComparingTo("500");
        assertThat(service.allocatable(GROUP_ID, mgr, null)).isEqualByComparingTo("4000");
    }

    @Test
    void 可分配_管理不限额返null_存在不限额下级兜底0() {
        assertThat(service.allocatable(GROUP_ID, mgrRow(null, "0"), null)).isNull();

        ProjectGroupMemberEntity mgr = mgrRow("5000", "0");
        when(memberMapper.countChildUnbounded(GROUP_ID, MANAGER, null)).thenReturn(1L);
        assertThat(service.allocatable(GROUP_ID, mgr, null)).isEqualByComparingTo("0");
    }

    @Test
    void 预算硬卡_新预留超可分配拒() {
        ProjectGroupMemberEntity mgr = mgrRow("5000", "0");
        when(memberMapper.countChildUnbounded(GROUP_ID, MANAGER, 2L)).thenReturn(0L);
        when(memberMapper.sumChildUsed(GROUP_ID, MANAGER)).thenReturn(new BigDecimal("500"));
        when(memberMapper.sumChildReserved(GROUP_ID, MANAGER, 2L)).thenReturn(new BigDecimal("500"));

        ProjectGroupMemberEntity target = new ProjectGroupMemberEntity();
        target.setUserId(2L);
        target.setUsedPoints(BigDecimal.ZERO);

        // 可分配 4000：配 4000 通、4001 拒
        assertThatCode(() -> service.requireWithinBudget(GROUP_ID, mgr, target, new BigDecimal("4000")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireWithinBudget(GROUP_ID, mgr, target, new BigDecimal("4001")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("可分配");
    }

    @Test
    void 预算硬卡_调整既有下级_可分配按排除目标口径() {
        ProjectGroupMemberEntity mgr = mgrRow("5000", "0");
        when(memberMapper.countChildUnbounded(GROUP_ID, MANAGER, 2L)).thenReturn(0L);
        when(memberMapper.sumChildUsed(GROUP_ID, MANAGER)).thenReturn(new BigDecimal("0"));
        when(memberMapper.sumChildReserved(GROUP_ID, MANAGER, 2L)).thenReturn(new BigDecimal("0"));

        // 目标旧 quota 3000：可分配按「排除目标」=5000——改成 5000 通、5001 拒
        // （旧预留无需回填：排除口径下目标本就不占可分配）
        ProjectGroupMemberEntity target = new ProjectGroupMemberEntity();
        target.setUserId(2L);
        target.setQuotaLimitPoints(new BigDecimal("3000"));
        target.setUsedPoints(BigDecimal.ZERO);
        assertThatCode(() -> service.requireWithinBudget(GROUP_ID, mgr, target, new BigDecimal("5000")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireWithinBudget(GROUP_ID, mgr, target, new BigDecimal("5001")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("可分配");

        // 目标 used 超旧 quota（调低不追溯态）：旧预留按 0 计
        target.setUsedPoints(new BigDecimal("3500"));
        assertThat(service.reservedOf(target.getQuotaLimitPoints(), target.getUsedPoints()))
                .isEqualByComparingTo("0");
    }
}
