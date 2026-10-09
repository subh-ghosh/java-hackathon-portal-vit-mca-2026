package com.vit.hackathon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vit.hackathon.model.ImportedTeamField;
import com.vit.hackathon.model.Problem;
import com.vit.hackathon.model.Student;
import com.vit.hackathon.model.Team;
import com.vit.hackathon.model.TeamRegistrationFields;
import com.vit.hackathon.config.LegacyTeamFieldsCleanup;
import com.vit.hackathon.security.AuthenticationAttemptLimiter;
import com.vit.hackathon.repository.AppSettingRepository;
import com.vit.hackathon.repository.ProblemRepository;
import com.vit.hackathon.repository.SubmissionRepository;
import com.vit.hackathon.repository.RoundTwoSubmissionRepository;
import com.vit.hackathon.repository.StudentRepository;
import com.vit.hackathon.repository.TeamRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hackathon-test;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class HackathonApplicationTests {
    private static final String ADMIN_PASSWORD = "test-admin-password";
    private static final String ATTENDANCE_PASSWORD = "separate-attendance-password";
    private static final String ADMIN_PASSWORD_HASH = new BCryptPasswordEncoder().encode(ADMIN_PASSWORD);

    @DynamicPropertySource
    static void adminPassword(DynamicPropertyRegistry registry) {
        registry.add("app.admin.password", () -> ADMIN_PASSWORD_HASH);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProblemRepository problems;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private StudentRepository students;

    @Autowired
    private SubmissionRepository submissions;

    @Autowired
    private RoundTwoSubmissionRepository roundTwoSubmissions;

    @Autowired
    private AppSettingRepository settings;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthenticationAttemptLimiter attemptLimiter;

    @Autowired
    private LegacyTeamFieldsCleanup legacyTeamFieldsCleanup;

    @BeforeEach
    void clearDatabase() {
        roundTwoSubmissions.deleteAllInBatch();
        submissions.deleteAllInBatch();
        students.deleteAllInBatch();
        teams.deleteAllInBatch();
        problems.deleteAllInBatch();
        settings.deleteAllInBatch();
    }

    @Test
    void bulkProblemVisibilityEnablesAndDisablesEveryProblemAndRequiresAdmin() throws Exception {
        Problem first = new Problem();
        first.setTitle("Problem One");
        first.setStatement("First problem statement");
        first.setEnabled(false);
        problems.save(first);

        Problem second = new Problem();
        second.setTitle("Problem Two");
        second.setStatement("Second problem statement");
        second.setEnabled(true);
        problems.save(second);

        mockMvc.perform(put("/api/admin/problems/enabled")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].enabled").value(true));
        org.junit.jupiter.api.Assertions.assertTrue(problems.findAll().stream().allMatch(Problem::isEnabled));

        mockMvc.perform(put("/api/admin/problems/enabled")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[1].enabled").value(false));
        org.junit.jupiter.api.Assertions.assertTrue(problems.findAll().stream().noneMatch(Problem::isEnabled));

        mockMvc.perform(put("/api/admin/problems/enabled")
                        .header("X-Admin-Password", "incorrect-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void manualTeamCreationStoresAllFieldsAndCreatesParticipantRoster() throws Exception {
        List<ImportedTeamField> fields = new ArrayList<>();
        for (int index = 0; index < 19; index++) {
            fields.add(new ImportedTeamField(index, com.vit.hackathon.model.TeamRegistrationFields.LABELS.get(index), ""));
        }

        fields.get(2).setFieldValue("TEAM LEADER");
        fields.get(1).setFieldValue("team-login");
        fields.get(3).setFieldValue("26MCA9001");
        fields.get(4).setFieldValue("TEAM MEMBER");
        fields.get(5).setFieldValue("26MCA9002");
        fields.get(10).setFieldValue("+91 98765 43210");
        fields.get(11).setFieldValue("leader@example.test");
        fields.get(12).setFieldValue("MCA");

        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields", fields))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Team 001"))
                .andExpect(jsonPath("$.importedFields.length()").value(19))
                .andExpect(jsonPath("$.students.length()").value(2))
                .andExpect(jsonPath("$.students[0].leader").value(true))
                .andExpect(jsonPath("$.students[0].email").value("leader@example.test"))
                .andExpect(jsonPath("$.students[1].registerNumber").value("26MCA9002"))
                .andExpect(jsonPath("$.groupLeaderName").value("TEAM LEADER"))
                .andExpect(jsonPath("$.groupLeaderRegisterNumber").value("26MCA9001"))
                .andExpect(jsonPath("$.member2Name").value("TEAM MEMBER"))
                .andExpect(jsonPath("$.primaryEmail").value("leader@example.test"));
        Team createdTeam = teams.findAll().get(0);
        org.junit.jupiter.api.Assertions.assertEquals("team-login", createdTeam.getRegistrationUsername());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.test\",\"contactNumber\":\"+91 98765 43210\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("No team members are checked in")));
        Student checkedInLeader = createdTeam.getStudents().get(0);
        checkedInLeader.setPresent(true);
        students.saveAndFlush(checkedInLeader);
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.test\",\"contactNumber\":\"+91 98765 43210\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedFields.length()").value(19))
                .andExpect(jsonPath("$.importedFields[1].fieldValue").value("team-login"))
                .andExpect(jsonPath("$.importedFields[10].fieldValue").value("+91 98765 43210"))
                .andExpect(jsonPath("$.importedFields[12].fieldValue").value("MCA"));
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" LEADER@example.test \",\"contactNumber\":\"+91-98765-43210\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedFields.length()").value(19))
                .andExpect(jsonPath("$.importedFields[1].fieldValue").value("team-login"))
                .andExpect(jsonPath("$.importedFields[10].fieldValue").value("+91 98765 43210"))
                .andExpect(jsonPath("$.importedFields[12].fieldValue").value("MCA"))
                .andExpect(jsonPath("$.importedFields[18].fieldValue").value(""));
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.test\",\"contactNumber\":\"9999999999\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"wrong@example.test\",\"contactNumber\":\"+91 98765 43210\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.test\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.test\",\"registerNumber\":\"26MCA9001\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.test\",\"contactNumber\":\"+91 98765 43210\","
                                + "\"googleDriveLink\":\"https://drive.google.com/file/d/shared-team\","
                                + "\"githubLink\":\"https://github.com/example/shared-team\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubLink").value("https://github.com/example/shared-team"));
        org.junit.jupiter.api.Assertions.assertEquals("TEAM LEADER",
                jdbcTemplate.queryForObject("SELECT group_leader_name FROM teams WHERE id = ?",
                        String.class, teams.findAll().get(0).getId()));
    }

    @Test
    void attendanceCoordinatorCanOnlyUpdateAttendanceAndParticipantsSeePresentMembers() throws Exception {
        Team team = createTeam("ATTENDANCE LEADER", "26MCA9901", "attendance-team");
        Student additional = new Student();
        additional.setName("ATTENDANCE MEMBER");
        additional.setRegisterNumber("26MCA9902");
        additional.setTeam(team);
        team.getStudents().add(additional);
        teams.save(team);

        mockMvc.perform(post("/api/attendance/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"not-configured-yet\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginEnabled\":true,\"startTime\":\"\",\"endTime\":\"\","
                                + "\"attendancePassword\":\"" + ATTENDANCE_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendancePasswordConfigured").value(true));
        String savedPassword = settings.findById("attendance-password").orElseThrow().getValue();
        org.junit.jupiter.api.Assertions.assertNotEquals(ATTENDANCE_PASSWORD, savedPassword);
        org.junit.jupiter.api.Assertions.assertTrue(new BCryptPasswordEncoder().matches(ATTENDANCE_PASSWORD, savedPassword));

        mockMvc.perform(get("/api/attendance/teams")
                        .header("X-Attendance-Password", "incorrect-password"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/attendance/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + ATTENDANCE_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].students.length()").value(2))
                .andExpect(jsonPath("$[0].students[0].present").value(false))
                .andExpect(jsonPath("$[0].students[1].present").value(false))
                .andExpect(jsonPath("$[0].students[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].importedFields").doesNotExist());

        mockMvc.perform(get("/api/admin/teams")
                        .header("X-Admin-Password", ATTENDANCE_PASSWORD))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("No team members are checked in")));
        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber(),
                                "googleDriveLink", "https://drive.google.com/file/d/not-checked-in",
                                "githubLink", "https://github.com/example/not-checked-in"))))
                .andExpect(status().isForbidden());

        Student presentMember = team.getStudents().get(0);
        mockMvc.perform(put("/api/attendance/students/{studentId}", presentMember.getId())
                        .header("X-Attendance-Password", ATTENDANCE_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/attendance/students/{studentId}", presentMember.getId())
                        .header("X-Attendance-Password", ATTENDANCE_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"present\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(true));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students.length()").value(1))
                .andExpect(jsonPath("$.students[0].registerNumber").value("26MCA9901"))
                .andExpect(jsonPath("$.students[0].present").value(true));

        mockMvc.perform(get("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].students.length()").value(2))
                .andExpect(jsonPath("$[0].students[0].present").value(true))
                .andExpect(jsonPath("$[0].students[1].present").value(false));

        mockMvc.perform(put("/api/attendance/students/{studentId}", presentMember.getId())
                        .header("X-Attendance-Password", ATTENDANCE_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"present\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false));
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void teamImportDoesNotRequireUsernameColumn() throws Exception {
        List<String> headers = List.of(
                TeamRegistrationFields.LABELS.get(2),
                TeamRegistrationFields.LABELS.get(3),
                TeamRegistrationFields.LABELS.get(10),
                TeamRegistrationFields.LABELS.get(11));
        List<String> row = List.of("EMAIL LOGIN LEADER", "26MCA9011", "9876543210",
                "email-login@example.test");
        List<String> rowWithoutContact = List.of("EMAIL LOGIN LEADER", "26MCA9011",
                "email-login@example.test");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(List.of(TeamRegistrationFields.LABELS.get(2),
                                TeamRegistrationFields.LABELS.get(3), TeamRegistrationFields.LABELS.get(11)),
                                rowWithoutContact))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("missing the required column")));
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());

        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(headers, row))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].registrationUsername").value(nullValue()))
                .andExpect(jsonPath("$[0].primaryEmail").value("email-login@example.test"));
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"email-login@example.test\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("No team members are checked in")));
    }

    @Test
    void onlyPrimaryContactAndEmailAreRequiredForTeamCreationAndImport() throws Exception {
        List<ImportedTeamField> fields = new ArrayList<>();
        for (int index = 0; index < TeamRegistrationFields.LABELS.size(); index++) {
            fields.add(new ImportedTeamField(index, TeamRegistrationFields.LABELS.get(index), ""));
        }
        fields.get(10).setFieldValue("9876543210");
        fields.get(11).setFieldValue("minimal-team@example.test");

        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields", fields))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students").isEmpty())
                .andExpect(jsonPath("$.groupLeaderName").value(nullValue()))
                .andExpect(jsonPath("$.groupLeaderRegisterNumber").value(nullValue()));
        Team team = teams.findAll().get(0);
        org.junit.jupiter.api.Assertions.assertNull(team.getGroupLeaderName());
        org.junit.jupiter.api.Assertions.assertNull(team.getGroupLeaderRegisterNumber());
        org.junit.jupiter.api.Assertions.assertNotNull(team.getPrimaryContactNumber());
        org.junit.jupiter.api.Assertions.assertNotNull(team.getPrimaryEmail());
        org.junit.jupiter.api.Assertions.assertEquals("NO",
                jdbcTemplate.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE LOWER(TABLE_NAME) = 'teams' "
                                + "AND LOWER(COLUMN_NAME) = 'primary_contact_number'",
                        String.class));
        org.junit.jupiter.api.Assertions.assertEquals("NO",
                jdbcTemplate.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE LOWER(TABLE_NAME) = 'teams' AND LOWER(COLUMN_NAME) = 'primary_email'",
                        String.class));

        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(
                                List.of(TeamRegistrationFields.LABELS.get(10), TeamRegistrationFields.LABELS.get(11)),
                                List.of("9876501234", "minimal-import@example.test")))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].students").isEmpty())
                .andExpect(jsonPath("$[0].groupLeaderName").value(nullValue()));
        org.junit.jupiter.api.Assertions.assertEquals(2, teams.count());
    }

    @Test
    void duplicateNamesAndUsernamesAreAllowedAndSharedCredentialsAuthenticateTheTeam() throws Exception {
        jdbcTemplate.execute("ALTER TABLE teams ADD CONSTRAINT legacy_teams_name_unique UNIQUE (name)");
        legacyTeamFieldsCleanup.run(new DefaultApplicationArguments(new String[0]));
        org.junit.jupiter.api.Assertions.assertEquals("YES",
                jdbcTemplate.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE LOWER(TABLE_NAME) = 'teams' AND LOWER(COLUMN_NAME) = 'group_leader_name'",
                        String.class));
        org.junit.jupiter.api.Assertions.assertEquals("YES",
                jdbcTemplate.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE LOWER(TABLE_NAME) = 'teams' "
                                + "AND LOWER(COLUMN_NAME) = 'group_leader_register_number'",
                        String.class));
        Team first = createTeam("SAME PERSON", "26mca9101", "shared-username@example.test");
        Team second = createTeam("SAME PERSON", "26MCA9102", "shared-username@example.test");
        first.getStudents().forEach(student -> student.setPresent(true));
        second.getStudents().forEach(student -> student.setPresent(true));
        teams.saveAllAndFlush(List.of(first, second));
        mockMvc.perform(put("/api/admin/teams/{teamId}", second.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + first.getName() + "\"}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(2,
                teams.findAll().stream().filter(team -> first.getName().equals(team.getName())).count());
        org.junit.jupiter.api.Assertions.assertEquals(2,
                teams.findAll().stream()
                        .filter(team -> "shared-username@example.test".equals(team.getRegistrationUsername()))
                        .count());
        org.junit.jupiter.api.Assertions.assertEquals("26MCA9101",
                students.findAllByRegisterNumberIgnoreCase("26mca9101").get(0).getRegisterNumber());

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + first.getPrimaryEmail()
                                + "\",\"contactNumber\":\"" + first.getPrimaryContactNumber() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(first.getName()));
        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + first.getPrimaryEmail()
                                + "\",\"contactNumber\":\"" + first.getPrimaryContactNumber() + "\","
                                + "\"googleDriveLink\":\"https://drive.google.com/file/d/email-test\","
                                + "\"githubLink\":\"https://github.com/example/email-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubLink").value("https://github.com/example/email-test"));
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + second.getPrimaryEmail()
                                + "\",\"contactNumber\":\"" + second.getPrimaryContactNumber() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students[0].registerNumber").value("26MCA9102"));

        List<ImportedTeamField> duplicateLogin = teamFields(
                "ANOTHER SAME NAME", "26MCA9103", "another-username@example.test");
        duplicateLogin.get(3).setFieldValue("26MCA9101");
        duplicateLogin.get(11).setFieldValue(first.getPrimaryEmail());
        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields",
                                duplicateLogin))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("primary email is already assigned")));
        org.junit.jupiter.api.Assertions.assertEquals(2, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(2, students.count());

        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(TeamRegistrationFields.LABELS,
                                teamRow("DUPLICATE LOGIN", "26mca9101", "shared-username@example.test")))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("primary email is already assigned")));

        List<ImportedTeamField> editedFields = new ArrayList<>(second.getImportedFields());
        editedFields.get(11).setFieldValue(first.getPrimaryEmail());
        mockMvc.perform(put("/api/admin/teams/{teamId}/imported-fields", second.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("fields", editedFields))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("primary email is already assigned")));
    }

    @Test
    void teamCreationAllowsMissingRosterFieldsAndRequiresCompleteMemberPairs() throws Exception {
        List<ImportedTeamField> missingLeader = teamFields("", "", "");
        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields", missingLeader))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students").isEmpty())
                .andExpect(jsonPath("$.groupLeaderName").value(nullValue()));

        List<ImportedTeamField> incompleteMember = teamFields("LEADER", "26MCA9201", "");
        incompleteMember.get(4).setFieldValue("MEMBER WITHOUT REGISTER");
        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields", incompleteMember))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "enter both the name and register number or leave both blank")));
        org.junit.jupiter.api.Assertions.assertEquals(1, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, students.count());
    }

    @Test
    void editingTeamRegistrationFieldsKeepsStudentAccountsAndTeamLeaderDetailsInSync() throws Exception {
        List<ImportedTeamField> fields = teamFields("ORIGINAL LEADER", "26MCA9251", "team@example.test");
        fields.get(4).setFieldValue("ORIGINAL MEMBER");
        fields.get(5).setFieldValue("26MCA9252");
        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields", fields))))
                .andExpect(status().isOk());
        Team team = teams.findAll().get(0);
        List<ImportedTeamField> edited = new ArrayList<>(team.getImportedFields());
        edited.get(2).setFieldValue("UPDATED LEADER");
        edited.get(3).setFieldValue("26mca9261");
        edited.get(4).setFieldValue("UPDATED MEMBER");
        edited.get(5).setFieldValue("26mca9262");

        mockMvc.perform(put("/api/admin/teams/{teamId}/imported-fields", team.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("fields", edited))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students.length()").value(2))
                .andExpect(jsonPath("$.groupLeaderRegisterNumber").value("26MCA9261"));
        org.junit.jupiter.api.Assertions.assertTrue(students.findAllByRegisterNumberIgnoreCase("26MCA9251").isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(students.findAllByRegisterNumberIgnoreCase("26MCA9252").isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals("UPDATED LEADER",
                students.findAllByRegisterNumberIgnoreCase("26MCA9261").get(0).getName());
        org.junit.jupiter.api.Assertions.assertEquals("UPDATED MEMBER",
                students.findAllByRegisterNumberIgnoreCase("26MCA9262").get(0).getName());
        org.junit.jupiter.api.Assertions.assertEquals("26MCA9261",
                teams.findById(team.getId()).orElseThrow().getGroupLeaderRegisterNumber());
    }

    @Test
    void leaderCannotBeDeletedUntilReplacementIsAssignedAndRosterFieldsFollowNewLeader() throws Exception {
        List<ImportedTeamField> fields = teamFields("ORIGINAL LEADER", "26MCA9271", "team@example.test");
        fields.get(4).setFieldValue("NEW LEADER");
        fields.get(5).setFieldValue("26MCA9272");
        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields", fields))))
                .andExpect(status().isOk());
        Team team = teams.findAll().get(0);
        Student oldLeader = students.findAllByRegisterNumberIgnoreCase("26MCA9271").get(0);
        Student replacement = students.findAllByRegisterNumberIgnoreCase("26MCA9272").get(0);

        mockMvc.perform(delete("/api/admin/students/{studentId}", oldLeader.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/admin/students/{studentId}/leader", replacement.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupLeaderRegisterNumber").value("26MCA9272"))
                .andExpect(jsonPath("$.member2RegisterNumber").value("26MCA9271"));
        mockMvc.perform(delete("/api/admin/students/{studentId}", oldLeader.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        Team updated = teams.findById(team.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(1, updated.getStudents().size());
        org.junit.jupiter.api.Assertions.assertNull(updated.getMember2RegisterNumber());
        org.junit.jupiter.api.Assertions.assertTrue(students.findById(oldLeader.getId()).isEmpty());
    }

    @Test
    void questionImportRejectsInvalidLaterRowsWithoutSavingEarlierRows() throws Exception {
        MockMultipartFile questions = new MockMultipartFile("file", "questions.csv", "text/csv",
                "title,statement\nGood question,This row is valid\nMissing statement,\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/problems/import")
                        .file(questions)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("CSV row 3")));
        org.junit.jupiter.api.Assertions.assertEquals(0, problems.count());
    }

    @Test
    void questionCsvImportSupportsQuotedMultilineFieldsAndRejectsWrongWidthRows() throws Exception {
        MockMultipartFile validQuestions = new MockMultipartFile("file", "questions.csv", "text/csv",
                "title,statement\n\"Green, campus\",\"Line one\nLine two\"\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/problems/import")
                        .file(validQuestions)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Green, campus"))
                .andExpect(jsonPath("$[0].statement").value("Line one\nLine two"));

        MockMultipartFile wrongWidth = new MockMultipartFile("file", "questions.csv", "text/csv",
                "title,statement\nAnother question\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/problems/import")
                        .file(wrongWidth)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("row 2 has 1 columns")));

        MockMultipartFile duplicateHeaders = new MockMultipartFile("file", "questions.csv", "text/csv",
                "title,title,statement\nA,B,Question\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/problems/import")
                        .file(duplicateHeaders)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("duplicate column names")));

        MockMultipartFile malformedCsv = new MockMultipartFile("file", "questions.csv", "text/csv",
                "title,statement\n\"Unclosed,Question\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/problems/import")
                        .file(malformedCsv)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("unterminated quoted field")));
        org.junit.jupiter.api.Assertions.assertEquals(1, problems.count());
    }

    @Test
    void teamImportSupportsMultilineCellsAndTreatsMissingOptionalTrailingCellsAsBlank() throws Exception {
        List<String> row = teamRow("MULTILINE LEADER", "26MCA9281", "team@example.test");
        row.set(14, "First campus line\nSecond campus line");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUploadRows(TeamRegistrationFields.LABELS, List.of(row)))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].institution").value("First campus line\nSecond campus line"));

        List<String> shortenedRow = new ArrayList<>(teamRow("SHORT ROW LEADER", "26MCA9282", "team@example.test"));
        shortenedRow.remove(shortenedRow.size() - 1);
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUploadRows(TeamRegistrationFields.LABELS, List.of(shortenedRow)))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value(nullValue()));
        org.junit.jupiter.api.Assertions.assertEquals(2, teams.count());
    }

    @Test
    void participantWithMissingTeamGetsActionableConflictAndUnknownAdminIdsReturnNotFound() throws Exception {
        Student orphan = new Student();
        orphan.setName("ORPHAN PARTICIPANT");
        orphan.setRegisterNumber("26MCA9283");
        orphan.setLoginUsername("orphan-team");
        students.saveAndFlush(orphan);

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"orphan-team@example.test\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/admin/problems/{problemId}", 99999)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Problem not found"));
        mockMvc.perform(put("/api/admin/teams/{teamId}/problem/{problemId}", 99999, 99999)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Team not found"));
        Team team = createTeam("REFRESH TEAM LEADER", "26MCA9284", "refresh@example.test");
        mockMvc.perform(get("/api/admin/teams/{teamId}", team.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(team.getId()));
        mockMvc.perform(get("/api/admin/teams/{teamId}", 99999)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/admin/teams/{teamId}", 99999)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Team not found"));
    }

    @Test
    void nullJsonBodiesReturnClientErrorsInsteadOfServerErrors() throws Exception {
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/problems")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corsAllowsConfiguredPortalOriginsAndRejectsUntrustedOrigins() throws Exception {
        mockMvc.perform(options("/api/public/config")
                        .header("Origin", "https://vit-hackathon-portal.pages.dev")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin",
                        "https://vit-hackathon-portal.pages.dev"));

        mockMvc.perform(options("/api/attendance/students/1")
                        .header("Origin", "https://vit-hackathon-portal.pages.dev")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "content-type,x-attendance-password"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsString("x-attendance-password")));

        mockMvc.perform(options("/api/public/config")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void failedParticipantAttemptsAreThrottledAndSuccessfulAuthenticationClearsTheWindow() {
        String username = "limiter-test-team";
        String registerNumber = "26MCA9920";
        for (int failure = 1; failure < 20; failure++) {
            attemptLimiter.participantFailed(username, registerNumber);
        }
        org.springframework.web.server.ResponseStatusException threshold =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> attemptLimiter.participantFailed(username, registerNumber));
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.TOO_MANY_REQUESTS, threshold.getStatusCode());
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> attemptLimiter.checkParticipant(username, registerNumber));

        attemptLimiter.participantSucceeded(username, registerNumber);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> attemptLimiter.checkParticipant(username, registerNumber));
    }

    @Test
    void adminAttemptBucketsAreIsolatedByClientIdentity() {
        for (int failure = 1; failure < 30; failure++) {
            attemptLimiter.adminFailed("admin-limiter-test-client-a");
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> attemptLimiter.adminFailed("admin-limiter-test-client-a"));
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> attemptLimiter.checkAdmin("admin-limiter-test-client-a"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> attemptLimiter.checkAdmin("admin-limiter-test-client-b"));
    }
    @Test
    void teamImportMapsReorderedHeadersIgnoresExtraColumnsAndAllowsMissingUsername() throws Exception {
        List<String> canonicalHeaders = TeamRegistrationFields.LABELS;
        List<String> headers = new ArrayList<>(canonicalHeaders);
        List<String> row = teamRow("FLEXIBLE IMPORT LEADER", "26MCA9010", "flexible-import");
        java.util.Collections.swap(headers, 1, 3);
        java.util.Collections.swap(row, 1, 3);
        headers.add(2, "Unused administrative notes");
        row.add(2, "ignore this");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(headers, row))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupLeaderName").value("FLEXIBLE IMPORT LEADER"))
                .andExpect(jsonPath("$[0].groupLeaderRegisterNumber").value("26MCA9010"))
                .andExpect(jsonPath("$[0].registrationUsername").value("flexible-import"))
                .andExpect(jsonPath("$[0].importedFields.length()").value(19));

        List<String> missingUsernameHeader = new ArrayList<>(canonicalHeaders);
        List<String> missingUsernameRow = teamRow("MISSING USERNAME LEADER", "26MCA9011", "unused");
        missingUsernameHeader.remove(1);
        missingUsernameRow.remove(1);
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(missingUsernameHeader, missingUsernameRow))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].registrationUsername").value(nullValue()));

        List<String> missingEmailHeader = new ArrayList<>(canonicalHeaders);
        List<String> missingEmailRow = teamRow("MISSING EMAIL LEADER", "26MCA9012", "unused");
        missingEmailHeader.remove(11);
        missingEmailRow.remove(11);
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(missingEmailHeader, missingEmailRow))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Primary Email Id")));

        org.junit.jupiter.api.Assertions.assertEquals(2, teams.count());
    }

    private MockMultipartFile csvUpload(List<String> headers, List<String> row) {
        return csvUploadRows(headers, List.of(row));
    }

    private MockMultipartFile csvUploadRows(List<String> headers, List<List<String>> rows) {
        String csv = csvLine(headers) + "\n" + rows.stream()
                .map(this::csvLine)
                .collect(java.util.stream.Collectors.joining("\n"));
        return new MockMultipartFile("file", "teams.csv", "text/csv",
                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void roundTwoReusesRoundOneProblemAndSharesAttendanceWithRoundOne() throws Exception {
        Team team = createTeam("ROUND TWO LEADER", "26MCA9981", "round-two@example.test");
        Student presentMember = team.getStudents().get(0);
        presentMember.setPresent(true);
        students.saveAndFlush(presentMember);

        Problem roundOneProblem = new Problem();
        roundOneProblem.setTitle("Round 1 problem");
        roundOneProblem.setStatement("Round 1 challenge statement");
        roundOneProblem.setEnabled(true);
        roundOneProblem = problems.saveAndFlush(roundOneProblem);

        mockMvc.perform(put("/api/admin/teams/{teamId}/problem/{problemId}", team.getId(), roundOneProblem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        String roundOneDrive = "https://drive.google.com/round-one";
        String roundOneGithub = "https://github.com/example/round-one";
        String roundTwoDrive = "https://drive.google.com/round-two";
        String roundTwoGithub = "https://github.com/example/round-two";
        String credentialsAndRoundOneLinks = new ObjectMapper().writeValueAsString(Map.of(
                "email", team.getPrimaryEmail(),
                "contactNumber", team.getPrimaryContactNumber(),
                "googleDriveLink", roundOneDrive,
                "githubLink", roundOneGithub));
        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsAndRoundOneLinks))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/round-two/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"deadline\":\"2099-10-10T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.published").value(true));
        mockMvc.perform(put("/api/admin/teams/{teamId}/round-two/qualification", team.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"advanced\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundTwoStatus").value("advanced"));
        presentMember.setPresent(false);
        students.saveAndFlush(presentMember);

        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsAndRoundOneLinks))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Round 1 is not currently active")));

        String roundTwoLinks = new ObjectMapper().writeValueAsString(Map.of(
                "email", team.getPrimaryEmail(),
                "contactNumber", team.getPrimaryContactNumber(),
                "googleDriveLink", roundTwoDrive,
                "githubLink", roundTwoGithub));
        mockMvc.perform(put("/api/student/round-two/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roundTwoLinks))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("No team members are checked in")));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("No team members are checked in")));

        settings.save(new com.vit.hackathon.model.AppSetting("login-enabled", "false"));
        presentMember.setPresent(true);
        students.saveAndFlush(presentMember);
        mockMvc.perform(put("/api/student/round-two/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roundTwoLinks))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleDriveLink").value(roundTwoDrive));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problem.id").value(roundOneProblem.getId()))
                .andExpect(jsonPath("$.submission.googleDriveLink").value(roundOneDrive))
                .andExpect(jsonPath("$.students.length()").value(1))
                .andExpect(jsonPath("$.students[0].present").value(true))
                .andExpect(jsonPath("$.roundTwoProblem.id").value(roundOneProblem.getId()))
                .andExpect(jsonPath("$.roundTwoSubmission.googleDriveLink").value(roundTwoDrive))
                .andExpect(jsonPath("$.roundTwoStatus").value("advanced"))
                .andExpect(jsonPath("$.roundTwoOpen").value(true))
                .andExpect(jsonPath("$.roundTwoPublished").value(true));
        mockMvc.perform(get("/api/admin/round-two/submissions")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].googleDriveLink").value(roundTwoDrive));
        mockMvc.perform(get("/api/admin/round-two/submissions/export"))
                .andExpect(status().isBadRequest());
        byte[] exportedRoundTwoWorkbook = mockMvc.perform(get("/api/admin/round-two/submissions/export")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("hackathon-round-two-submissions.xlsx")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        try (Workbook exportedFile = WorkbookFactory.create(new ByteArrayInputStream(exportedRoundTwoWorkbook))) {
            Row submissionRow = exportedFile.getSheet("Round 2 Submissions").getRow(1);
            org.junit.jupiter.api.Assertions.assertEquals("Team 001",
                    submissionRow.getCell(1).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals(roundTwoDrive,
                    submissionRow.getCell(4).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals(roundTwoGithub,
                    submissionRow.getCell(5).getStringCellValue());
        }
        mockMvc.perform(delete("/api/admin/round-two/submissions"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/round-two/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"deadline\":\"2099-10-10T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));
        mockMvc.perform(put("/api/student/round-two/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roundTwoLinks))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Participant login is currently disabled")));

        org.junit.jupiter.api.Assertions.assertEquals(roundOneDrive,
                submissions.findByTeamId(team.getId()).orElseThrow().getGoogleDriveLink());
        org.junit.jupiter.api.Assertions.assertEquals(roundOneGithub,
                submissions.findByTeamId(team.getId()).orElseThrow().getGithubLink());
        org.junit.jupiter.api.Assertions.assertEquals(roundTwoDrive,
                roundTwoSubmissions.findByTeamId(team.getId()).orElseThrow().getGoogleDriveLink());
        org.junit.jupiter.api.Assertions.assertEquals(roundOneProblem.getId(),
                teams.findById(team.getId()).orElseThrow().getProblem().getId());
        mockMvc.perform(delete("/api/admin/round-two/submissions")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(roundTwoSubmissions.findAll().isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(roundOneDrive,
                submissions.findByTeamId(team.getId()).orElseThrow().getGoogleDriveLink());
    }

    @Test
    void onlyAdvancedTeamsCanLoginDuringRoundTwoAndRoundAccessIsExclusive() throws Exception {
        Team team = createTeam("ROUND TWO PENDING", "26MCA9982", "round-two-pending@example.test");
        Student member = team.getStudents().get(0);
        member.setPresent(true);
        students.saveAndFlush(member);
        settings.save(new com.vit.hackathon.model.AppSetting("login-enabled", "false"));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String roundTwoStart = now.minusMinutes(1).toString();
        String roundTwoEnd = now.plusHours(1).toString();
        mockMvc.perform(put("/api/admin/round-access/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeRound\":\"2\",\"startTime\":\"\",\"endTime\":\"\","
                                + "\"roundTwoStartTime\":\"" + roundTwoStart + "\","
                                + "\"roundTwoEndTime\":\"" + roundTwoEnd + "\","
                                + "\"attendancePassword\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.activeRound").value("2"))
                .andExpect(jsonPath("$.roundTwoStartTime").exists())
                .andExpect(jsonPath("$.roundTwoEndTime").exists());

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This team has not advanced to Round 2"));

        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber(),
                                "googleDriveLink", "https://drive.google.com/round-one",
                                "githubLink", "https://github.com/example/round-one"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Round 1 is not currently active"));

        mockMvc.perform(put("/api/admin/teams/{teamId}/round-two/qualification", team.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"advanced\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundTwoOpen").value(true))
                .andExpect(jsonPath("$.advancedToRoundTwo").value(true))
                .andExpect(jsonPath("$.roundTwoDeadline").exists());

        String futureRoundTwoStart = now.plusHours(1).toString();
        String futureRoundTwoEnd = now.plusHours(2).toString();
        mockMvc.perform(put("/api/admin/round-access/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeRound\":\"2\",\"startTime\":\"\",\"endTime\":\"\","
                                + "\"roundTwoStartTime\":\"" + futureRoundTwoStart + "\","
                                + "\"roundTwoEndTime\":\"" + futureRoundTwoEnd + "\","
                                + "\"attendancePassword\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Round 2 submissions are currently closed"));

        String endedRoundTwoStart = now.minusHours(2).toString();
        String endedRoundTwoEnd = now.minusHours(1).toString();
        mockMvc.perform(put("/api/admin/round-access/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeRound\":\"2\",\"startTime\":\"\",\"endTime\":\"\","
                                + "\"roundTwoStartTime\":\"" + endedRoundTwoStart + "\","
                                + "\"roundTwoEndTime\":\"" + endedRoundTwoEnd + "\","
                                + "\"attendancePassword\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Round 2 submissions are currently closed"));

        mockMvc.perform(put("/api/admin/round-access/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeRound\":\"2\",\"startTime\":\"\",\"endTime\":\"\","
                                + "\"roundTwoStartTime\":\"" + futureRoundTwoEnd + "\","
                                + "\"roundTwoEndTime\":\"" + futureRoundTwoStart + "\","
                                + "\"attendancePassword\":\"\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/round-access/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeRound\":\"1\",\"startTime\":\"\",\"endTime\":\"\","
                                + "\"roundTwoStartTime\":\"" + roundTwoStart + "\","
                                + "\"roundTwoEndTime\":\"" + roundTwoEnd + "\","
                                + "\"attendancePassword\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRound").value("1"))
                .andExpect(jsonPath("$.open").value(false));
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", team.getPrimaryEmail(),
                                "contactNumber", team.getPrimaryContactNumber()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundTwoOpen").value(false));
        mockMvc.perform(put("/api/student/round-two/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + team.getPrimaryEmail() + "\","
                                + "\"contactNumber\":\"" + team.getPrimaryContactNumber() + "\","
                                + "\"googleDriveLink\":\"https://drive.google.com/round-two\","
                                + "\"githubLink\":\"https://github.com/example/round-two\"}"))
                .andExpect(status().isForbidden());
    }

    private List<ImportedTeamField> teamFields(String leaderName, String leaderRegister, String username) {
        List<ImportedTeamField> fields = new ArrayList<>();
        for (int index = 0; index < TeamRegistrationFields.LABELS.size(); index++) {
            fields.add(new ImportedTeamField(index, TeamRegistrationFields.LABELS.get(index), ""));
        }
        fields.get(1).setFieldValue(username);
        fields.get(2).setFieldValue(leaderName);
        fields.get(3).setFieldValue(leaderRegister);
        String normalizedRegister = leaderRegister == null ? "unknown"
                : leaderRegister.trim().toLowerCase(java.util.Locale.ROOT);
        String contactSuffix = String.format("%05d",
                Math.floorMod(normalizedRegister.hashCode(), 100_000));
        fields.get(10).setFieldValue("98765" + contactSuffix);
        fields.get(11).setFieldValue("team-" + normalizedRegister + "@example.test");
        return fields;
    }

    private List<String> teamRow(String leaderName, String leaderRegister, String username) {
        return teamFields(leaderName, leaderRegister, username).stream()
                .map(ImportedTeamField::getFieldValue)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private Team createTeam(String leaderName, String leaderRegister, String username) throws Exception {
        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields",
                                teamFields(leaderName, leaderRegister, username)))))
                .andExpect(status().isOk());
        String normalizedRegister = leaderRegister.trim().toUpperCase(java.util.Locale.ROOT);
        return teams.findAll().stream()
                .filter(team -> team.getStudents().stream()
                        .anyMatch(student -> normalizedRegister.equals(student.getRegisterNumber())))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void deletedTeamCredentialsCanNoLongerAuthenticate() throws Exception {
        Team team = createTeam("DELETED TEAM LEADER", "26MCA9199", "deleted-team@example.test");
        team.getStudents().forEach(student -> student.setPresent(true));
        teams.saveAndFlush(team);
        String credentials = "{\"email\":\"team-26mca9199@example.test\",\"contactNumber\":\""
                + team.getPrimaryContactNumber() + "\"}";

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/teams/{teamId}", team.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTeamImportReturnsSpecificErrorAndDoesNotSaveAnyRows() throws Exception {
        List<String> validRow = new ArrayList<>(java.util.Collections.nCopies(19, ""));
        validRow.set(1, "test-user@example.test");
        validRow.set(2, "TEST LEADER");
        validRow.set(3, "26MCA9001");
        validRow.set(10, "9876543210");
        validRow.set(11, "test-leader@example.test");
        List<String> missingLeaderName = new ArrayList<>(validRow);
        missingLeaderName.set(2, "");
        missingLeaderName.set(10, "9876543211");
        missingLeaderName.set(11, "missing-name@example.test");

        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUploadRows(TeamRegistrationFields.LABELS, List.of(validRow, missingLeaderName)))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(
                                "For team member 1 on CSV row 3, enter both the name and register number")));
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, students.count());

        List<String> renamedHeaders = new ArrayList<>(TeamRegistrationFields.LABELS);
        renamedHeaders.set(0, "Submitted at");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(renamedHeaders, validRow))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].registrationTimestamp").value(nullValue()));
    }

    @Test
    void teamImportRejectsDuplicateTeamEmailsAndAllowsDistinctEmails() throws Exception {
        List<String> firstRow = teamRow("SAME PERSON", "26MCA9301", "shared-user@example.test");
        List<String> secondRow = teamRow("SAME PERSON", "26mca9301", "shared-user@example.test");
        firstRow.set(10, "9876543210");
        secondRow.set(10, "+98 7654 3210");

        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUploadRows(TeamRegistrationFields.LABELS, List.of(firstRow, secondRow)))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("primary email appears more than once"),
                        org.hamcrest.Matchers.containsString("CSV rows 2 and 3"),
                        org.hamcrest.Matchers.containsString("unique email"))));
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, students.count());

        List<String> thirdRow = teamRow("SAME PERSON", "26MCA9302", "shared-user@example.test");
        thirdRow.set(11, "distinct-user@example.test");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUploadRows(TeamRegistrationFields.LABELS, List.of(firstRow, thirdRow)))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        org.junit.jupiter.api.Assertions.assertEquals(2, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(2, students.count());

        List<String> sameRegisterDifferentEmail =
                teamRow("SAME REGISTER NUMBER", "26MCA9301", "different-user@example.test");
        sameRegisterDifferentEmail.set(11, "different-team@example.test");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(TeamRegistrationFields.LABELS, sameRegisterDifferentEmail))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].students[0].registerNumber").value("26MCA9301"));
        org.junit.jupiter.api.Assertions.assertEquals(3, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(3, students.count());
    }

    @Test
    void teamImportRejectsDuplicateContactNumbersWithinUploadAndAgainstExistingTeams() throws Exception {
        List<String> firstRow = teamRow("FIRST TEAM", "26MCA9351", "first-team@example.test");
        List<String> secondRow = teamRow("SECOND TEAM", "26MCA9352", "second-team@example.test");
        firstRow.set(10, "9876543210");
        secondRow.set(10, "+98 7654 3210");

        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUploadRows(TeamRegistrationFields.LABELS, List.of(firstRow, secondRow)))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("contact number appears more than once"),
                        org.hamcrest.Matchers.containsString("CSV rows 2 and 3"),
                        org.hamcrest.Matchers.containsString("unique contact number"))));
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());

        firstRow.set(10, "9876543210");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(TeamRegistrationFields.LABELS, firstRow))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());

        secondRow.set(10, "98-7654-3210");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(TeamRegistrationFields.LABELS, secondRow))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("contact number is already assigned"),
                        org.hamcrest.Matchers.containsString("CSV row 2"))));
        org.junit.jupiter.api.Assertions.assertEquals(1, teams.count());
    }

    @Test
    void manualTeamCreationAndRegistrationEditsRequireUniqueEmailAndContactNumber() throws Exception {
        Team firstTeam = createTeam("FIRST TEAM", "26MCA9361", "first-team@example.test");
        List<ImportedTeamField> fields = teamFields("SECOND TEAM", "26MCA9362", "second-team");
        fields.get(10).setFieldValue("+" + firstTeam.getPrimaryContactNumber());
        fields.get(11).setFieldValue("second-team@example.test");
        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields", fields))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("unique contact number")));
        org.junit.jupiter.api.Assertions.assertEquals(1, teams.count());

        fields.get(10).setFieldValue("9876543999");
        fields.get(11).setFieldValue(firstTeam.getPrimaryEmail().toUpperCase(java.util.Locale.ROOT));
        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields", fields))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("unique email")));
        org.junit.jupiter.api.Assertions.assertEquals(1, teams.count());

        Team secondTeam = createTeam("SECOND TEAM", "26MCA9362", "second-team@example.test");
        List<ImportedTeamField> updatedFields = secondTeam.getImportedFields();
        updatedFields.get(10).setFieldValue("+" + firstTeam.getPrimaryContactNumber());
        mockMvc.perform(put("/api/admin/teams/{teamId}/imported-fields", secondTeam.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("fields", updatedFields))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("unique contact number")));

        updatedFields.get(10).setFieldValue(secondTeam.getPrimaryContactNumber());
        updatedFields.get(11).setFieldValue(firstTeam.getPrimaryEmail().toUpperCase(java.util.Locale.ROOT));
        mockMvc.perform(put("/api/admin/teams/{teamId}/imported-fields", secondTeam.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("fields", updatedFields))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("unique email")));
        org.junit.jupiter.api.Assertions.assertEquals(2, teams.count());

        updatedFields.get(10).setFieldValue(secondTeam.getPrimaryContactNumber());
        updatedFields.get(11).setFieldValue(secondTeam.getPrimaryEmail());
        mockMvc.perform(put("/api/admin/teams/{teamId}/imported-fields", secondTeam.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("fields", updatedFields))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(secondTeam.getId()));
    }

    @Test
    void teamImportAllowsRepeatedRegistersButRejectsIncompleteMemberPairs() throws Exception {
        List<String> repeatedWithinTeam = teamRow("LEADER", "2503730000000000", "same-user@example.test");
        repeatedWithinTeam.set(4, "JANANI P");
        repeatedWithinTeam.set(5, "2.50E+15");
        repeatedWithinTeam.set(6, "INDHUJA V");
        repeatedWithinTeam.set(7, "2503730424322050X");
        repeatedWithinTeam.set(8, "DIWAN P");
        repeatedWithinTeam.set(9, "2.50E+15");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(TeamRegistrationFields.LABELS, repeatedWithinTeam))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].students.length()").value(4))
                .andExpect(jsonPath("$[0].students[1].registerNumber").value("2.50E+15"))
                .andExpect(jsonPath("$[0].students[3].registerNumber").value("2.50E+15"));
        org.junit.jupiter.api.Assertions.assertEquals(1, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(4, students.count());

        List<String> missingRegister = teamRow("LEADER", "26MCA9402", "another-user@example.test");
        missingRegister.set(10, "9876543211");
        missingRegister.set(4, "MEMBER WITHOUT REGISTER");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(TeamRegistrationFields.LABELS, missingRegister))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "enter both the name and register number or leave both blank")));
        org.junit.jupiter.api.Assertions.assertEquals(1, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(4, students.count());
    }

    @Test
    void obsoleteImportedFieldStorageIsRemoved() {
        jdbcTemplate.execute("ALTER TABLE teams ADD COLUMN imported_fields TEXT");
        jdbcTemplate.execute("ALTER TABLE teams ADD COLUMN imported_extras TEXT");
        jdbcTemplate.execute("CREATE TABLE team_imported_fields (team_id BIGINT NOT NULL)");
        legacyTeamFieldsCleanup.run(new DefaultApplicationArguments(new String[0]));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE LOWER(TABLE_NAME) = 'team_imported_fields'", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = 'teams' AND LOWER(COLUMN_NAME) = 'imported_fields'",
                        Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = 'teams' AND LOWER(COLUMN_NAME) = 'imported_extras'",
                        Integer.class));
    }

    @Test
    void adminCanImportExcelManageLoginAndEnableProblemsAndAnyTeamMemberCanSubmit() throws Exception {
        mockMvc.perform(get("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginEnabled").value(true));

        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(teamWorkbookWithoutMemberName())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest());

        MockMultipartFile workbook = teamWorkbook();
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(workbook)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].students.length()").value(4))
                        .andExpect(jsonPath("$[0].importedFields.length()").value(19))
                        .andExpect(jsonPath("$[0].importedFields[1].fieldName").value("Username"))
                        .andExpect(jsonPath("$[0].importedFields[1].fieldValue").value("coordinator@example.com"))
                        .andExpect(jsonPath("$[0].importedFields[5].fieldName")
                                .value("Team Member 2 Registration Number/Roll Number"))
                        .andExpect(jsonPath("$[0].importedFields[5].fieldValue").value("22BCE0002"))
                        .andExpect(jsonPath("$[0].importedFields[10].fieldName")
                                .value("Primary Contact Number (preferably Whatsapp Number)"));

        Long teamId = teams.findAll().get(0).getId();
        Long leaderId = students.findAllByRegisterNumberIgnoreCase("22BCE0001").get(0).getId();
        List<ImportedTeamField> editedFields = new ArrayList<>(teams.findById(teamId).orElseThrow().getImportedFields());
        editedFields.stream()
                .filter(field -> "Username".equals(field.getFieldName()))
                .findFirst()
                .orElseThrow()
                .setFieldValue("updated@example.test");
        mockMvc.perform(put("/api/admin/teams/{teamId}/imported-fields", teamId)
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("fields", editedFields))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedFields.length()").value(19));
        Team savedImportedFields = teams.findById(teamId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(19, savedImportedFields.getImportedFields().size());
        org.junit.jupiter.api.Assertions.assertEquals("updated@example.test", savedImportedFields.getImportedFields()
                .stream()
                .filter(field -> "Username".equals(field.getFieldName()))
                .findFirst()
                .orElseThrow()
                .getFieldValue());
        mockMvc.perform(put("/api/admin/teams/{teamId}/imported-fields", teamId)
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[{\"columnIndex\":0,\"fieldName\":\"first\",\"fieldValue\":\"1\"},"
                                + "{\"columnIndex\":0,\"fieldName\":\"duplicate\",\"fieldValue\":\"2\"}]}"))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(19,
                teams.findById(teamId).orElseThrow().getImportedFields().size());
        mockMvc.perform(post("/api/admin/teams/{teamId}/students", teamId)
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"registerNumber\":\"22BCE0003\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/teams/{teamId}/students", teamId)
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"22BCE0003\",\"registerNumber\":\"22BCE0003\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/admin/students/{studentId}", leaderId)
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"registerNumber\":\"22BCE0001\"}"))
                .andExpect(status().isBadRequest());

        Student leader = students.findById(leaderId).orElseThrow();
        leader.setName("22BCE0001");
        students.saveAndFlush(leader);
        List<Student> checkedInMembers = students.findAll();
        checkedInMembers.forEach(member -> member.setPresent(true));
        students.saveAllAndFlush(checkedInMembers);
        org.junit.jupiter.api.Assertions.assertEquals(4, checkedInMembers.size());
        org.junit.jupiter.api.Assertions.assertTrue(checkedInMembers.stream().allMatch(Student::isPresent));
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isOk());
        leader = students.findById(leaderId).orElseThrow();
        leader.setName("TEST LEADER");
        students.saveAndFlush(leader);

        Problem problem = new Problem();
        problem.setTitle("Test problem");
        problem.setStatement("Test statement");
        problem = problems.saveAndFlush(problem);
        mockMvc.perform(put("/api/admin/teams/{teamId}/problem/{problemId}", teamId, problem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());

        Team secondTeam = createTeam("SECOND LEADER", "22BCE0005", "second-group@example.test");
        Long secondTeamId = secondTeam.getId();
        mockMvc.perform(put("/api/admin/teams/{teamId}/problem/{problemId}", secondTeamId, problem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].problem.id").value(problem.getId()))
                .andExpect(jsonPath("$[1].problem.id").value(problem.getId()));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students.length()").value(4))
                .andExpect(jsonPath("$.problem").value(nullValue()));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"wrong-team@example.test\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\","
                                + "\"googleDriveLink\":\"https://drive.google.com/file/d/test\","
                                + "\"githubLink\":\"https://github.com/example/project\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubLink").value("https://github.com/example/project"));

        mockMvc.perform(get("/api/admin/submissions")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].team.name").value("Team 001"));

        mockMvc.perform(get("/api/admin/submissions/export"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/submissions/export")
                        .header("X-Admin-Password", "wrong-password"))
                .andExpect(status().isUnauthorized());
        byte[] exportedWorkbook = mockMvc.perform(get("/api/admin/submissions/export")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("hackathon-submissions.xlsx")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        try (Workbook exportedFile = WorkbookFactory.create(new ByteArrayInputStream(exportedWorkbook))) {
            Row submissionRow = exportedFile.getSheet("Submissions").getRow(1);
            org.junit.jupiter.api.Assertions.assertEquals("Team 001", submissionRow.getCell(1).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("22BCE0001", submissionRow.getCell(3).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("https://drive.google.com/file/d/test",
                    submissionRow.getCell(7).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("https://github.com/example/project",
                    submissionRow.getCell(8).getStringCellValue());
        }
        mockMvc.perform(delete("/api/admin/submissions"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/admin/submissions")
                        .header("X-Admin-Password", "wrong-password"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/admin/submissions")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/submissions")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\","
                                + "\"googleDriveLink\":\"https://drive.google.com/file/d/test\","
                                + "\"githubLink\":\"https://github.com/example/project\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/problems/{problemId}/enabled", problem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problem.id").value(problem.getId()));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        mockMvc.perform(put("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginEnabled\":true,\"startTime\":\"" + now.plusMinutes(1)
                                + "\",\"endTime\":\"\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginEnabled\":false,\"startTime\":\"" + now.minusMinutes(1)
                                + "\",\"endTime\":\"" + now.plusMinutes(1) + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/settings/pause")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paused\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessPaused").value(true))
                .andExpect(jsonPath("$.startTime").isNotEmpty())
                .andExpect(jsonPath("$.endTime").isNotEmpty());
        mockMvc.perform(put("/api/admin/settings/pause")
                        .header("X-Admin-Password", "incorrect-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paused\":false}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\","
                                + "\"googleDriveLink\":\"https://drive.google.com/file/d/test\","
                                + "\"githubLink\":\"https://github.com/example/project\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/settings/pause")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paused\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessPaused").value(false));
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginEnabled\":true,\"startTime\":\"\",\"endTime\":\""
                                + now.minusMinutes(1) + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginEnabled\":false,\"startTime\":\"\",\"endTime\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginEnabled").value(false));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"leader@example.com\",\"contactNumber\":\"9876543210\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void teamsExportIncludesEveryTeamRegistrationDetailsAndAssignedProblemStatement() throws Exception {
        Team assignedTeam = createTeam("EXPORT ASSIGNED LEADER", "26MCA9101", "assigned@example.test");
        Team unassignedTeam = createTeam("EXPORT UNASSIGNED LEADER", "26MCA9102", "unassigned@example.test");
        Problem problem = new Problem();
        problem.setTitle("Water reuse dashboard");
        problem.setStatement("Build a dashboard that tracks water reuse across campus.");
        problem = problems.saveAndFlush(problem);
        assignedTeam.setProblem(problem);
        teams.saveAndFlush(assignedTeam);

        mockMvc.perform(get("/api/admin/teams/export"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/teams/export")
                        .header("X-Admin-Password", "wrong-password"))
                .andExpect(status().isUnauthorized());
        byte[] exportedWorkbook = mockMvc.perform(get("/api/admin/teams/export")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("hackathon-teams.xlsx")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        try (Workbook exportedFile = WorkbookFactory.create(new ByteArrayInputStream(exportedWorkbook))) {
            Sheet sheet = exportedFile.getSheet("Teams");
            org.junit.jupiter.api.Assertions.assertEquals(3, sheet.getPhysicalNumberOfRows());
            Row header = sheet.getRow(0);
            int memberDetailsColumn = 2 + TeamRegistrationFields.LABELS.size();
            int problemStatementColumn = memberDetailsColumn + 5;
            org.junit.jupiter.api.Assertions.assertEquals("Primary Email Id",
                    header.getCell(13).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("Assigned problem statement",
                    header.getCell(problemStatementColumn).getStringCellValue());

            Map<String, Row> rowsByTeamName = new java.util.HashMap<>();
            for (int rowIndex = 1; rowIndex <= 2; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                rowsByTeamName.put(row.getCell(1).getStringCellValue(), row);
            }
            Row assignedRow = rowsByTeamName.get("Team 001");
            Row unassignedRow = rowsByTeamName.get("Team 002");
            org.junit.jupiter.api.Assertions.assertNotNull(assignedRow);
            org.junit.jupiter.api.Assertions.assertNotNull(unassignedRow);
            org.junit.jupiter.api.Assertions.assertEquals("team-26mca9101@example.test",
                    assignedRow.getCell(13).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("assigned@example.test",
                    assignedRow.getCell(3).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertTrue(
                    assignedRow.getCell(memberDetailsColumn).getStringCellValue().contains("26MCA9101"));
            org.junit.jupiter.api.Assertions.assertEquals("Build a dashboard that tracks water reuse across campus.",
                    assignedRow.getCell(problemStatementColumn).getStringCellValue());
            org.junit.jupiter.api.Assertions.assertEquals("", unassignedRow.getCell(problemStatementColumn)
                    .getStringCellValue());
        }
    }

    @Test
    void scaleFixtureImportsEveryColumnAndSupportsCoreParticipantAndAdminWorkflows() throws Exception {
        Path fixture = testDataPath("realistic-scale-teams-1000-students.csv");
        List<String> fixtureLines = Files.readAllLines(fixture);
        List<String> fixtureHeaders = parseCsvRecord(fixtureLines.get(0));
        List<String> firstTeamValues = parseCsvRecord(fixtureLines.get(1));
        MockMultipartFile scaleCsv = new MockMultipartFile("file", fixture.getFileName().toString(),
                "text/csv", canonicalTeamCsv(fixtureLines));
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(scaleCsv)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(250))
                .andExpect(jsonPath("$[0].students.length()").value(4))
                .andExpect(jsonPath("$[0].importedFields.length()").value(19))
                .andExpect(jsonPath("$[0].importedFields[1].fieldName").value("Username"))
                .andExpect(jsonPath("$[0].importedFields[12].fieldName").value("Programme"))
                .andExpect(jsonPath("$[0].importedFields[15].fieldName")
                        .value(TeamRegistrationFields.LABELS.get(15)));

        org.junit.jupiter.api.Assertions.assertEquals(250, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(1000, students.count());
        mockMvc.perform(get("/api/public/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamCount").value(250))
                .andExpect(jsonPath("$.studentCount").value(1000));
        mockMvc.perform(get("/api/admin/teams").header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(250));
        mockMvc.perform(get("/api/admin/problems").header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        Team firstTeam = teams.findAll().stream()
                .min(Comparator.comparing(Team::getTeamNumber))
                .orElseThrow();
        Team lastTeam = teams.findAll().stream()
                .max(Comparator.comparing(Team::getTeamNumber))
                .orElseThrow();
        lastTeam.getStudents().forEach(student -> student.setPresent(true));
        teams.saveAndFlush(lastTeam);
        org.junit.jupiter.api.Assertions.assertEquals(fixtureValuesForIndex(fixtureHeaders, firstTeamValues, 2),
                firstTeam.getGroupLeaderName());
        org.junit.jupiter.api.Assertions.assertEquals(fixtureValuesForIndex(fixtureHeaders, firstTeamValues, 3),
                firstTeam.getGroupLeaderRegisterNumber());
        org.junit.jupiter.api.Assertions.assertEquals(fixtureValuesForIndex(fixtureHeaders, firstTeamValues, 15),
                firstTeam.getPaymentReferenceNumber());
        org.junit.jupiter.api.Assertions.assertEquals(19, firstTeam.getImportedFields().size());
        org.junit.jupiter.api.Assertions.assertEquals("MEMBER 3 TEAM 250",
                lastTeam.getStudents().stream().filter(member -> member.getRegisterNumber().equals("26MCA1998"))
                        .findFirst().orElseThrow().getName());

        Path questionsFixture = testDataPath("realistic-scale-questions-40.csv");
        MockMultipartFile questionCsv = new MockMultipartFile("file", "realistic-scale-questions-40.csv",
                "text/csv", Files.readAllBytes(questionsFixture));
        mockMvc.perform(multipart("/api/admin/problems/import")
                        .file(questionCsv)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(40));
        mockMvc.perform(get("/api/admin/problems").header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(40));
        Problem sharedProblem = problems.findAll().get(0);
        mockMvc.perform(put("/api/admin/problems/{problemId}/enabled", sharedProblem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/teams/{teamId}/problem/{problemId}",
                        firstTeam.getId(), sharedProblem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/admin/teams/{teamId}/problem/{problemId}",
                        lastTeam.getId(), sharedProblem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/admin/teams/random-assignment")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(250));
        org.junit.jupiter.api.Assertions.assertTrue(teams.findAll().stream()
                .allMatch(team -> team.getProblem() != null));

        mockMvc.perform(put("/api/admin/problems/{problemId}", sharedProblem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated shared problem\",\"statement\":\"Updated statement\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated shared problem"));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", lastTeam.getPrimaryEmail(),
                                "contactNumber", lastTeam.getPrimaryContactNumber()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamNumber").value(250))
                .andExpect(jsonPath("$.students.length()").value(4))
                .andExpect(jsonPath("$.problem.id").value(sharedProblem.getId()));
        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", lastTeam.getPrimaryEmail(),
                                "contactNumber", lastTeam.getPrimaryContactNumber(),
                                "googleDriveLink", "https://drive.google.com/file/d/test",
                                "githubLink", "https://github.com/example/project"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", lastTeam.getPrimaryEmail(),
                                "contactNumber", lastTeam.getPrimaryContactNumber()))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", lastTeam.getPrimaryEmail(),
                                "contactNumber", lastTeam.getPrimaryContactNumber(),
                                "googleDriveLink", "https://drive.google.com/file/d/team250",
                                "githubLink", "https://github.com/example/team250"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/submissions").header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].team.teamNumber").value(250))
                .andExpect(jsonPath("$[0].githubLink").value("https://github.com/example/team250"));

        mockMvc.perform(delete("/api/admin/teams/{teamId}/problem", lastTeam.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problem").value(nullValue()));

        mockMvc.perform(put("/api/admin/problems/{problemId}/enabled", sharedProblem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(put("/api/admin/teams/{teamId}/problem/{problemId}",
                        lastTeam.getId(), sharedProblem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", lastTeam.getPrimaryEmail(),
                                "contactNumber", lastTeam.getPrimaryContactNumber()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problem").value(nullValue()));

        mockMvc.perform(post("/api/admin/problems")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"CRUD test\",\"statement\":\"Temporary problem\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("CRUD test"));
        Problem temporaryProblem = problems.findAll().stream()
                .filter(problem -> "CRUD test".equals(problem.getTitle()))
                .findFirst().orElseThrow();
        mockMvc.perform(delete("/api/admin/problems/{problemId}", temporaryProblem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(problems.findById(temporaryProblem.getId()).isEmpty());

        Team crudTeam = createTeam("CRUD TEAM LEADER", "26MCA9998", "crud-team@example.test");
        mockMvc.perform(put("/api/admin/teams/{teamId}", crudTeam.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed CRUD team\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed CRUD team"));
        mockMvc.perform(post("/api/admin/teams/{teamId}/students", crudTeam.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CRUD PARTICIPANT\",\"registerNumber\":\"26MCA9999\","
                                + "\"email\":\"crud@example.test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students.length()").value(2));
        Student crudOriginalLeader = students.findAllByRegisterNumberIgnoreCase("26MCA9998").get(0);
        Student crudParticipant = students.findAllByRegisterNumberIgnoreCase("26MCA9999").get(0);
        mockMvc.perform(put("/api/admin/students/{studentId}", crudParticipant.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"UPDATED PARTICIPANT\",\"registerNumber\":\"26MCA9999\","
                                + "\"email\":\"updated@example.test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("UPDATED PARTICIPANT"))
                .andExpect(jsonPath("$.email").value("updated@example.test"));
        mockMvc.perform(put("/api/admin/students/{studentId}/leader", crudParticipant.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        crudParticipant = students.findById(crudParticipant.getId()).orElseThrow();
        crudParticipant.setPresent(true);
        students.saveAndFlush(crudParticipant);
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", crudTeam.getPrimaryEmail(),
                                "contactNumber", crudTeam.getPrimaryContactNumber()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students.length()").value(1));
        mockMvc.perform(delete("/api/admin/students/{studentId}", crudParticipant.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/admin/students/{studentId}", crudOriginalLeader.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/teams/{teamId}", crudTeam.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/admin/teams/{teamId}", firstTeam.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/teams/{teamId}", lastTeam.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(248, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(992, students.count());
        mockMvc.perform(delete("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/problems")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, students.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, problems.count());
    }

    private Path testDataPath(String filename) {
        Path fromRepositoryRoot = Path.of("test-data", filename);
        return Files.exists(fromRepositoryRoot) ? fromRepositoryRoot : Path.of("..", "test-data", filename);
    }

    private java.util.List<String> parseCsvRecord(String line) {
        java.util.List<String> fields = new java.util.ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private String fixtureValuesForIndex(List<String> headers, List<String> values, int expectedIndex) {
        for (int i = 0; i < headers.size(); i++) {
            if (TeamRegistrationFields.indexOf(headers.get(i)) == expectedIndex
                    && i < values.size() && !values.get(i).isBlank()) {
                return values.get(i);
            }
        }
        return "";
    }

    private byte[] canonicalTeamCsv(List<String> sourceLines) {
        List<String> sourceHeaders = parseCsvRecord(sourceLines.get(0));
        List<List<Integer>> sourceColumns = new ArrayList<>();
        for (int fieldIndex = 0; fieldIndex < TeamRegistrationFields.LABELS.size(); fieldIndex++) {
            sourceColumns.add(new ArrayList<>());
        }
        for (int column = 0; column < sourceHeaders.size(); column++) {
            int fieldIndex = TeamRegistrationFields.indexOf(sourceHeaders.get(column));
            if (fieldIndex >= 0) sourceColumns.get(fieldIndex).add(column);
        }

        StringBuilder canonicalCsv = new StringBuilder();
        canonicalCsv.append(csvLine(TeamRegistrationFields.LABELS)).append('\n');
        for (int rowIndex = 1; rowIndex < sourceLines.size(); rowIndex++) {
            List<String> sourceValues = parseCsvRecord(sourceLines.get(rowIndex));
            List<String> row = new ArrayList<>(TeamRegistrationFields.LABELS.size());
            for (List<Integer> columns : sourceColumns) {
                row.add(columns.stream()
                        .filter(column -> column < sourceValues.size() && !sourceValues.get(column).isBlank())
                        .map(sourceValues::get)
                        .findFirst()
                        .orElse(""));
            }
            canonicalCsv.append(csvLine(row)).append('\n');
        }
        return canonicalCsv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String csvLine(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private MockMultipartFile teamWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Teams");
            Row headers = sheet.createRow(0);
            for (int i = 0; i < TeamRegistrationFields.LABELS.size(); i++) {
                headers.createCell(i).setCellValue(TeamRegistrationFields.LABELS.get(i));
            }
            Row values = sheet.createRow(1);
            values.createCell(0).setCellValue("2026-10-08 04:00:00");
            values.createCell(1).setCellValue("coordinator@example.com");
            values.createCell(2).setCellValue("TEST LEADER");
            values.createCell(3).setCellValue("22BCE0001");
            values.createCell(4).setCellValue("TEST MEMBER 2");
            values.createCell(5).setCellValue("22BCE0002");
            values.createCell(6).setCellValue("TEST MEMBER 3");
            values.createCell(7).setCellValue("22BCE0003");
            values.createCell(8).setCellValue("TEST MEMBER 4");
            values.createCell(9).setCellValue("22BCE0004");
            values.createCell(10).setCellValue("9876543210");
            values.createCell(11).setCellValue("leader@example.com");
            values.createCell(12).setCellValue("MCA");
            values.createCell(13).setCellValue("CSE");
            values.createCell(14).setCellValue("VIT");
            values.createCell(15).setCellValue("PAY-123");
            values.createCell(16).setCellValue("Vellore Institute of Technology");
            values.createCell(17).setCellValue("Vellore");
            values.createCell(18).setCellValue("Tamil Nadu");
            workbook.write(output);
            return new MockMultipartFile("file", "teams.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private MockMultipartFile teamWorkbookWithoutMemberName() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Teams");
            Row headers = sheet.createRow(0);
            for (int i = 0; i < TeamRegistrationFields.LABELS.size(); i++) {
                headers.createCell(i).setCellValue(TeamRegistrationFields.LABELS.get(i));
            }
            Row values = sheet.createRow(1);
            values.createCell(2).setCellValue("TEST LEADER");
            values.createCell(3).setCellValue("22BCE0003");
            values.createCell(5).setCellValue("22BCE0004");
            workbook.write(output);
            return new MockMultipartFile("file", "teams-missing-member-name.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
