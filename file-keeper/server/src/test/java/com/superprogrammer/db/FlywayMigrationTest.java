package com.superprogrammer.db;

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

    private void assertTableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where lower(table_schema) = 'public' and lower(table_name) = ?",
                Integer.class,
                tableName
        );
        assertEquals(1, count, tableName + " table should exist");
    }
}
