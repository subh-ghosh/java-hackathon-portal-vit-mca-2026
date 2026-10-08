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
        dropSingleColumnUniqueConstraints("teams", "name");
        if (tableExists("team_imported_fields")) {
            jdbcTemplate.execute("DROP TABLE team_imported_fields");
            logger.info("Removed obsolete team_imported_fields table");
        }
        if (columnExists("teams", "imported_fields")) {
            jdbcTemplate.execute("ALTER TABLE teams DROP COLUMN imported_fields");
            logger.info("Removed obsolete teams.imported_fields column");
        }
        if (columnExists("teams", "imported_extras")) {
            jdbcTemplate.execute("ALTER TABLE teams DROP COLUMN imported_extras");
            logger.info("Removed unused teams.imported_extras column");
        }
    }

    private void dropSingleColumnUniqueConstraints(String tableName, String columnName) {
        var constraintNames = jdbcTemplate.query("""
                SELECT tc.CONSTRAINT_NAME
                FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                  ON tc.CONSTRAINT_CATALOG = kcu.CONSTRAINT_CATALOG
                 AND tc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
                 AND tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
                 AND tc.TABLE_NAME = kcu.TABLE_NAME
                WHERE LOWER(tc.TABLE_SCHEMA) = LOWER(CURRENT_SCHEMA())
                  AND LOWER(tc.TABLE_NAME) = LOWER(?)
                  AND tc.CONSTRAINT_TYPE = 'UNIQUE'
                GROUP BY tc.CONSTRAINT_NAME
                HAVING COUNT(*) = 1 AND MAX(LOWER(kcu.COLUMN_NAME)) = LOWER(?)
                """, (result, row) -> result.getString(1), tableName, columnName);
        for (String constraintName : constraintNames) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " DROP CONSTRAINT \""
                    + constraintName.replace("\"", "\"\"") + "\"");
            logger.info("Removed unique constraint {} from {}.{} to allow duplicate team names",
                    constraintName, tableName, columnName);
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
