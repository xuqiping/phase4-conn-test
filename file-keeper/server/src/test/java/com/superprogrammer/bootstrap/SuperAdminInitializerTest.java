package com.superprogrammer.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:super_admin_initializer;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "file-keeper.bootstrap.super-admin.email=admin@example.com",
        "file-keeper.bootstrap.super-admin.password=AdminPass123!"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SuperAdminInitializerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsSuperAdminWhenBootstrapPropertiesExist() {
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "select email, password_hash, role, status, email_verified from users where role = 'super_admin'"
        );

        assertEquals("admin@example.com", user.get("email"));
        assertEquals("super_admin", user.get("role"));
        assertEquals("active", user.get("status"));
        assertEquals(Boolean.TRUE, user.get("email_verified"));
        assertNotNull(user.get("password_hash"));
        assertNotEquals("AdminPass123!", user.get("password_hash"));
    }
}
