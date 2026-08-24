package com.superprogrammer.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsCoreAuthorizationTables() {
        assertTableExists("users");
        assertTableExists("user_module_entitlements");
        assertTableExists("user_devices");
        assertTableExists("anonymous_device_trials");
        assertTableExists("system_settings");
        assertTableExists("admin_audit_logs");
    }

    @Test
    void createsWorkReportTables() {
        assertTableExists("work_logs");
        assertTableExists("work_plans");
        assertTableExists("report_templates");
        assertTableExists("report_configs");
        assertTableExists("report_push_targets");
        assertTableExists("work_reports");
        assertTableExists("push_deliveries");
    }

    @Test
    void seedsDefaultReportTemplates() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from report_templates where deleted = 0",
                Integer.class
        );
        assertEquals(3, count, "默认报告模板应为 3 套");
    }

    @Test
    void activatesPendingUsersWithoutReenablingDisabledUsers() {
        String url = "jdbc:h2:mem:flyway_v16_status;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("15")
                .load()
                .migrate();

        JdbcTemplate migrationJdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "sa", "")
        );
        migrationJdbc.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values ('pending-migrate@example.com', 'hash', 'user', 'pending_review', true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );
        migrationJdbc.update(
                "insert into users (email, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values ('disabled-stays@example.com', 'hash', 'user', 'disabled', true, false, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)"
        );

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertEquals("active", migrationJdbc.queryForObject(
                "select status from users where email = 'pending-migrate@example.com'",
                String.class
        ));
        assertEquals("disabled", migrationJdbc.queryForObject(
                "select status from users where email = 'disabled-stays@example.com'",
                String.class
        ));
    }

    private void assertTableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where lower(table_schema) = 'public' and lower(table_name) = ?",
                Integer.class,
                tableName
        );
        assertEquals(1, count, tableName + " table should exist");
    }
}
