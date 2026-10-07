package com.vit.hackathon;

import com.vit.hackathon.model.Problem;
import com.vit.hackathon.model.Student;
import com.vit.hackathon.model.Team;
import com.vit.hackathon.repository.AppSettingRepository;
import com.vit.hackathon.repository.ProblemRepository;
import com.vit.hackathon.repository.SubmissionRepository;
import com.vit.hackathon.repository.StudentRepository;
import com.vit.hackathon.repository.TeamRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Comparator;
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

    @BeforeEach
    void clearDatabase() {
        submissions.deleteAllInBatch();
        students.deleteAllInBatch();
        teams.deleteAllInBatch();
        problems.deleteAllInBatch();
        settings.deleteAllInBatch();
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
                        .andExpect(jsonPath("$[0].importedFields.length()").value(21))
                        .andExpect(jsonPath("$[0].importedFields[0].fieldName").value("Username"))
                        .andExpect(jsonPath("$[0].importedFields[0].fieldValue").value("coordinator@example.com"))
                        .andExpect(jsonPath("$[0].importedFields[12].fieldName")
                                .value("Team Member 2 - Registration Number/Roll Number"))
                        .andExpect(jsonPath("$[0].importedFields[12].fieldValue").value("22BCE0002"))
                        .andExpect(jsonPath("$[0].importedFields[17].fieldName")
                                .value("Primary Contact Number (preferably Whatsapp Number)"));

        Long teamId = teams.findAll().get(0).getId();
        Long leaderId = students.findByRegisterNumberIgnoreCase("22BCE0001").orElseThrow().getId();
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

        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Second group\"}"))
                .andExpect(status().isOk());
        Long secondTeamId = teams.findAll().stream()
                .filter(team -> "Second group".equals(team.getName()))
                .findFirst()
                .orElseThrow()
                .getId();
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
                "text/csv", Files.readAllBytes(fixture));
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(scaleCsv)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(250))
                .andExpect(jsonPath("$[0].students.length()").value(4))
                .andExpect(jsonPath("$[0].importedFields.length()").value(21))
                .andExpect(jsonPath("$[0].importedFields[0].fieldName").value("Username"))
                .andExpect(jsonPath("$[0].importedFields[4].fieldName").value("Programme"))
                .andExpect(jsonPath("$[0].importedFields[7].fieldName")
                        .value("Payment Reference Number (Check your Payment Receipt- Refer  Reference No column)"))
                .andExpect(jsonPath("$[0].importedFields[20].fieldName").value("Their Gmail ID"));

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
        org.junit.jupiter.api.Assertions.assertEquals(
                "Team Member 2 \u00e2\u20ac\u201c Name   (As per SSLC Record- USE UPPERCASE FORMAT only) ",
                firstTeam.getImportedFields().get(11).getFieldName());
        org.junit.jupiter.api.Assertions.assertEquals(fixtureHeaders,
                firstTeam.getImportedFields().stream().map(field -> field.getFieldName()).toList());
        org.junit.jupiter.api.Assertions.assertEquals(firstTeamValues,
                firstTeam.getImportedFields().stream().map(field -> field.getFieldValue()).toList());
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

        mockMvc.perform(post("/api/admin/teams")
                        .header("X-Admin-Password", ADMIN_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CRUD test team\"}"))
                .andExpect(status().isOk());
        Team crudTeam = teams.findAll().stream()
                .filter(team -> "CRUD test team".equals(team.getName()))
                .findFirst().orElseThrow();
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
                .andExpect(jsonPath("$.students.length()").value(1));
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

    private MockMultipartFile teamWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Teams");
            Row headers = sheet.createRow(0);
            String[] headerNames = {
                    "Username", "Timestamp", "Name of the Group Leader", "Register Number/Roll Number of the Group Leader",
                    "Programme", "Specialization (say for example CSE/ECE/EEE/ CSE SPEC. IN AI ML)", "Institution",
                    "Payment Reference Number", "Name of the Institute", "City", "State", "Team Member 2 - Name",
                    "Team Member 2 - Registration Number/Roll Number", "Team Member 3 - Name",
                    "Team Member 3 - Registration Number/Roll Number", "Team Member 4 - Name",
                    "Team Member 4 - Registration Number/Roll Number", "Primary Contact Number (preferably Whatsapp Number)",
                    "Primary Email Id", "Team Member 2 - Registration Number/Enrollment Number/Register Number",
                    "Team Member 3 - Registration Number/Enrollment Number/Register Number",
                    "Team Member 3 - Name (As per SSLC Record)", "Team Member 4 - Registration Number/Enrollment Number/Register Number",
                    "Team Member 4 - Name (As per SSLC Record)", "Primary Contact Number (preferably Whatsapp Number)",
                    "Email ID", "Their Gmail ID"
            };
            for (int i = 0; i < headerNames.length; i++) {
                headers.createCell(i).setCellValue(headerNames[i]);
            }
            Row values = sheet.createRow(1);
            values.createCell(0).setCellValue("coordinator@example.com");
            values.createCell(1).setCellValue("2026-10-08 04:00:00");
            values.createCell(2).setCellValue("TEST LEADER");
            values.createCell(3).setCellValue("22BCE0001");
            values.createCell(4).setCellValue("MCA");
            values.createCell(5).setCellValue("CSE");
            values.createCell(6).setCellValue("VIT");
            values.createCell(7).setCellValue("PAY-123");
            values.createCell(8).setCellValue("Vellore Institute of Technology");
            values.createCell(9).setCellValue("Vellore");
            values.createCell(10).setCellValue("Tamil Nadu");
            values.createCell(11).setCellValue("TEST MEMBER 2");
            values.createCell(13).setCellValue("TEST MEMBER 3");
            values.createCell(14).setCellValue("22BCE0003");
            values.createCell(15).setCellValue("TEST MEMBER 4");
            values.createCell(16).setCellValue("22BCE0004");
            values.createCell(17).setCellValue("9876543210");
            values.createCell(18).setCellValue("team@example.com");
            values.createCell(19).setCellValue("22BCE0002");
            values.createCell(20).setCellValue("22BCE0003");
            values.createCell(21).setCellValue("TEST MEMBER 3");
            values.createCell(22).setCellValue("22BCE0004");
            values.createCell(23).setCellValue("TEST MEMBER 4");
            values.createCell(24).setCellValue("9876543210");
            values.createCell(25).setCellValue("leader@example.com");
            values.createCell(26).setCellValue("leader@gmail.com");
            workbook.write(output);
            return new MockMultipartFile("file", "teams.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private MockMultipartFile teamWorkbookWithoutMemberName() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Teams");
            Row headers = sheet.createRow(0);
            headers.createCell(0).setCellValue("Name of the Group Leader");
            headers.createCell(1).setCellValue("Register Number/Roll Number of the Group Leader");
            headers.createCell(2).setCellValue("Team Member 2 - Name");
            headers.createCell(3).setCellValue("Team Member 2 - Registration Number/Roll Number");
            Row values = sheet.createRow(1);
            values.createCell(0).setCellValue("TEST LEADER");
            values.createCell(1).setCellValue("22BCE0003");
            values.createCell(3).setCellValue("22BCE0004");
            workbook.write(output);
            return new MockMultipartFile("file", "teams-missing-member-name.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
