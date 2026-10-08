package com.vit.hackathon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vit.hackathon.model.ImportedTeamField;
import com.vit.hackathon.model.Problem;
import com.vit.hackathon.model.Student;
import com.vit.hackathon.model.Team;
import com.vit.hackathon.model.TeamRegistrationFields;
import com.vit.hackathon.config.LegacyTeamFieldsCleanup;
import com.vit.hackathon.repository.AppSettingRepository;
import com.vit.hackathon.repository.ProblemRepository;
import com.vit.hackathon.repository.SubmissionRepository;
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
    private AppSettingRepository settings;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LegacyTeamFieldsCleanup legacyTeamFieldsCleanup;

    @BeforeEach
    void clearDatabase() {
        submissions.deleteAllInBatch();
        students.deleteAllInBatch();
        teams.deleteAllInBatch();
        problems.deleteAllInBatch();
        settings.deleteAllInBatch();
    }

    @Test
    void manualTeamCreationStoresAllFieldsAndCreatesParticipantRoster() throws Exception {
        List<ImportedTeamField> fields = new ArrayList<>();
        for (int index = 0; index < 19; index++) {
            fields.add(new ImportedTeamField(index, com.vit.hackathon.model.TeamRegistrationFields.LABELS.get(index), ""));
        }

        fields.get(2).setFieldValue("TEAM LEADER");
        fields.get(3).setFieldValue("26MCA9001");
        fields.get(4).setFieldValue("TEAM MEMBER");
        fields.get(5).setFieldValue("26MCA9002");
        fields.get(11).setFieldValue("leader@example.test");

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
        org.junit.jupiter.api.Assertions.assertEquals("TEAM LEADER",
                jdbcTemplate.queryForObject("SELECT group_leader_name FROM teams WHERE id = ?",
                        String.class, teams.findAll().get(0).getId()));
    }

    @Test
    void duplicateNamesAndUsernamesAreAllowedButParticipantRegistersAreUnique() throws Exception {
        jdbcTemplate.execute("ALTER TABLE teams ADD CONSTRAINT legacy_teams_name_unique UNIQUE (name)");
        legacyTeamFieldsCleanup.run(new DefaultApplicationArguments(new String[0]));
        org.junit.jupiter.api.Assertions.assertEquals("NO",
                jdbcTemplate.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE LOWER(TABLE_NAME) = 'teams' AND LOWER(COLUMN_NAME) = 'group_leader_name'",
                        String.class));
        org.junit.jupiter.api.Assertions.assertEquals("NO",
                jdbcTemplate.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE LOWER(TABLE_NAME) = 'teams' "
                                + "AND LOWER(COLUMN_NAME) = 'group_leader_register_number'",
                        String.class));
        Team first = createTeam("SAME PERSON", "26mca9101", "shared-username@example.test");
        Team second = createTeam("SAME PERSON", "26MCA9102", "shared-username@example.test");
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
                students.findByRegisterNumberIgnoreCase("26mca9101").orElseThrow().getRegisterNumber());

        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields",
                                teamFields("ANOTHER SAME NAME", " 26MCA9101 ", "another-user@example.test")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already assigned to another team")));
        org.junit.jupiter.api.Assertions.assertEquals(2, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(2, students.count());

        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(TeamRegistrationFields.LABELS,
                                teamRow("THIRD SAME PERSON", "26mca9102", "shared-user@example.test")))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already assigned to another team")));
        org.junit.jupiter.api.Assertions.assertEquals(2, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(2, students.count());

        mockMvc.perform(post("/api/admin/teams/{teamId}/students", second.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ANOTHER SAME NAME\",\"registerNumber\":\"26mca9101\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already assigned to another team")));
        Student secondStudent = second.getStudents().get(0);
        mockMvc.perform(put("/api/admin/students/{studentId}", secondStudent.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ANOTHER SAME NAME\",\"registerNumber\":\"26mca9101\"}"))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(2, students.count());
    }

    @Test
    void teamCreationRequiresLeaderAndEitherBothOrNeitherMemberFields() throws Exception {
        List<ImportedTeamField> missingLeader = teamFields("", "", "");
        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields", missingLeader))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Group leader name and register number are required")));

        List<ImportedTeamField> incompleteMember = teamFields("LEADER", "26MCA9201", "");
        incompleteMember.get(4).setFieldValue("MEMBER WITHOUT REGISTER");
        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("importedFields", incompleteMember))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "enter both the name and register number or leave both blank")));
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());
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
        org.junit.jupiter.api.Assertions.assertTrue(students.findByRegisterNumberIgnoreCase("26MCA9251").isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(students.findByRegisterNumberIgnoreCase("26MCA9252").isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals("UPDATED LEADER",
                students.findByRegisterNumberIgnoreCase("26MCA9261").orElseThrow().getName());
        org.junit.jupiter.api.Assertions.assertEquals("UPDATED MEMBER",
                students.findByRegisterNumberIgnoreCase("26MCA9262").orElseThrow().getName());
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
        Student oldLeader = students.findByRegisterNumberIgnoreCase("26MCA9271").orElseThrow();
        Student replacement = students.findByRegisterNumberIgnoreCase("26MCA9272").orElseThrow();

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
        org.junit.jupiter.api.Assertions.assertEquals("", updated.getMember2RegisterNumber());
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
    void teamImportRejectsAnyHeaderOrRowShapeThatDiffersFromTheCanonicalContract() throws Exception {
        List<String> canonicalHeaders = TeamRegistrationFields.LABELS;
        List<List<String>> invalidHeaders = new ArrayList<>();
        List<String> extra = new ArrayList<>(canonicalHeaders);
        extra.add("Extra column");
        invalidHeaders.add(extra);
        invalidHeaders.add(new ArrayList<>(canonicalHeaders.subList(0, canonicalHeaders.size() - 1)));
        List<String> reordered = new ArrayList<>(canonicalHeaders);
        java.util.Collections.swap(reordered, 0, 1);
        invalidHeaders.add(reordered);
        List<String> renamed = new ArrayList<>(canonicalHeaders);
        renamed.set(0, "Submitted at");
        invalidHeaders.add(renamed);

        for (List<String> headers : invalidHeaders) {
            MockMultipartFile file = csvUpload(headers, new ArrayList<>(java.util.Collections.nCopies(headers.size(), "")));
            mockMvc.perform(multipart("/api/admin/teams/import")
                            .file(file)
                            .header("X-Admin-Password", ADMIN_PASSWORD))
                    .andExpect(status().isBadRequest());
        }
        List<String> extraCell = new ArrayList<>(java.util.Collections.nCopies(canonicalHeaders.size() + 1, ""));
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(canonicalHeaders, extraCell))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());
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

    private List<ImportedTeamField> teamFields(String leaderName, String leaderRegister, String username) {
        List<ImportedTeamField> fields = new ArrayList<>();
        for (int index = 0; index < TeamRegistrationFields.LABELS.size(); index++) {
            fields.add(new ImportedTeamField(index, TeamRegistrationFields.LABELS.get(index), ""));
        }
        fields.get(1).setFieldValue(username);
        fields.get(2).setFieldValue(leaderName);
        fields.get(3).setFieldValue(leaderRegister);
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
    void invalidTeamImportReturnsSpecificErrorAndDoesNotSaveAnyRows() throws Exception {
        List<String> validRow = new ArrayList<>(java.util.Collections.nCopies(19, ""));
        validRow.set(2, "TEST LEADER");
        validRow.set(3, "26MCA9001");
        List<String> extraCellRow = new ArrayList<>(validRow);
        extraCellRow.add("unexpected");

        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUploadRows(TeamRegistrationFields.LABELS, List.of(validRow, extraCellRow)))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("row 3 contains extra columns")));
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, students.count());

        List<String> renamedHeaders = new ArrayList<>(TeamRegistrationFields.LABELS);
        renamedHeaders.set(0, "Submitted at");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(renamedHeaders, validRow))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("column 1 must be exactly: Timestamp")));
    }

    @Test
    void teamImportAllowsRepeatedNamesAndUsernamesButRejectsDuplicateRegistersAtomically() throws Exception {
        List<String> firstRow = teamRow("SAME PERSON", "26MCA9301", "shared-user@example.test");
        List<String> secondRow = teamRow("SAME PERSON", "26mca9301", "shared-user@example.test");

        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUploadRows(TeamRegistrationFields.LABELS, List.of(firstRow, secondRow)))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("26MCA9301"),
                        org.hamcrest.Matchers.containsString("CSV rows 2 and 3"),
                        org.hamcrest.Matchers.containsString("only one team"))));
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, students.count());

        List<String> thirdRow = teamRow("SAME PERSON", "26MCA9302", "shared-user@example.test");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUploadRows(TeamRegistrationFields.LABELS, List.of(firstRow, thirdRow)))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        org.junit.jupiter.api.Assertions.assertEquals(2, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(2, students.count());
    }

    @Test
    void teamImportRejectsRepeatedRegistersInsideOneTeamAndIncompleteMemberPairs() throws Exception {
        List<String> repeatedWithinTeam = teamRow("LEADER", "26MCA9401", "same-user@example.test");
        repeatedWithinTeam.set(4, "MEMBER");
        repeatedWithinTeam.set(5, "26mca9401");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(TeamRegistrationFields.LABELS, repeatedWithinTeam))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("rows 2 and 2")));
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());

        List<String> missingRegister = teamRow("LEADER", "26MCA9402", "same-user@example.test");
        missingRegister.set(4, "MEMBER WITHOUT REGISTER");
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(csvUpload(TeamRegistrationFields.LABELS, missingRegister))
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "enter both the name and register number or leave both blank")));
        org.junit.jupiter.api.Assertions.assertEquals(0, teams.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, students.count());
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
    void adminCanImportExcelManageLoginAndEnableProblemsAndLeaderCanSubmit() throws Exception {
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
        Long leaderId = students.findByRegisterNumberIgnoreCase("22BCE0001").orElseThrow().getId();
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
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"22BCE0001\",\"registerNumber\":\"22BCE0001\"}"))
                .andExpect(status().isUnauthorized());
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
                        .content("{\"name\":\"TEST LEADER\",\"registerNumber\":\"22BCE0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leader").value(true))
                .andExpect(jsonPath("$.ownRegisterNumber").value("22BCE0001"))
                .andExpect(jsonPath("$.problem").value(nullValue()));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"WRONG NAME\",\"registerNumber\":\"22BCE0001\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TEST LEADER\",\"registerNumber\":\"22BCE0001\","
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
                        .content("{\"name\":\"TEST MEMBER 2\",\"registerNumber\":\"22BCE0002\","
                                + "\"googleDriveLink\":\"https://drive.google.com/file/d/test\","
                                + "\"githubLink\":\"https://github.com/example/project\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/problems/{problemId}/enabled", problem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TEST LEADER\",\"registerNumber\":\"22BCE0001\"}"))
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
                        .content("{\"name\":\"TEST LEADER\",\"registerNumber\":\"22BCE0001\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginEnabled\":true,\"startTime\":\"" + now.minusMinutes(1)
                                + "\",\"endTime\":\"" + now.plusMinutes(1) + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TEST LEADER\",\"registerNumber\":\"22BCE0001\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginEnabled\":true,\"startTime\":\"\",\"endTime\":\""
                                + now.minusMinutes(1) + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TEST LEADER\",\"registerNumber\":\"22BCE0001\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginEnabled\":false,\"startTime\":\"\",\"endTime\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginEnabled").value(false));

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Participant\",\"registerNumber\":\"22BCE0001\"}"))
                .andExpect(status().isForbidden());
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
                        .content("{\"name\":\"MEMBER 3 TEAM 250\",\"registerNumber\":\"26MCA1998\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamNumber").value(250))
                .andExpect(jsonPath("$.students.length()").value(4))
                .andExpect(jsonPath("$.problem.id").value(sharedProblem.getId()))
                .andExpect(jsonPath("$.leader").value(false));
        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"MEMBER 3 TEAM 250\",\"registerNumber\":\"26MCA1998\","
                                + "\"googleDriveLink\":\"https://drive.google.com/file/d/test\","
                                + "\"githubLink\":\"https://github.com/example/project\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"LEADER 250\",\"registerNumber\":\"26MCA1996\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leader").value(true));
        mockMvc.perform(put("/api/student/submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"LEADER 250\",\"registerNumber\":\"26MCA1996\","
                                + "\"googleDriveLink\":\"https://drive.google.com/file/d/team250\","
                                + "\"githubLink\":\"https://github.com/example/team250\"}"))
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
                        .content("{\"name\":\"LEADER 250\",\"registerNumber\":\"26MCA1996\"}"))
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
        Student crudOriginalLeader = students.findByRegisterNumberIgnoreCase("26MCA9998").orElseThrow();
        Student crudParticipant = students.findByRegisterNumberIgnoreCase("26MCA9999").orElseThrow();
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
        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"UPDATED PARTICIPANT\",\"registerNumber\":\"26MCA9999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leader").value(true));
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
