package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryProjectSetting;
import com.superprogrammer.chat.entity.MemoryProjectUserSetting;
import com.superprogrammer.chat.mapper.MemoryProjectSettingMapper;
import com.superprogrammer.chat.mapper.MemoryProjectUserSettingMapper;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 计划12 · C · gen 开关判定单测（Mockito 无 DB）。
 * 出口对齐 plan C：owner AND 会员覆写；任一关 → false（写 raw gen_done=false）；非项目读全局兜底。
 */
@ExtendWith(MockitoExtension.class)
class MemoryGenToggleServiceTest {

    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private MemoryProjectSettingMapper projectSettingMapper;
    @Mock
    private MemoryProjectUserSettingMapper userSettingMapper;

    @InjectMocks
    private MemoryGenToggleService toggleService;

    // ---------- 非项目会话：全局兜底 ----------

    @Test
    @DisplayName("非项目会话 + 全局 gen 开（默认）→ true")
    void nonProject_globalOn_true() {
        when(systemSettingService.getMemoryGenPersonalEnabled()).thenReturn(true);
        assertTrue(toggleService.resolveGenEnabled(1L, null));
    }

    @Test
    @DisplayName("非项目会话 + 全局 gen 关 → false")
    void nonProject_globalOff_false() {
        when(systemSettingService.getMemoryGenPersonalEnabled()).thenReturn(false);
        assertFalse(toggleService.resolveGenEnabled(1L, null));
    }

    // ---------- 项目会话：owner AND 会员覆写 ----------

    @Test
    @DisplayName("项目会话 + 双方均无设置行 → 默认双开 → true")
    void project_bothRowsAbsent_defaultsTrue() {
        when(projectSettingMapper.selectOne(any())).thenReturn(null);
        when(userSettingMapper.selectOne(any())).thenReturn(null);
        assertTrue(toggleService.resolveGenEnabled(1L, 100L));
    }

    @Test
    @DisplayName("项目会话 + owner 关 → false（会员覆写无效）")
    void project_ownerOff_false() {
        MemoryProjectSetting owner = new MemoryProjectSetting();
        owner.setGenEnabled(false);
        when(projectSettingMapper.selectOne(any())).thenReturn(owner);
        assertFalse(toggleService.resolveGenEnabled(1L, 100L));
    }

    @Test
    @DisplayName("项目会话 + owner 开 + 会员覆写关 → false")
    void project_memberOverrideOff_false() {
        MemoryProjectSetting owner = new MemoryProjectSetting();
        owner.setGenEnabled(true);
        MemoryProjectUserSetting member = new MemoryProjectUserSetting();
        member.setGenEnabled(false);
        when(projectSettingMapper.selectOne(any())).thenReturn(owner);
        when(userSettingMapper.selectOne(any())).thenReturn(member);
        assertFalse(toggleService.resolveGenEnabled(1L, 100L));
    }

    @Test
    @DisplayName("项目会话 + owner 开 + 会员开 → true")
    void project_bothOn_true() {
        MemoryProjectSetting owner = new MemoryProjectSetting();
        owner.setGenEnabled(true);
        MemoryProjectUserSetting member = new MemoryProjectUserSetting();
        member.setGenEnabled(true);
        when(projectSettingMapper.selectOne(any())).thenReturn(owner);
        when(userSettingMapper.selectOne(any())).thenReturn(member);
        assertTrue(toggleService.resolveGenEnabled(1L, 100L));
    }

    @Test
    @DisplayName("项目会话 + 设置行存在但 genEnabled=null → 防御默认 true")
    void project_nullFieldDefaultsTrue() {
        MemoryProjectSetting owner = new MemoryProjectSetting();
        owner.setGenEnabled(null);  // 异常数据
        MemoryProjectUserSetting member = new MemoryProjectUserSetting();
        member.setGenEnabled(null);
        when(projectSettingMapper.selectOne(any())).thenReturn(owner);
        when(userSettingMapper.selectOne(any())).thenReturn(member);
        assertTrue(toggleService.resolveGenEnabled(1L, 100L));
    }

    @Test
    @DisplayName("项目会话 + owner 无行(默认开) + 会员覆写关 → false")
    void project_ownerDefault_memberOff_false() {
        MemoryProjectUserSetting member = new MemoryProjectUserSetting();
        member.setGenEnabled(false);
        when(projectSettingMapper.selectOne(any())).thenReturn(null);
        when(userSettingMapper.selectOne(any())).thenReturn(member);
        assertFalse(toggleService.resolveGenEnabled(1L, 100L));
    }
}
