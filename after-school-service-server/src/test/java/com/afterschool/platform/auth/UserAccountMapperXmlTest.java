package com.afterschool.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class UserAccountMapperXmlTest {

    @Test
    void passwordUpdateUsesStoredPasswordAsCompareAndSetCondition()
            throws Exception {
        Configuration configuration = mapperConfiguration();

        MappedStatement statement = configuration.getMappedStatement(
                UserAccountMapper.class.getName() + ".updatePassword");
        BoundSql boundSql = statement.getBoundSql(Map.of(
                "id", 7L,
                "expectedPasswordHash", "{bcrypt}old-hash",
                "passwordHash", "{bcrypt}new-hash"));
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("SET password_hash = ?")
                .contains("WHERE id = ? AND BINARY password_hash = BINARY ? AND enabled = TRUE");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly(
                        "passwordHash",
                        "id",
                        "expectedPasswordHash");
    }

    private Configuration mapperConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/auth/UserAccountMapper.xml";
        try (InputStream input =
                getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as("账号 Mapper XML").isNotNull();
            new XMLMapperBuilder(
                            input,
                            configuration,
                            resource,
                            configuration.getSqlFragments())
                    .parse();
        }
        return configuration;
    }
}
