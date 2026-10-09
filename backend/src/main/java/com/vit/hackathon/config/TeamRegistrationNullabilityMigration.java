package com.vit.hackathon.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class TeamRegistrationNullabilityMigration implements ApplicationRunner {
    private static final List<String> OPTIONAL_COLUMNS = List.of(
            "registration_timestamp",
            "registration_username",
            "group_leader_name",
            "group_leader_register_number",
            "member_2_name",
            "member_2_register_number",
            "member_3_name",
            "member_3_register_number",
            "member_4_name",
            "member_4_register_number",
            "programme",
            "specialization",
            "institution",
            "payment_reference_number",
            "institute_name",
            "city",
            "state"
    );

    private final JdbcTemplate jdbcTemplate;

    public TeamRegistrationNullabilityMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer missingCredentials = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM teams
                WHERE primary_contact_number IS NULL OR TRIM(primary_contact_number) = ''
                   OR primary_email IS NULL OR TRIM(primary_email) = ''
                """, Integer.class);
        if (missingCredentials != null && missingCredentials > 0) {
            throw new IllegalStateException(missingCredentials
                    + " team(s) are missing a primary contact number or primary email. "
                    + "Fill in both fields before starting the application.");
        }

        jdbcTemplate.execute("ALTER TABLE teams ALTER COLUMN primary_contact_number SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE teams ALTER COLUMN primary_email SET NOT NULL");
        for (String column : OPTIONAL_COLUMNS) {
            jdbcTemplate.execute("ALTER TABLE teams ALTER COLUMN " + column + " DROP NOT NULL");
        }
    }
}
