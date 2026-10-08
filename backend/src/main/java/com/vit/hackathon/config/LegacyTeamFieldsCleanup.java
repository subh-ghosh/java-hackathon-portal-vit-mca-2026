package com.vit.hackathon.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;

@Component
public class LegacyTeamFieldsCleanup implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(LegacyTeamFieldsCleanup.class);

    private final JdbcTemplate jdbcTemplate;

    public LegacyTeamFieldsCleanup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (tableExists("team_imported_fields")) {
            jdbcTemplate.execute("DROP TABLE team_imported_fields");
            logger.info("Removed obsolete team_imported_fields table");
        }
        if (columnExists("teams", "imported_fields")) {
            jdbcTemplate.execute("ALTER TABLE teams DROP COLUMN imported_fields");
            logger.info("Removed obsolete teams.imported_fields column");
        }
    }

    private boolean tableExists(String tableName) {
        Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            try (ResultSet tables = connection.getMetaData().getTables(
                    connection.getCatalog(), null, null, new String[]{"TABLE"})) {
                while (tables.next()) {
                    if (tableName.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(exists);
    }

    private boolean columnExists(String tableName, String columnName) {
        Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, null, null)) {
                while (columns.next()) {
                    if (tableName.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                            && columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(exists);
    }
}
