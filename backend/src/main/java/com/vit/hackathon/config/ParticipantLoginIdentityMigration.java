package com.vit.hackathon.config;

import com.vit.hackathon.model.Student;
import com.vit.hackathon.repository.StudentRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Component
public class ParticipantLoginIdentityMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private final StudentRepository students;

    public ParticipantLoginIdentityMigration(JdbcTemplate jdbcTemplate, StudentRepository students) {
        this.jdbcTemplate = jdbcTemplate;
        this.students = students;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (jdbcTemplate.getDataSource() == null) return;
        String database = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName())
                .toLowerCase(Locale.ROOT);
        if (database.contains("postgresql")) {
            removeGlobalRegisterNumberConstraint();
        }

        List<Student> participants = students.findAll();
        for (Student participant : participants) {
            if (participant.getTeam() != null
                    && !normalize(participant.getLoginUsername())
                    .equals(normalize(participant.getTeam().getParticipantLoginIdentifier()))) {
                participant.setTeam(participant.getTeam());
            }
        }
        students.saveAll(participants);
    }

    private void removeGlobalRegisterNumberConstraint() {
        List<String> constraints = jdbcTemplate.queryForList("""
                SELECT constraint_row.conname
                FROM pg_constraint constraint_row
                JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                WHERE table_row.relname = 'students'
                  AND constraint_row.contype = 'u'
                  AND array_length(constraint_row.conkey, 1) = 1
                  AND EXISTS (
                      SELECT 1
                      FROM unnest(constraint_row.conkey) AS column_key(attnum)
                      JOIN pg_attribute column_row
                        ON column_row.attrelid = table_row.oid
                       AND column_row.attnum = column_key.attnum
                      WHERE column_row.attname = 'register_number'
                  )
                """, String.class);
        for (String constraint : constraints) {
            String safeName = constraint.replace("\"", "\"\"");
            jdbcTemplate.execute("ALTER TABLE students DROP CONSTRAINT \"" + safeName + "\"");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
