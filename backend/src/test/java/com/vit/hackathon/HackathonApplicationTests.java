package com.vit.hackathon;

import com.vit.hackathon.model.Problem;
import com.vit.hackathon.repository.ProblemRepository;
import com.vit.hackathon.repository.TeamRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void adminCanImportExcelManageLoginAndEnableProblemsAndLeaderCanSubmit() throws Exception {
        mockMvc.perform(get("/api/admin/settings")
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginEnabled").value(true));

        MockMultipartFile workbook = teamWorkbook();
        mockMvc.perform(multipart("/api/admin/teams/import")
                        .file(workbook)
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].students.length()").value(2));

        Problem problem = new Problem();
        problem.setTitle("Test problem");
        problem.setStatement("Test statement");
        problem = problems.saveAndFlush(problem);
        Long teamId = teams.findAll().get(0).getId();
        mockMvc.perform(put("/api/admin/teams/{teamId}/problem/{problemId}", teamId, problem.getId())
                        .header("X-Admin-Password", ADMIN_PASSWORD))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TEST LEADER\",\"registerNumber\":\"22BCE0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leader").value(true))
                .andExpect(jsonPath("$.ownRegisterNumber").value("22BCE0001"))
                .andExpect(jsonPath("$.problem").value(nullValue()));

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
                        .content("{\"name\":\"TEST MEMBER\",\"registerNumber\":\"22BCE0002\","
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

    private MockMultipartFile teamWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Teams");
            Row headers = sheet.createRow(0);
            headers.createCell(0).setCellValue("Name of the Group Leader");
            headers.createCell(1).setCellValue("Register Number/Roll Number of the Group Leader");
            headers.createCell(2).setCellValue("Team Member 2 - Name");
            headers.createCell(3).setCellValue("Team Member 2 - Registration Number/Roll Number");
            Row values = sheet.createRow(1);
            values.createCell(0).setCellValue("TEST LEADER");
            values.createCell(1).setCellValue("22BCE0001");
            values.createCell(2).setCellValue("TEST MEMBER");
            values.createCell(3).setCellValue("22BCE0002");
            workbook.write(output);
            return new MockMultipartFile("file", "teams.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
