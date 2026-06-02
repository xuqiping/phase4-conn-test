package com.superprogrammer.engine.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VariableStoreTest {

    private VariableStore store;

    @BeforeEach
    void setUp() {
        store = new VariableStore();
    }

    @Test
    void set_andGet_shouldReturnStoredValue() {
        store.set("analysis", "用户需要代码生成");
        assertEquals("用户需要代码生成", store.get("analysis"));
    }

    @Test
    void get_nonExistentKey_shouldReturnNull() {
        assertNull(store.get("nonexistent"));
    }

    @Test
    void renderTemplate_shouldReplaceVariables() {
        store.set("input", "Hello World");
        store.set("lang", "Java");
        String result = store.renderTemplate("分析{{input}}并生成{{lang}}代码");
        assertEquals("分析Hello World并生成Java代码", result);
    }

    @Test
    void renderTemplate_missingVariable_shouldKeepOriginal() {
        String result = store.renderTemplate("分析{{missing}}内容");
        assertEquals("分析{{missing}}内容", result);
    }

    @Test
    void getAll_shouldReturnUnmodifiableCopy() {
        store.set("a", "1");
        var map = store.getAll();
        assertEquals(1, map.size());
        assertThrows(UnsupportedOperationException.class, () -> map.put("b", "2"));
        assertEquals(1, store.getAll().size());
    }
}
