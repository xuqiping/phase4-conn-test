package com.superprogrammer.projectgroup.service;

import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 成员功能开关编解码与判定（17x#2，V139）：null 不限 / [] 全禁 / 白名单 / 坏 JSON 宽容回落。 */
class MemberAllowedKindsTest {

    @Test
    void parse_null与坏JSON按不限回落() {
        assertThat(MemberAllowedKinds.parse(null)).isNull();
        assertThat(MemberAllowedKinds.parse("")).isNull();
        assertThat(MemberAllowedKinds.parse("not-json")).isNull();
        assertThat(MemberAllowedKinds.parse("{\"CHAT\":1}")).isNull();   // 非数组
    }

    @Test
    void parse_过滤非法元素() {
        assertThat(MemberAllowedKinds.parse("[\"CHAT\",\"HACK\",\"VIDEO\"]"))
                .containsExactly("CHAT", "VIDEO");
        assertThat(MemberAllowedKinds.parse("[]")).isEmpty();           // 全禁≠不限
    }

    @Test
    void toJson_null与空数组语义区分() {
        assertThat(MemberAllowedKinds.toJson(null)).isNull();
        assertThat(MemberAllowedKinds.toJson(List.of())).isEqualTo("[]");
        assertThat(MemberAllowedKinds.toJson(List.of("CHAT"))).isEqualTo("[\"CHAT\"]");
    }

    @Test
    void validate_非法元素400_null放行() {
        MemberAllowedKinds.validate(null);
        MemberAllowedKinds.validate(List.of("CHAT", "VIDEO"));
        assertThatThrownBy(() -> MemberAllowedKinds.validate(List.of("HACK")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非法模块");
    }

    @Test
    void isAllowed_白名单语义() {
        assertThat(MemberAllowedKinds.isAllowed(null, "CHAT")).isTrue();            // 不限
        assertThat(MemberAllowedKinds.isAllowed("[\"CHAT\"]", "CHAT")).isTrue();    // 白名单命中
        assertThat(MemberAllowedKinds.isAllowed("[\"CHAT\"]", "VIDEO")).isFalse();  // 白名单排除
        assertThat(MemberAllowedKinds.isAllowed("[]", "CHAT")).isFalse();           // 全禁
        assertThat(MemberAllowedKinds.isAllowed("[\"CHAT\"]", null)).isTrue();      // kind 空不约束
        assertThat(MemberAllowedKinds.isAllowed("[]", "GROUP")).isTrue();           // 结算类 refType 不约束
        assertThat(MemberAllowedKinds.isAllowed("bad-json", "CHAT")).isTrue();      // 坏数据宽容
    }
}
