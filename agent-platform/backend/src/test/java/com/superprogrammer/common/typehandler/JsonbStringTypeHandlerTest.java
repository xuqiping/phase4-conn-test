package com.superprogrammer.common.typehandler;

import org.junit.jupiter.api.Test;
import java.sql.PreparedStatement;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JsonbStringTypeHandlerTest {

    @Test
    void setNonNullParameterBindsJsonAsJdbcOther() throws Exception {
        JsonbStringTypeHandler handler = new JsonbStringTypeHandler();
        PreparedStatement statement = mock(PreparedStatement.class);

        handler.setNonNullParameter(statement, 1, "[{\"type\":\"NODE_STARTED\"}]", null);

        verify(statement).setObject(1, "[{\"type\":\"NODE_STARTED\"}]", Types.OTHER);
    }
}
