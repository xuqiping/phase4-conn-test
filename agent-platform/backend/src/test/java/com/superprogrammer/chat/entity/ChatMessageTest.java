package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageTest {

    @Test
    void metadataUsesJsonbTypeHandler() throws NoSuchFieldException {
        TableName tableName = ChatMessage.class.getAnnotation(TableName.class);
        assertTrue(tableName.autoResultMap());

        TableField metadataField = ChatMessage.class
                .getDeclaredField("metadata")
                .getAnnotation(TableField.class);

        assertEquals(JsonbStringTypeHandler.class, metadataField.typeHandler());
    }
}
