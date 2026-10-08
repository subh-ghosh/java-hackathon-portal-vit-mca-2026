package com.vit.hackathon.api;

import com.vit.hackathon.model.*;
import com.vit.hackathon.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class HackathonController {
    private final TeamRepository teams;
    private final StudentRepository students;
    private final ProblemRepository problems;
    private final AppSettingRepository settings;
    private final SubmissionRepository submissions;
    private final String adminPassword;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public HackathonController(TeamRepository teams, StudentRepository students, ProblemRepository problems,
                               AppSettingRepository settings,
                               SubmissionRepository submissions,
                               @Value("${app.admin.password}") String adminPassword) {
        this.teams = teams;
        this.students = students;
        this.problems = problems;
        this.settings = settings;
        this.submissions = submissions;
        this.adminPassword = adminPassword;
    }

    private static void setCell(Row row, int column, String value) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
    }

    private void ensureTeamNumber(Team team) {
        if (team.getTeamNumber() == null) {
            team.setTeamNumber(nextTeamNumber());
            teams.save(team);
        }
    }

    @GetMapping("/public/config")
    public Map<String, Object> config() {
        return Map.of("teamCount", teams.count(), "studentCount", students.count());
    }

    @ExceptionHandler(TeamImportException.class)
    public ResponseEntity<Map<String, String>> handleTeamImportException(TeamImportException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", exception.getReason()));
    }

    @PostMapping("/student/login")
    public StudentTeamResponse login(@RequestBody LoginRequest request) {
        enforceLoginWindow();
        Student student = authenticateParticipant(request.name(), request.registerNumber());
        Team team = teams.findById(student.getTeam().getId()).orElseThrow();
        ensureTeamNumber(team);
        String leaderRegisterNumber = team.getStudents().stream()
                .filter(Student::isLeader)
                .map(Student::getRegisterNumber)
                .findFirst()
                .orElse(null);
        String participantName = student.getName() == null ? "" : student.getName().trim();
        Problem visibleProblem = team.getProblem() != null && team.getProblem().isEnabled() ? team.getProblem() : null;
        return new StudentTeamResponse(team.getName(), team.getTeamNumber(), student.getRegisterNumber(),
                participantName, student.getEmail(), leaderRegisterNumber, visibleProblem, team.getStudents(),
                student.isLeader(), submissions.findByTeamId(team.getId()).orElse(null));
    }

    @GetMapping("/admin/settings")
    public Map<String, Object> adminSettings(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        return settingsPayload();
    }

    @PutMapping("/admin/settings")
    public Map<String, Object> updateAdminSettings(@RequestHeader("X-Admin-Password") String password,
                                                    @RequestBody SettingsRequest request) {
        requireAdmin(password);
        OffsetDateTime startTime = parseOptionalTime(request.startTime(), "start");
        OffsetDateTime endTime = parseOptionalTime(request.endTime(), "end");
        if (startTime != null && endTime != null && !endTime.isAfter(startTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Login end time must be after start time");
        }
        saveSetting("login-enabled", Boolean.toString(request.loginEnabled()));
        saveSetting("login-start", request.startTime() == null ? "" : request.startTime());
        saveSetting("login-end", request.endTime() == null ? "" : request.endTime());
        return settingsPayload();
    }

    @GetMapping("/admin/submissions")
    public List<Submission> listSubmissions(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        return submissions.findAll();
    }

    @GetMapping("/admin/submissions/export")
    public ResponseEntity<byte[]> exportSubmissions(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Submissions");
            Row header = sheet.createRow(0);
            String[] columns = {
                    "Team number", "Team name", "Team leader", "Leader register number", "Members",
                    "Problem ID", "Problem title", "Google Drive link", "GitHub link", "Updated at"
            };
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            int rowNumber = 1;
            for (Submission submission : submissions.findAll()) {
                Team team = submission.getTeam();
                Row row = sheet.createRow(rowNumber++);
                String leaderName = team.getStudents().stream()
                        .filter(Student::isLeader)
                        .map(Student::getName)
                        .findFirst()
                        .orElse("");
                String leaderRegisterNumber = team.getStudents().stream()
                        .filter(Student::isLeader)
                        .map(Student::getRegisterNumber)
                        .findFirst()
                        .orElse("");
                String members = team.getStudents().stream()
                        .map(student -> student.getName() + " (" + student.getRegisterNumber() + ")")
                        .collect(Collectors.joining(", "));
                setCell(row, 0, team.getTeamNumber() == null ? "" : team.getTeamNumber().toString());
                setCell(row, 1, team.getName());
                setCell(row, 2, leaderName);
                setCell(row, 3, leaderRegisterNumber);
                setCell(row, 4, members);
                setCell(row, 5, team.getProblem() == null ? "" : team.getProblem().getId().toString());
                setCell(row, 6, team.getProblem() == null ? "" : team.getProblem().getTitle());
                setCell(row, 7, submission.getGoogleDriveLink());
                setCell(row, 8, submission.getGithubLink());
                setCell(row, 9, submission.getUpdatedAt().toString());
            }
            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(output);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"hackathon-submissions.xlsx\"")
                    .body(output.toByteArray());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not generate submissions workbook", exception);
        }
    }

    @DeleteMapping("/admin/submissions")
    @Transactional
    public void clearSubmissions(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        submissions.deleteAllInBatch();
    }

    @PutMapping("/student/submission")
    public Submission saveSubmission(@RequestBody SubmissionRequest request) {
        enforceLoginWindow();
        Student student = authenticateParticipant(request.name(), request.registerNumber());
        if (!student.isLeader()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the group leader can submit");
        requireWebUrl(request.googleDriveLink(), "Google Drive");
        requireWebUrl(request.githubLink(), "GitHub");
        Submission submission = submissions.findByTeamId(student.getTeam().getId()).orElseGet(Submission::new);
        Team team = teams.findById(student.getTeam().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Participant team not found"));
        submission.setTeam(team);
        submission.setGoogleDriveLink(request.googleDriveLink().trim());
        submission.setGithubLink(request.githubLink().trim());
        submission.touch();
        return submissions.save(submission);
    }

    @GetMapping("/admin/teams")
    public List<Team> listTeams(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        List<Team> allTeams = teams.findAll();
        allTeams.forEach(this::ensureTeamNumber);
        return allTeams;
    }

    @GetMapping("/admin/problems")
    public List<Problem> listProblems(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        return problems.findAll();
    }

    @PostMapping("/admin/problems")
    public Problem createProblem(@RequestHeader("X-Admin-Password") String password, @RequestBody ProblemRequest request) {
        requireAdmin(password);
        Problem problem = new Problem();
        problem.setTitle(request.title());
        problem.setStatement(request.statement());
        return problems.save(problem);
    }

    @PutMapping("/admin/problems/{problemId}/enabled")
    public Problem setProblemEnabled(@RequestHeader("X-Admin-Password") String password,
                                     @PathVariable Long problemId,
                                     @RequestBody EnabledRequest request) {
        requireAdmin(password);
        Problem problem = problems.findById(problemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));
        problem.setEnabled(request.enabled());
        return problems.save(problem);
    }

    @PostMapping("/admin/problems/import")
    public List<Problem> importProblems(@RequestHeader("X-Admin-Password") String password,
                                        @RequestPart("file") MultipartFile file) {
        requireAdmin(password);
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file is empty");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file has no header");
            }
            List<String> headers = parseCsvLine(headerLine);
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                columns.put(normalize(headers.get(i)), i);
            }
            Integer titleColumn = findColumnAny(columns, "title", "problem title", "question title");
            Integer statementColumn = findColumnAny(columns, "statement", "problem statement", "question", "description");
            if (statementColumn == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV must contain a statement, problem statement, question, or description column");
            }
            List<Problem> imported = new ArrayList<>();
            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                String statement = valueAt(values, statementColumn);
                if (statement.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Missing question statement on CSV row " + rowNumber);
                }
                Problem problem = new Problem();
                problem.setTitle(titleColumn == null ? "Question " + (rowNumber - 1) : valueAt(values, titleColumn));
                if (problem.getTitle() == null || problem.getTitle().isBlank()) {
                    problem.setTitle("Question " + (rowNumber - 1));
                }
                problem.setStatement(statement);
                imported.add(problems.save(problem));
            }
            return imported;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read CSV file", exception);
        }
    }

    @PutMapping("/admin/teams/random-assignment")
    public List<Team> randomlyAssignProblems(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        List<Team> allTeams = teams.findAll();
        List<Problem> allProblems = problems.findAll();
        if (allTeams.isEmpty() || allProblems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Create at least one team and one question first");
        }
        List<Team> unassignedTeams = allTeams.stream()
                .filter(team -> team.getProblem() == null)
                .collect(Collectors.toCollection(ArrayList::new));
        if (unassignedTeams.isEmpty()) {
            return allTeams;
        }
        Map<Long, Integer> usage = new HashMap<>();
        allProblems.forEach(problem -> usage.put(problem.getId(), 0));
        allTeams.stream()
                .map(Team::getProblem)
                .filter(Objects::nonNull)
                .forEach(problem -> usage.computeIfPresent(problem.getId(), (id, count) -> count + 1));
        Collections.shuffle(unassignedTeams);
        Collections.shuffle(allProblems);
        for (Team team : unassignedTeams) {
            Problem leastUsed = allProblems.stream()
                    .min(Comparator.comparingInt(problem -> usage.get(problem.getId())))
                    .orElseThrow();
            team.setProblem(leastUsed);
            usage.computeIfPresent(leastUsed.getId(), (id, count) -> count + 1);
        }
        return teams.saveAll(allTeams);
    }

    @PutMapping("/admin/problems/{problemId}")
    public Problem updateProblem(@RequestHeader("X-Admin-Password") String password,
                                 @PathVariable Long problemId, @RequestBody ProblemRequest request) {
        requireAdmin(password);
        Problem problem = problems.findById(problemId).orElseThrow();
        problem.setTitle(request.title());
        problem.setStatement(request.statement());
        return problems.save(problem);
    }

    @DeleteMapping("/admin/problems/{problemId}")
    public void deleteProblem(@RequestHeader("X-Admin-Password") String password, @PathVariable Long problemId) {
        requireAdmin(password);
        Problem problem = problems.findById(problemId).orElseThrow();
        teams.findAll().stream().filter(team -> problem.equals(team.getProblem())).forEach(team -> {
            team.setProblem(null);
            teams.save(team);
        });
        problems.delete(problem);
    }

    @DeleteMapping("/admin/problems")
    public void clearProblems(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        List<Team> allTeams = teams.findAll();
        allTeams.forEach(team -> team.setProblem(null));
        teams.saveAll(allTeams);
        problems.deleteAllInBatch();
    }

    @PostMapping("/admin/teams")
    public Team createTeam(@RequestHeader("X-Admin-Password") String password, @RequestBody TeamRequest request) {
        requireAdmin(password);
        Team team = new Team();
        if (request.importedFields() == null) {
            if (request.name() == null || request.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Team name is required");
            }
            team.setName(request.name().trim());
        } else {
            List<ImportedTeamField> fields = request.importedFields();
            if (fields.size() != 19 || fields.stream().anyMatch(field -> field == null
                    || field.getColumnIndex() < 0 || field.getColumnIndex() >= 19
                    || !TeamRegistrationFields.LABELS.get(field.getColumnIndex()).equals(field.getFieldName())
                    || isInvalidImportedField(field))
                    || fields.stream().map(ImportedTeamField::getColumnIndex).distinct().count() != 19) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "All 19 team form fields must be provided with unique indexes");
            }
            String leaderName = importedFieldValue(fields, 2).trim();
            String leaderRegister = importedFieldValue(fields, 3).trim();
            if (leaderName.isBlank() || leaderRegister.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Group leader name and register number are required");
            }
            List<ImportedParticipant> participants = new ArrayList<>();
            participants.add(new ImportedParticipant(leaderRegister, leaderName));
            for (int member = 2; member <= 4; member++) {
                String memberName = importedFieldValue(fields, 4 + (member - 2) * 2).trim();
                String memberRegister = importedFieldValue(fields, 5 + (member - 2) * 2).trim();
                if (memberName.isBlank() != memberRegister.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Both name and register number are required for team member " + member);
                }
                if (!memberRegister.isBlank()) {
                    participants.add(new ImportedParticipant(memberRegister, memberName));
                }
            }
            if (participants.stream().map(ImportedParticipant::registerNumber).distinct().count()
                    != participants.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Each participant must have a unique register number");
            }
            for (ImportedParticipant participant : participants) {
                if (students.findByRegisterNumberIgnoreCase(participant.registerNumber()).isPresent()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Register number is already assigned: " + participant.registerNumber());
                }
            }
            team.setImportedFields(fields);
            for (int i = 0; i < participants.size(); i++) {
                ImportedParticipant participant = participants.get(i);
                Student student = new Student();
                student.setName(participant.name());
                student.setRegisterNumber(participant.registerNumber());
                student.setEmail(i == 0 ? importedFieldValue(fields, 11).trim() : null);
                student.setLeader(i == 0);
                student.setTeam(team);
                team.getStudents().add(student);
            }
        }
        team.setTeamNumber(nextTeamNumber());
        if (team.getName() == null) team.setName(String.format("Team %03d", team.getTeamNumber()));
        return teams.save(team);
    }

    private String importedFieldValue(List<ImportedTeamField> fields, int columnIndex) {
        return fields.stream()
                .filter(field -> field.getColumnIndex() == columnIndex)
                .map(ImportedTeamField::getFieldValue)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
    }

    @PostMapping("/admin/teams/import")
    @Transactional
    public List<Team> importTeams(@RequestHeader("X-Admin-Password") String password,
                                  @RequestPart("file") MultipartFile file) {
        requireAdmin(password);
        if (file.isEmpty()) {
            throw new TeamImportException("Team import file is empty");
        }
        try (BufferedReader reader = new BufferedReader(createTeamImportReader(file))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new TeamImportException("Team import file has no header row");
            }
            List<String> headers = parseCsvLine(headerLine);
            List<String> expectedHeaders = TeamRegistrationFields.LABELS;
            if (headers.size() != expectedHeaders.size()) {
                throw new TeamImportException(
                        "Team import must contain exactly these 19 columns, in this exact order: "
                                + String.join(" | ", expectedHeaders));
            }
            for (int i = 0; i < expectedHeaders.size(); i++) {
                if (!expectedHeaders.get(i).equals(headers.get(i))) {
                    throw new TeamImportException(
                            "Team import column " + (i + 1) + " must be exactly: " + expectedHeaders.get(i)
                                    + ". Use the supplied column names and order; do not add, remove, or rename columns.");
                }
            }
            List<Team> imported = new ArrayList<>();
            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                if (values.size() > expectedHeaders.size()) {
                    throw new TeamImportException(
                            "Team import row " + rowNumber + " contains extra columns; exactly 19 are allowed");
                }
                List<ImportedTeamField> importedFields = new ArrayList<>(expectedHeaders.size());
                for (int column = 0; column < expectedHeaders.size(); column++) {
                    importedFields.add(new ImportedTeamField(column, expectedHeaders.get(column),
                            valueAt(values, column)));
                }
                if (importedFields.stream().anyMatch(this::isInvalidImportedField)) {
                    throw new TeamImportException(
                            "A team field exceeds its allowed length on CSV row " + rowNumber);
                }
                String leaderRegister = importedFieldValue(importedFields, 3);
                if (isIgnoredRegister(leaderRegister)) {
                    throw new TeamImportException(
                            "Missing representative register number on CSV row " + rowNumber);
                }
                String leaderName = importedFieldValue(importedFields, 2);
                if (leaderName.isBlank() || leaderName.trim().equalsIgnoreCase(leaderRegister)) {
                    throw new TeamImportException(
                            "Missing or invalid group leader name on CSV row " + rowNumber);
                }
                List<ImportedParticipant> participants = new ArrayList<>();
                participants.add(new ImportedParticipant(leaderRegister, leaderName));
                for (int memberNumber = 2; memberNumber <= 4; memberNumber++) {
                    int memberFieldIndex = 4 + (memberNumber - 2) * 2;
                    String register = importedFieldValue(importedFields, memberFieldIndex + 1);
                    if (isIgnoredRegister(register)) continue;
                    if (participants.stream().anyMatch(participant ->
                            participant.registerNumber().equalsIgnoreCase(register))) continue;
                    String name = importedFieldValue(importedFields, memberFieldIndex);
                    if (name.isBlank() || name.trim().equalsIgnoreCase(register)) {
                        throw new TeamImportException(
                                "Missing or invalid name for member register number " + register
                                        + " on CSV row " + rowNumber);
                    }
                    participants.add(new ImportedParticipant(register, name));
                }
                Team team = new Team();
                team.setTeamNumber(nextTeamNumber());
                team.setName(String.format("Team %03d", team.getTeamNumber()));
                team.setImportedFields(importedFields);
                for (int i = 0; i < participants.size(); i++) {
                    Student student = new Student();
                    ImportedParticipant participant = participants.get(i);
                    student.setName(participant.name().trim());
                    student.setRegisterNumber(participant.registerNumber().trim());
                    student.setLeader(i == 0);
                    student.setTeam(team);
                    team.getStudents().add(student);
                }
                imported.add(teams.save(team));
            }
            if (imported.isEmpty()) {
                throw new TeamImportException(
                        "The spreadsheet contains headers but no team rows to import");
            }
            return imported;
        } catch (IOException exception) {
            throw new TeamImportException("Could not read team import file", exception);
        }
    }

    private Reader createTeamImportReader(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
        }
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new TeamImportException("The Excel workbook has no worksheets");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            StringBuilder csv = new StringBuilder();
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    csv.append('\n');
                    continue;
                }
                int lastCell = Math.max(0, row.getLastCellNum());
                for (int cellIndex = 0; cellIndex < lastCell; cellIndex++) {
                    if (cellIndex > 0) csv.append(',');
                    String value = formatter.formatCellValue(row.getCell(cellIndex), evaluator);
                    csv.append('"').append(value.replace("\"", "\"\"")).append('"');
                }
                csv.append('\n');
            }
            return new StringReader(csv.toString());
        }
    }

    @PutMapping("/admin/teams/{teamId}")
    public Team updateTeam(@RequestHeader("X-Admin-Password") String password,
                           @PathVariable Long teamId, @RequestBody TeamRequest request) {
        requireAdmin(password);
        Team team = teams.findById(teamId).orElseThrow();
        team.setName(request.name());
        return teams.save(team);
    }

    @PutMapping("/admin/teams/{teamId}/imported-fields")
    @Transactional
    public Team updateImportedTeamFields(@RequestHeader("X-Admin-Password") String password,
                                         @PathVariable Long teamId, @RequestBody ImportedFieldsRequest request) {
        requireAdmin(password);
        if (request == null || request.fields() == null
                || request.fields().size() != TeamRegistrationFields.LABELS.size()
                || request.fields().stream().anyMatch(this::isInvalidImportedField)
                || request.fields().stream().anyMatch(field -> field.getColumnIndex() >= TeamRegistrationFields.LABELS.size()
                || !TeamRegistrationFields.LABELS.get(field.getColumnIndex()).equals(field.getFieldName()))
                || request.fields().stream().map(ImportedTeamField::getColumnIndex).distinct().count()
                != request.fields().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid imported team fields");
        }
        Team team = teams.findById(teamId).orElseThrow();
        team.setImportedFields(request.fields());
        return teams.save(team);
    }

    private boolean isInvalidImportedField(ImportedTeamField field) {
        if (field == null || field.getColumnIndex() < 0 || field.getFieldName() == null
                || field.getFieldName().isBlank() || field.getFieldName().length() > 1000
                || (field.getFieldValue() != null && field.getFieldValue().length() > 5000)) {
            return true;
        }
        int fieldIndex = TeamRegistrationFields.indexOf(field.getFieldName());
        if (fieldIndex < 0 || field.getFieldValue() == null) return false;
        int maxLength = switch (fieldIndex) {
            case 0 -> 100;
            case 1, 11 -> 320;
            case 2, 4, 6, 8 -> 255;
            case 3, 5, 7, 9 -> 80;
            case 10 -> 50;
            case 12 -> 100;
            case 13, 14, 15, 16 -> 255;
            case 17, 18 -> 120;
            default -> 5000;
        };
        return field.getFieldValue().length() > maxLength;
    }

    @DeleteMapping("/admin/teams/{teamId}")
    public void deleteTeam(@RequestHeader("X-Admin-Password") String password, @PathVariable Long teamId) {
        requireAdmin(password);
        submissions.findByTeamId(teamId).ifPresent(submissions::delete);
        teams.deleteById(teamId);
    }

    @DeleteMapping("/admin/teams")
    public void clearTeams(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        submissions.deleteAllInBatch();
        students.deleteAllInBatch();
        teams.deleteAllInBatch();
    }

    @PostMapping("/admin/teams/{teamId}/students")
    public Team addStudent(@RequestHeader("X-Admin-Password") String password,
                           @PathVariable Long teamId, @RequestBody StudentRequest request) {
        requireAdmin(password);
        Team team = teams.findById(teamId).orElseThrow();
        Student student = new Student();
        applyStudent(student, request);
        student.setTeam(team);
        team.getStudents().add(student);
        return teams.save(team);
    }

    @PutMapping("/admin/students/{studentId}")
    public Student updateStudent(@RequestHeader("X-Admin-Password") String password,
                                 @PathVariable Long studentId, @RequestBody StudentRequest request) {
        requireAdmin(password);
        Student student = students.findById(studentId).orElseThrow();
        applyStudent(student, request);
        return students.save(student);
    }

    @DeleteMapping("/admin/students/{studentId}")
    public void deleteStudent(@RequestHeader("X-Admin-Password") String password, @PathVariable Long studentId) {
        requireAdmin(password);
        students.deleteById(studentId);
    }

    @PutMapping("/admin/students/{studentId}/leader")
    @Transactional
    public Team assignLeader(@RequestHeader("X-Admin-Password") String password,
                             @PathVariable Long studentId) {
        requireAdmin(password);
        Student leader = students.findById(studentId).orElseThrow();
        Team team = leader.getTeam();
        if (team == null) {
            team = teams.findAll().stream()
                    .filter(candidate -> candidate.getStudents().stream()
                            .anyMatch(student -> student.getId().equals(studentId)))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student team not found"));
            leader.setTeam(team);
        }
        team.getStudents().forEach(student -> student.setLeader(student.getId().equals(studentId)));
        return teams.save(team);
    }

    @PutMapping("/admin/teams/{teamId}/problem/{problemId}")
    public Team assignProblem(@RequestHeader("X-Admin-Password") String password,
                              @PathVariable Long teamId, @PathVariable Long problemId) {
        requireAdmin(password);
        Team team = teams.findById(teamId).orElseThrow();
        team.setProblem(problems.findById(problemId).orElseThrow());
        return teams.save(team);
    }

    @DeleteMapping("/admin/teams/{teamId}/problem")
    public Team unassignProblem(@RequestHeader("X-Admin-Password") String password, @PathVariable Long teamId) {
        requireAdmin(password);
        Team team = teams.findById(teamId).orElseThrow();
        team.setProblem(null);
        return teams.save(team);
    }

    private void requireAdmin(String password) {
        if (password == null || adminPassword == null || !passwordEncoder.matches(password, adminPassword)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin password");
        }
    }

    private Student authenticateParticipant(String name, String registerNumber) {
        if (name == null || name.isBlank() || registerNumber == null || registerNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid name or register number");
        }
        return students.findByRegisterNumberIgnoreCase(registerNumber.trim())
                .filter(student -> hasParticipantName(student)
                        && student.getName().trim().equalsIgnoreCase(name.trim()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid name or register number"));
    }

    private boolean hasParticipantName(Student student) {
        return student.getName() != null
                && !student.getName().isBlank()
                && !student.getName().trim().equalsIgnoreCase(student.getRegisterNumber());
    }

    private void applyStudent(Student student, StudentRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Participant name is required for sign-in");
        }
        if (request.registerNumber() == null || request.registerNumber().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Register number is required");
        }
        String name = request.name().trim();
        String registerNumber = request.registerNumber().trim();
        if (name.equalsIgnoreCase(registerNumber)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Participant name must be their actual name, not their register number");
        }
        student.setName(name);
        student.setRegisterNumber(registerNumber);
        student.setEmail(request.email() == null || request.email().isBlank() ? null : request.email().trim());
    }

    public record LoginRequest(String name, String registerNumber) {}
    public record StudentTeamResponse(String name, Integer teamNumber, String ownRegisterNumber,
                                      String ownName, String ownEmail, String leaderRegisterNumber, Problem problem,
                                      List<Student> students, boolean leader, Submission submission) {}
    public record ProblemRequest(String title, String statement) {}
    public record TeamRequest(String name, List<ImportedTeamField> importedFields) {}
    public record ImportedFieldsRequest(List<ImportedTeamField> fields) {}
    public record StudentRequest(String name, String registerNumber, String email) {}
    public record SettingsRequest(boolean loginEnabled, String startTime, String endTime) {}
    public record SubmissionRequest(String name, String registerNumber, String googleDriveLink, String githubLink) {}
    public record EnabledRequest(boolean enabled) {}
    private record ImportedParticipant(String registerNumber, String name) {}

    private void saveSetting(String key, String value) {
        settings.save(new AppSetting(key, value));
    }

    private OffsetDateTime parseOptionalTime(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Login " + label + " time must include a timezone", exception);
        }
    }

    private void requireWebUrl(String value, String label) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("URL must be an absolute HTTP(S) URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + " submission must be a valid HTTP or HTTPS URL", exception);
        }
    }

    private Map<String, Object> settingsPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("loginEnabled", Boolean.parseBoolean(settingValue("login-enabled", "true")));
        payload.put("startTime", localTime(settingValue("login-start", "")));
        payload.put("endTime", localTime(settingValue("login-end", "")));
        return payload;
    }

    private String localTime(String value) {
        if (value.isBlank()) return "";
        try {
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(ZoneId.of("Asia/Kolkata"))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Invalid login schedule configured", exception);
        }
    }

    private String settingValue(String key, String fallback) {
        return settings.findById(key).map(AppSetting::getValue).orElse(fallback);
    }

    private void enforceLoginWindow() {
        if (!Boolean.parseBoolean(settingValue("login-enabled", "true"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Participant login is currently disabled");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String start = settingValue("login-start", "");
        String end = settingValue("login-end", "");
        try {
            if (!start.isBlank() && now.isBefore(OffsetDateTime.parse(start))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Participant login has not started");
            }
            if (!end.isBlank() && now.isAfter(OffsetDateTime.parse(end))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Participant login has ended");
            }
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid login schedule configured");
        }
    }

    private int nextTeamNumber() {
        return teams.findAll().stream()
                .map(Team::getTeamNumber)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private static final class TeamImportException extends ResponseStatusException {
        private TeamImportException(String reason) {
            super(HttpStatus.BAD_REQUEST, reason);
        }

        private TeamImportException(String reason, Throwable cause) {
            super(HttpStatus.BAD_REQUEST, reason, cause);
        }
    }

    private static Integer findColumn(Map<String, Integer> columns, String expected) {
        String normalizedExpected = normalize(expected);
        return columns.entrySet().stream()
                .filter(entry -> entry.getKey().equals(normalizedExpected))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static Integer findColumnAny(Map<String, Integer> columns, String... expectedNames) {
        for (String expectedName : expectedNames) {
            Integer column = findColumn(columns, expectedName);
            if (column != null) return column;
        }
        return null;
    }

    private static Integer findColumnContaining(Map<String, Integer> columns, String... fragments) {
        return columns.entrySet().stream()
                .filter(entry -> Arrays.stream(fragments)
                        .allMatch(fragment -> entry.getKey().contains(normalize(fragment))))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String valueAt(List<String> values, int index) {
        return index < values.size() ? values.get(index).trim() : "";
    }

    private static boolean isIgnoredRegister(String value) {
        return value == null || value.isBlank() || value.trim().equalsIgnoreCase("NA");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        values.add(current.toString());
        return values;
    }
}
