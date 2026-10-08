package com.vit.hackathon.config;

import com.vit.hackathon.model.ImportedTeamField;
import com.vit.hackathon.model.ImportedTeamFieldsConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ImportedTeamFieldsMigration implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(ImportedTeamFieldsMigration.class);

    private final JdbcTemplate jdbcTemplate;
    private final ImportedTeamFieldsConverter converter = new ImportedTeamFieldsConverter();

    public ImportedTeamFieldsMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!legacyTableExists()) return;

        Map<Long, List<ImportedTeamField>> fieldsByTeam = jdbcTemplate.query(
                "SELECT team_id, column_index, field_name, field_value FROM team_imported_fields ORDER BY team_id, column_order",
                resultSet -> {
                    Map<Long, List<ImportedTeamField>> fields = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        Long teamId = resultSet.getLong("team_id");
                        fields.computeIfAbsent(teamId, ignored -> new ArrayList<>()).add(new ImportedTeamField(
                                resultSet.getInt("column_index"),
                                resultSet.getString("field_name"),
                                resultSet.getString("field_value")
                        ));
                    }
                    return fields;
                }
        );

        for (Map.Entry<Long, List<ImportedTeamField>> entry : fieldsByTeam.entrySet()) {
            int updated = jdbcTemplate.update(
                    "UPDATE teams SET imported_fields = ? WHERE id = ?",
                    converter.convertToDatabaseColumn(entry.getValue()),
                    entry.getKey()
            );
            if (updated != 1) {
                throw new IllegalStateException("Cannot migrate imported fields for missing team " + entry.getKey());
            }
        }

        jdbcTemplate.execute("DROP TABLE team_imported_fields");
        logger.info("Migrated imported fields for {} teams into the teams table", fieldsByTeam.size());
    }

    private boolean legacyTableExists() {
        Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            try (ResultSet tables = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "team_imported_fields", new String[]{"TABLE"})) {
                if (tables.next()) return true;
            }
            try (ResultSet tables = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "TEAM_IMPORTED_FIELDS", new String[]{"TABLE"})) {
                return tables.next();
            }
        });
        return Boolean.TRUE.equals(exists);
    }
}
