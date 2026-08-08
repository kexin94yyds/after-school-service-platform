package com.afterschool.platform.supervision;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SupervisionMapperXmlTest {

    @Test
    void scanRunMapperKeepsTraceFieldsAndSystemActionSeparate() throws Exception {
        Configuration configuration = mapperConfiguration();
        MappedStatement insertRun = configuration.getMappedStatement(
                SupervisionMapper.class.getName() + ".insertScanRun");
        MappedStatement systemAction = configuration.getMappedStatement(
                SupervisionMapper.class.getName() + ".insertSystemAction");
        Map<String, Object> runParameters = new HashMap<>();
        runParameters.put("id", "run-1");
        runParameters.put("triggerSource", "SCHEDULED");
        runParameters.put("startedAt", LocalDateTime.of(2026, 8, 8, 2, 0));
        runParameters.put("operatorUserId", null);
        Map<String, Object> actionParameters = new HashMap<>();
        actionParameters.put("schoolId", 1L);
        actionParameters.put("alertId", 2L);
        actionParameters.put("fromStatus", null);
        actionParameters.put("toStatus", "OPEN");
        actionParameters.put("actionType", "CREATE");
        actionParameters.put("comment", "定时监管扫描自动生成");

        String insertRunSql = normalize(insertRun.getBoundSql(runParameters));
        String systemActionSql = normalize(systemAction.getBoundSql(
                actionParameters));

        assertThat(insertRunSql)
                .contains("INSERT INTO supervision_scan_run")
                .contains("trigger_source")
                .contains("operator_user_id")
                .contains("'RUNNING'");
        assertThat(systemActionSql)
                .contains("INSERT INTO supervision_alert_action")
                .contains("NULL")
                .contains("'SYSTEM'");
    }

    @Test
    void scanRunListAndStaleCleanupAreBoundedAndVisible() throws Exception {
        Configuration configuration = mapperConfiguration();
        MappedStatement staleCleanup = configuration.getMappedStatement(
                SupervisionMapper.class.getName() + ".markStaleScanRunsFailed");
        MappedStatement listRuns = configuration.getMappedStatement(
                SupervisionMapper.class.getName() + ".listScanRuns");
        BoundSql staleSql = staleCleanup.getBoundSql(Map.of(
                "staleBefore", LocalDateTime.of(2026, 8, 8, 0, 0),
                "finishedAt", LocalDateTime.of(2026, 8, 8, 2, 0),
                "failureSummary", "扫描运行超时，已由后续扫描标记失败"));
        String listSql = normalize(listRuns.getBoundSql(Map.of("limit", 50)));

        assertThat(normalize(staleSql))
                .contains("UPDATE supervision_scan_run")
                .contains("status = 'RUNNING'")
                .contains("started_at < ?");
        assertThat(staleSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("finishedAt", "failureSummary", "staleBefore");
        assertThat(listSql)
                .contains("LEFT JOIN sys_user")
                .contains("ORDER BY ssr.started_at DESC, ssr.id DESC")
                .endsWith("LIMIT ?");
    }

    @Test
    void migrationUsesMySql8ConstraintsForTraceAndSingleRunningRun()
            throws Exception {
        String resource = "db/migration/V12__add_supervision_scan_runs.sql";
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(input).as("监管扫描运行迁移").isNotNull();
            String migration = new String(
                    input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(migration)
                    .contains("CREATE TABLE supervision_scan_run")
                    .contains("trigger_source IN ('MANUAL', 'SCHEDULED')")
                    .contains("running_marker TINYINT GENERATED ALWAYS AS")
                    .contains("UNIQUE KEY uk_supervision_scan_run_running")
                    .contains("MODIFY actor_id BIGINT UNSIGNED NULL")
                    .contains("'SYSTEM'")
                    .doesNotContain("&lt;");
        }
    }

    private Configuration mapperConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/supervision/SupervisionMapper.xml";
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(input).as("监管 Mapper XML").isNotNull();
            new XMLMapperBuilder(
                    input,
                    configuration,
                    resource,
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String normalize(BoundSql boundSql) {
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
