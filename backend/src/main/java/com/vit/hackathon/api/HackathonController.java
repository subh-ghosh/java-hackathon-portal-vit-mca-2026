package com.vit.hackathon.api;

import com.vit.hackathon.model.*;
import com.vit.hackathon.repository.*;
import com.vit.hackathon.security.AuthenticationAttemptLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class HackathonController {
    private final TeamRepository teams;
    private final StudentRepository students;
    private final ProblemRepository problems;
    private final AppSettingRepository settings;
    private final SubmissionRepository submissions;
    private final RoundTwoSubmissionRepository roundTwoSubmissions;
    private final AuthenticationAttemptLimiter attemptLimiter;
    private final String adminPassword;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public HackathonController(TeamRepository teams, StudentRepository students, ProblemRepository problems,
                               AppSettingRepository settings,
                               SubmissionRepository submissions,
                               RoundTwoSubmissionRepository roundTwoSubmissions,
                               AuthenticationAttemptLimiter attemptLimiter,
                               @Value("${app.admin.password}") String adminPassword) {
        this.teams = teams;
        this.students = students;
        this.problems = problems;
        this.settings = settings;
        this.submissions = submissions;
        this.roundTwoSubmissions = roundTwoSubmissions;
        this.attemptLimiter = attemptLimiter;
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

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleRequestException(ResponseStatusException exception) {
        String message = exception.getReason();
        if (message == null || message.isBlank()) {
            message = exception.getStatusCode().toString();
        }
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", message));
    }

    @PostMapping("/student/login")
    public StudentTeamResponse login(@RequestBody LoginRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Participant login details are required");
        }
        boolean roundTwoPublished = roundTwoPublished();
        boolean roundTwoOpen = isRoundTwoOpen();
        if (!roundTwoPublished || Boolean.parseBoolean(settingValue("login-paused", "false"))) {
            enforceLoginWindow();
        }
        Team team = authenticateTeam(request.email(), request.contactNumber(), !roundTwoPublished);
        ensureTeamNumber(team);
        String leaderRegisterNumber = team.getStudents().stream()
                .filter(Student::isPresent)
                .filter(Student::isLeader)
                .map(Student::getRegisterNumber)
                .findFirst()
                .orElse(null);
        Problem visibleProblem = team.getProblem() != null && team.getProblem().isEnabled() ? team.getProblem() : null;
        return new StudentTeamResponse(team.getName(), team.getTeamNumber(), leaderRegisterNumber, visibleProblem,
                roundTwoPublished ? team.getStudents() : team.getStudents().stream().filter(Student::isPresent).toList(),
                submissions.findByTeamId(team.getId()).orElse(null), team.getImportedFields(),
                team.isAdvancedToRoundTwo(), roundTwoPublished && team.isAdvancedToRoundTwo()
                        ? team.getProblem() : null,
                team.isAdvancedToRoundTwo() ? roundTwoSubmissions.findByTeamId(team.getId()).orElse(null) : null,
                roundTwoOpen, roundTwoPublished, localTime(settingValue("round-two-deadline", "")),
                team.getRoundTwoStatus());
    }

    @PostMapping("/attendance/login")
    public List<AttendanceTeamResponse> attendanceLogin(@RequestBody AttendanceLoginRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attendance password is required");
        }
        requireAttendance(request.password());
        return attendanceTeams();
    }

    @GetMapping("/attendance/teams")
    public List<AttendanceTeamResponse> attendanceTeams(@RequestHeader("X-Attendance-Password") String password) {
        requireAttendance(password);
        return attendanceTeams();
    }

    @PutMapping("/attendance/students/{studentId}")
    public AttendanceMemberResponse setAttendance(@RequestHeader("X-Attendance-Password") String password,
                                                   @PathVariable Long studentId,
                                                   @RequestBody AttendanceRequest request) {
        requireAttendance(password);
        if (request == null || request.present() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attendance status is required");
        }
        Student student = students.findById(studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Participant not found"));
        student.setPresent(request.present());
        Student saved = students.save(student);
        return attendanceMember(saved);
    }

    @GetMapping("/admin/settings")
    public Map<String, Object> adminSettings(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        return settingsPayload();
    }

    @PutMapping("/admin/settings")
    @Transactional
    public Map<String, Object> updateAdminSettings(@RequestHeader("X-Admin-Password") String password,
                                                    @RequestBody SettingsRequest request) {
        requireAdmin(password);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Login settings are required");
        }
        OffsetDateTime startTime = parseOptionalTime(request.startTime(), "Login start");
        OffsetDateTime endTime = parseOptionalTime(request.endTime(), "Login end");
        if (startTime != null && endTime != null && !endTime.isAfter(startTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Login end time must be after start time");
        }
        saveSetting("login-enabled", Boolean.toString(request.loginEnabled()));
        saveSetting("login-start", request.startTime() == null ? "" : request.startTime());
        saveSetting("login-end", request.endTime() == null ? "" : request.endTime());
        String attendancePassword = request.attendancePassword();
        if (attendancePassword != null && !attendancePassword.isBlank()) {
            if (attendancePassword.length() < 8 || attendancePassword.length() > 72) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Attendance password must be between 8 and 72 characters");
            }
            if (passwordEncoder.matches(attendancePassword, adminPassword)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Attendance password must be different from the admin password");
            }
            saveSetting("attendance-password", passwordEncoder.encode(attendancePassword));
        }
        return settingsPayload();
    }

    @PutMapping("/admin/settings/pause")
    @Transactional
    public Map<String, Object> setParticipantAccessPaused(@RequestHeader("X-Admin-Password") String password,
                                                          @RequestBody PauseRequest request) {
        requireAdmin(password);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pause state is required");
        }
        saveSetting("login-paused", Boolean.toString(request.paused()));
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

    @GetMapping("/admin/round-two/settings")
    public Map<String, Object> roundTwoSettings(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        return roundTwoSettingsPayload();
    }

    @PutMapping("/admin/round-two/settings")
    public Map<String, Object> updateRoundTwoSettings(@RequestHeader("X-Admin-Password") String password,
                                                       @RequestBody RoundTwoSettingsRequest request) {
        requireAdmin(password);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Round 2 settings are required");
        }
        OffsetDateTime deadline = parseOptionalTime(request.deadline(), "Round 2 deadline");
        if (request.enabled() && deadline != null && !deadline.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Round 2 deadline must be in the future");
        }
        saveSetting("round-two-enabled", Boolean.toString(request.enabled()));
        if (request.enabled()) {
            saveSetting("round-two-published", "true");
        }
        saveSetting("round-two-deadline", request.deadline() == null ? "" : request.deadline());
        return roundTwoSettingsPayload();
    }

    @GetMapping("/admin/round-two/submissions")
    public List<RoundTwoSubmission> listRoundTwoSubmissions(
            @RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        return roundTwoSubmissions.findAll();
    }

    @PutMapping("/admin/teams/{teamId}/round-two/qualification")
    @Transactional
    public Team setRoundTwoQualification(@RequestHeader("X-Admin-Password") String password,
                                         @PathVariable Long teamId,
                                         @RequestBody QualificationRequest request) {
        requireAdmin(password);
        if (request == null || request.status() == null
                || !List.of("pending", "advanced", "not_advanced").contains(request.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Round 2 qualification state is required");
        }
        Team team = findTeam(teamId);
        team.setRoundTwoStatus(request.status());
        return teams.save(team);
    }

    @PutMapping("/student/submission")
    public Submission saveSubmission(@RequestBody SubmissionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Submission links are required");
        }
        enforceLoginWindow();
        Team authenticatedTeam = authenticateTeam(request.email(), request.contactNumber());
        requireWebUrl(request.googleDriveLink(), "Google Drive");
        requireWebUrl(request.githubLink(), "GitHub");
        Submission submission = submissions.findByTeamId(authenticatedTeam.getId()).orElseGet(Submission::new);
        Team team = teams.findById(authenticatedTeam.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Participant team not found"));
        submission.setTeam(team);
        submission.setGoogleDriveLink(request.googleDriveLink().trim());
        submission.setGithubLink(request.githubLink().trim());
        submission.touch();
        return submissions.save(submission);
    }

    @PutMapping("/student/round-two/submission")
    public RoundTwoSubmission saveRoundTwoSubmission(@RequestBody SubmissionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Round 2 submission links are required");
        }
        Team authenticatedTeam = authenticateTeam(request.email(), request.contactNumber(), false);
        requireParticipantNotPaused();
        requireRoundTwoOpen();
        if (!authenticatedTeam.isAdvancedToRoundTwo()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This team has not advanced to Round 2");
        }
        if (authenticatedTeam.getProblem() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This team does not have a Round 1 problem assignment to continue in Round 2");
        }
        requireWebUrl(request.googleDriveLink(), "Round 2 Google Drive");
        requireWebUrl(request.githubLink(), "Round 2 GitHub");
        RoundTwoSubmission submission = roundTwoSubmissions.findByTeamId(authenticatedTeam.getId())
                .orElseGet(RoundTwoSubmission::new);
        submission.setTeam(findTeam(authenticatedTeam.getId()));
        submission.setGoogleDriveLink(request.googleDriveLink().trim());
        submission.setGithubLink(request.githubLink().trim());
        submission.touch();
        return roundTwoSubmissions.save(submission);
    }

    @GetMapping("/admin/teams")
    public List<Team> listTeams(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        List<Team> allTeams = teams.findAll();
        allTeams.forEach(this::ensureTeamNumber);
        return allTeams;
    }

    @GetMapping("/admin/teams/{teamId}")
    public Team getTeam(@RequestHeader("X-Admin-Password") String password, @PathVariable Long teamId) {
        requireAdmin(password);
        Team team = teams.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        ensureTeamNumber(team);
        return team;
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
        setProblemFields(problem, request);
        return problems.save(problem);
    }

    @PutMapping("/admin/problems/{problemId}/enabled")
    public Problem setProblemEnabled(@RequestHeader("X-Admin-Password") String password,
                                     @PathVariable Long problemId,
                                     @RequestBody EnabledRequest request) {
        requireAdmin(password);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Problem enabled state is required");
        }
        Problem problem = problems.findById(problemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));
        problem.setEnabled(request.enabled());
        return problems.save(problem);
    }

    @PutMapping("/admin/problems/enabled")
    @Transactional
    public List<Problem> setAllProblemsEnabled(@RequestHeader("X-Admin-Password") String password,
                                               @RequestBody EnabledRequest request) {
        requireAdmin(password);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Problem enabled state is required");
        }
        List<Problem> allProblems = problems.findAll();
        allProblems.forEach(problem -> problem.setEnabled(request.enabled()));
        return problems.saveAll(allProblems);
    }

    @PostMapping("/admin/problems/import")
    @Transactional
    public List<Problem> importProblems(@RequestHeader("X-Admin-Password") String password,
                                        @RequestPart("file") MultipartFile file) {
        requireAdmin(password);
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file is empty");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                file.getInputStream(), StandardCharsets.UTF_8))) {
            List<List<String>> records = parseCsv(reader);
            if (records.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file has no header");
            }
            List<String> headers = records.get(0);
            if (headers.stream().anyMatch(String::isBlank)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV header contains an empty column name");
            }
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                String normalizedHeader = normalize(headers.get(i));
                if (columns.putIfAbsent(normalizedHeader, i) != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "CSV contains duplicate column names: " + headers.get(i));
                }
            }
            Integer titleColumn = findColumnAny(columns, "title", "problem title", "question title");
            Integer statementColumn = findColumnAny(columns, "statement", "problem statement", "question", "description");
            if (statementColumn == null || Objects.equals(titleColumn, statementColumn)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV must contain separate title (optional) and statement columns");
            }
            List<Problem> imported = new ArrayList<>();
            for (int recordIndex = 1; recordIndex < records.size(); recordIndex++) {
                List<String> values = records.get(recordIndex);
                int rowNumber = recordIndex + 1;
                if (values.stream().allMatch(String::isBlank)) continue;
                if (values.size() != headers.size()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Question CSV row " + rowNumber + " has " + values.size()
                                    + " columns; expected " + headers.size());
                }
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
                if (problem.getTitle().length() > 255 || statement.length() > 5000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Question title or statement exceeds the allowed length on CSV row " + rowNumber);
                }
                problem.setStatement(statement);
                imported.add(problem);
            }
            if (imported.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV contains no question rows to import");
            }
            return problems.saveAll(imported);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read CSV file", exception);
        }
    }

    @PutMapping("/admin/teams/random-assignment")
    @Transactional
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
        Problem problem = problems.findById(problemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));
        setProblemFields(problem, request);
        return problems.save(problem);
    }

    @DeleteMapping("/admin/problems/{problemId}")
    @Transactional
    public void deleteProblem(@RequestHeader("X-Admin-Password") String password, @PathVariable Long problemId) {
        requireAdmin(password);
        Problem problem = problems.findById(problemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));
        teams.findAll().stream().filter(team -> problem.equals(team.getProblem())).forEach(team -> {
            team.setProblem(null);
            teams.save(team);
        });
        problems.delete(problem);
    }

    @DeleteMapping("/admin/problems")
    @Transactional
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
        if (request == null || request.importedFields() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter all 19 registration fields to create a team");
        }
        Team team = new Team();
        List<ImportedTeamField> fields = request.importedFields();
        if (fields.size() != TeamRegistrationFields.LABELS.size() || fields.stream().anyMatch(field -> field == null
                || field.getColumnIndex() < 0 || field.getColumnIndex() >= TeamRegistrationFields.LABELS.size()
                || !TeamRegistrationFields.LABELS.get(field.getColumnIndex()).equals(field.getFieldName())
                || isInvalidImportedField(field))
                || fields.stream().map(ImportedTeamField::getColumnIndex).distinct().count() != fields.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "All 19 team form fields must be provided with unique indexes");
        }
        String leaderName = importedFieldValue(fields, 2).trim();
        String leaderRegister = normalizeRegisterNumber(importedFieldValue(fields, 3));
        if (leaderName.isBlank() != leaderRegister.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "For the group leader, enter both name and register number or leave both blank");
        }
        if (!leaderRegister.isBlank() && leaderName.equalsIgnoreCase(leaderRegister)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Group leader name must be their actual name, not their register number");
        }
        String teamEmail = normalizeTeamUsername(importedFieldValue(fields, 11));
        List<ImportedParticipant> participants = new ArrayList<>();
        if (!leaderRegister.isBlank()) participants.add(new ImportedParticipant(leaderRegister, leaderName));
        for (int member = 2; member <= 4; member++) {
            String memberName = importedFieldValue(fields, 4 + (member - 2) * 2).trim();
            String memberRegister = normalizeRegisterNumber(importedFieldValue(fields, 5 + (member - 2) * 2));
            if (memberName.isBlank() != memberRegister.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "For team member " + member + ", enter both the name and register number or leave both blank");
            }
            if (!memberRegister.isBlank()) {
                if (memberName.equalsIgnoreCase(memberRegister)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Team member " + member + " name must be their actual name, not their register number");
                }
                participants.add(new ImportedParticipant(memberRegister, memberName));
            }
        }
        if (!isValidEmail(teamEmail)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid primary email is required for team login");
        }
        requirePrimaryContactNumber(importedFieldValue(fields, 10));
        ensureTeamContactNumberAvailable(importedFieldValue(fields, 10), null, null);
        ensureTeamEmailAvailable(teamEmail, null, null);
        Set<String> participantNumbers = new HashSet<>();
        for (ImportedParticipant participant : participants) {
            String registerNumber = normalizeRegisterNumber(participant.registerNumber());
            if (!participantNumbers.add(registerNumber)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Register number " + registerNumber + " appears more than once in this team");
            }
            ensureLoginIdentityAvailable(teamEmail, registerNumber, null);
        }
        normalizeRegistrationFieldValues(fields);
        team.setImportedFields(fields);
        for (int i = 0; i < participants.size(); i++) {
            ImportedParticipant participant = participants.get(i);
            Student student = new Student();
            student.setName(participant.name());
            student.setRegisterNumber(participant.registerNumber());
            student.setEmail(!leaderRegister.isBlank() && i == 0
                    ? importedFieldValue(fields, 11).trim() : null);
            student.setLeader(!leaderRegister.isBlank() && i == 0);
            student.setTeam(team);
            team.getStudents().add(student);
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Team import file is empty");
        }
        try (BufferedReader reader = new BufferedReader(createTeamImportReader(file))) {
            List<List<String>> records = parseCsv(reader);
            if (records.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Team import file has no header row");
            }
            List<String> headers = records.get(0);
            List<String> expectedHeaders = TeamRegistrationFields.LABELS;
            Map<Integer, Integer> sourceColumns = new HashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                int fieldIndex = TeamRegistrationFields.indexOf(headers.get(column));
                if (fieldIndex < 0) continue;
                if (sourceColumns.putIfAbsent(fieldIndex, column) != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Team import contains duplicate columns for: " + expectedHeaders.get(fieldIndex));
                }
            }
            for (int requiredFieldIndex : List.of(10, 11)) {
                if (!sourceColumns.containsKey(requiredFieldIndex)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Team import is missing the required column: " + expectedHeaders.get(requiredFieldIndex));
                }
            }
            List<Team> imported = new ArrayList<>();
            Map<LoginIdentity, Integer> importedLoginRows = new HashMap<>();
            Map<String, Integer> importedTeamEmails = new HashMap<>();
            Map<String, Integer> importedTeamContacts = new HashMap<>();
            List<Team> existingTeams = teams.findAll();
            Set<String> existingTeamEmails = existingTeams.stream()
                    .map(team -> normalizeTeamUsername(team.getPrimaryEmail()))
                    .filter(email -> !email.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> existingTeamContacts = existingTeams.stream()
                    .map(team -> normalizeContactNumber(team.getPrimaryContactNumber()))
                    .filter(contact -> !contact.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            for (int recordIndex = 1; recordIndex < records.size(); recordIndex++) {
                List<String> values = records.get(recordIndex);
                int rowNumber = recordIndex + 1;
                if (values.stream().allMatch(String::isBlank)) continue;
                List<ImportedTeamField> importedFields = new ArrayList<>(expectedHeaders.size());
                for (int column = 0; column < expectedHeaders.size(); column++) {
                    Integer sourceColumn = sourceColumns.get(column);
                    String fieldValue = sourceColumn == null ? "" : valueAt(values, sourceColumn);
                    importedFields.add(new ImportedTeamField(column, expectedHeaders.get(column), fieldValue));
                }
                String teamEmail = normalizeTeamUsername(importedFieldValue(importedFields, 11));
                if (!isValidEmail(teamEmail)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Missing or invalid primary email on CSV row " + rowNumber
                                    + ". Participants use the team's primary email to sign in.");
                }
                requirePrimaryContactNumber(importedFieldValue(importedFields, 10), rowNumber);
                Integer previousEmailRow = importedTeamEmails.putIfAbsent(teamEmail, rowNumber);
                if (previousEmailRow != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The primary email appears more than once in the upload (CSV rows "
                                    + previousEmailRow + " and " + rowNumber + "). Each team must have a unique email.");
                }
                if (!existingTeamEmails.add(teamEmail)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The primary email is already assigned to another team on CSV row " + rowNumber
                                    + ". Each team must use a unique email.");
                }
                String teamContact = importedFieldValue(importedFields, 10);
                String normalizedTeamContact = normalizeContactNumber(teamContact);
                Integer previousContactRow = importedTeamContacts.putIfAbsent(normalizedTeamContact, rowNumber);
                if (previousContactRow != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The primary contact number appears more than once in the upload (CSV rows "
                                    + previousContactRow + " and " + rowNumber
                                    + "). Each team must have a unique contact number.");
                }
                if (!existingTeamContacts.add(normalizedTeamContact)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The primary contact number is already assigned to another team on CSV row " + rowNumber
                                    + ". Each team must use a unique contact number.");
                }
                if (importedFields.stream().anyMatch(this::isInvalidImportedField)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "A team field exceeds its allowed length on CSV row " + rowNumber);
                }
                List<ImportedParticipant> participants = new ArrayList<>();
                for (int memberNumber = 1; memberNumber <= 4; memberNumber++) {
                    int memberFieldIndex = memberNumber == 1 ? 2 : 4 + (memberNumber - 2) * 2;
                    String register = normalizeRegisterNumber(importedFieldValue(importedFields, memberFieldIndex + 1));
                    String name = importedFieldValue(importedFields, memberFieldIndex).trim();
                    if (register.isBlank() && name.isBlank()) continue;
                    if (register.isBlank() != name.isBlank()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "For team member " + memberNumber + " on CSV row " + rowNumber
                                        + ", enter both the name and register number or leave both blank");
                    }
                    if (name.isBlank() || name.trim().equalsIgnoreCase(register)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Missing or invalid name for member register number " + register
                                        + " on CSV row " + rowNumber);
                    }
                    participants.add(new ImportedParticipant(register, name));
                }
                for (ImportedParticipant participant : participants) {
                    String register = normalizeRegisterNumber(participant.registerNumber());
                    LoginIdentity identity = new LoginIdentity(teamEmail, register);
                    Integer previousRow = importedLoginRows.putIfAbsent(identity, rowNumber);
                    if (previousRow != null) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "The team email and register number combination appears more than once in the upload "
                                        + "(CSV rows " + previousRow + " and " + rowNumber + "). "
                                        + "Each participant login combination must identify one person.");
                    }
                    ensureLoginIdentityAvailable(teamEmail, register, null, rowNumber);
                }
                Team team = new Team();
                team.setTeamNumber(nextTeamNumber());
                team.setName(String.format("Team %03d", team.getTeamNumber()));
                normalizeRegistrationFieldValues(importedFields);
                team.setImportedFields(importedFields);
                for (int i = 0; i < participants.size(); i++) {
                    Student student = new Student();
                    ImportedParticipant participant = participants.get(i);
                    student.setName(participant.name().trim());
                    student.setRegisterNumber(participant.registerNumber().trim());
                    if (!normalizeRegisterNumber(importedFieldValue(importedFields, 3)).isBlank()
                            && i == 0) {
                        student.setEmail(blankToNull(importedFieldValue(importedFields, 11)));
                    }
                    student.setLeader(!participants.isEmpty()
                            && !normalizeRegisterNumber(importedFieldValue(importedFields, 3)).isBlank() && i == 0);
                    student.setTeam(team);
                    team.getStudents().add(student);
                }
                imported.add(teams.save(team));
            }
            if (imported.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The spreadsheet contains headers but no team rows to import");
            }
            return imported;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read team import file. Upload a valid CSV or Excel workbook.", exception);
        }
    }

    private Reader createTeamImportReader(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
        }
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The Excel workbook has no worksheets");
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
        if (request == null || request.name() == null || request.name().isBlank()
                || request.name().trim().length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Team name is required and must be 120 characters or fewer");
        }
        Team team = teams.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        team.setName(request.name().trim());
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
        Team team = teams.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        normalizeRegistrationFieldValues(request.fields());
        validateRegistrationParticipants(request.fields(), team);
        syncRegistrationParticipants(team, request.fields());
        team.setImportedFields(request.fields());
        return teams.save(team);
    }

    private void validateRegistrationParticipants(List<ImportedTeamField> fields, Team team) {
        String teamEmail = normalizeTeamUsername(importedFieldValue(fields, 11));
        if (!isValidEmail(teamEmail)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid primary email is required for team login");
        }
        requirePrimaryContactNumber(importedFieldValue(fields, 10));
        ensureTeamContactNumberAvailable(importedFieldValue(fields, 10), team.getId(), null);
        ensureTeamEmailAvailable(teamEmail, team.getId(), null);

        Set<String> registerNumbers = new HashSet<>();
        for (int slot = 0; slot < 4; slot++) {
            int nameIndex = slot == 0 ? 2 : 4 + (slot - 1) * 2;
            int registerIndex = nameIndex + 1;
            String name = importedFieldValue(fields, nameIndex).trim();
            String register = normalizeRegisterNumber(importedFieldValue(fields, registerIndex));
            if (name.isBlank() != register.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "For team member " + (slot + 1)
                                + ", enter both the name and register number or leave both blank");
            }
            if (register.isBlank()) continue;
            if (name.isBlank() || name.equalsIgnoreCase(register)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Participant names must be actual names and register numbers must be present");
            }
            if (!registerNumbers.add(register)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Register number " + register + " appears more than once in this team");
            }
            boolean assignedElsewhere = students.findByLoginUsernameAndRegisterNumber(teamEmail, register).stream()
                    .anyMatch(existing -> existing.getTeam() == null
                            || !Objects.equals(existing.getTeam().getId(), team.getId()));
            if (assignedElsewhere) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The team email and register number combination is already assigned to another team");
            }
        }
    }

    private void normalizeRegistrationFieldValues(List<ImportedTeamField> fields) {
        for (int registerIndex : new int[]{3, 5, 7, 9}) {
            setImportedFieldValue(fields, registerIndex,
                    normalizeRegisterNumber(importedFieldValue(fields, registerIndex)));
        }
        setImportedFieldValue(fields, 11, importedFieldValue(fields, 11).trim());
    }

    private void syncRegistrationParticipants(Team team, List<ImportedTeamField> fields) {
        List<Student> currentStudents = new ArrayList<>(team.getStudents());
        List<Student> leaders = currentStudents.stream().filter(Student::isLeader).toList();
        if (leaders.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This team has multiple leaders; resolve the roster before editing registration details");
        }
        int[] nameIndexes = {2, 4, 6, 8};
        int[] registerIndexes = {3, 5, 7, 9};
        Student leader = leaders.isEmpty()
                ? findTeamStudent(currentStudents, Set.of(), normalizeRegisterNumber(
                        importedFieldValue(team.getImportedFields(), registerIndexes[0])))
                : leaders.get(0);
        List<Student> matched = new ArrayList<>();
        matched.add(leader);
        Set<Long> usedStudentIds = new HashSet<>();
        if (leader != null) usedStudentIds.add(leader.getId());
        for (int slot = 1; slot < 4; slot++) {
            String oldRegister = normalizeRegisterNumber(
                    importedFieldValue(team.getImportedFields(), registerIndexes[slot]));
            String newRegister = normalizeRegisterNumber(importedFieldValue(fields, registerIndexes[slot]));
            Student member = findTeamStudent(currentStudents, usedStudentIds, oldRegister);
            if (member == null && !newRegister.isBlank()) {
                member = findTeamStudent(currentStudents, usedStudentIds, newRegister);
            }
            matched.add(member);
            if (member != null) usedStudentIds.add(member.getId());
        }
        for (int slot = 0; slot < 4; slot++) {
            String name = importedFieldValue(fields, nameIndexes[slot]).trim();
            String register = normalizeRegisterNumber(importedFieldValue(fields, registerIndexes[slot]));
            Student student = matched.get(slot);
            if (register.isBlank()) {
                String oldRegister = normalizeRegisterNumber(
                        importedFieldValue(team.getImportedFields(), registerIndexes[slot]));
                if (student != null && !oldRegister.isBlank()) {
                    team.getStudents().remove(student);
                    student.setTeam(null);
                }
                continue;
            }
            if (student == null) {
                student = new Student();
                student.setTeam(team);
                team.getStudents().add(student);
            }
            student.setName(name);
            student.setRegisterNumber(register);
            student.setLeader(slot == 0);
            if (slot == 0) student.setEmail(blankToNull(importedFieldValue(fields, 11)));
        }
    }

    private Student findTeamStudent(List<Student> teamStudents, Set<Long> usedStudentIds, String registerNumber) {
        if (registerNumber.isBlank()) return null;
        return teamStudents.stream()
                .filter(student -> !usedStudentIds.contains(student.getId()))
                .filter(student -> !student.isLeader())
                .filter(student -> registerNumber.equals(normalizeRegisterNumber(student.getRegisterNumber())))
                .findFirst()
                .orElse(null);
    }

    private String blankToNull(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private void setProblemFields(Problem problem, ProblemRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()
                || request.statement() == null || request.statement().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Problem title and statement are required");
        }
        String title = request.title().trim();
        String statement = request.statement().trim();
        if (title.length() > 255 || statement.length() > 5000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Problem title must be 255 characters or fewer and statement 5000 characters or fewer");
        }
        problem.setTitle(title);
        problem.setStatement(statement);
    }

    private void setImportedFieldValue(List<ImportedTeamField> fields, int columnIndex, String value) {
        fields.stream().filter(field -> field.getColumnIndex() == columnIndex)
                .findFirst().ifPresent(field -> field.setFieldValue(value == null ? "" : value));
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
    @Transactional
    public void deleteTeam(@RequestHeader("X-Admin-Password") String password, @PathVariable Long teamId) {
        requireAdmin(password);
        Team team = teams.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        submissions.findByTeamId(teamId).ifPresent(submissions::delete);
        teams.delete(team);
    }

    @DeleteMapping("/admin/teams")
    @Transactional
    public void clearTeams(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        submissions.deleteAllInBatch();
        roundTwoSubmissions.deleteAllInBatch();
        students.deleteAllInBatch();
        teams.deleteAllInBatch();
    }

    @PostMapping("/admin/teams/{teamId}/students")
    @Transactional
    public Team addStudent(@RequestHeader("X-Admin-Password") String password,
                           @PathVariable Long teamId, @RequestBody StudentRequest request) {
        requireAdmin(password);
        Team team = teams.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        Student student = new Student();
        applyStudent(student, request);
        if (!isValidEmail(team.getPrimaryEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Set the team's primary email before adding participants so they can sign in");
        }
        ensureLoginIdentityAvailable(team.getParticipantLoginIdentifier(), student.getRegisterNumber(), null);
        List<ImportedTeamField> fields = team.getImportedFields();
        int availableNameIndex = -1;
        for (int nameIndex : new int[]{4, 6, 8}) {
            if (importedFieldValue(fields, nameIndex + 1).isBlank()) {
                availableNameIndex = nameIndex;
                break;
            }
        }
        if (availableNameIndex < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A team can have at most four participants; remove a member before adding another");
        }
        setImportedFieldValue(fields, availableNameIndex, student.getName());
        setImportedFieldValue(fields, availableNameIndex + 1, student.getRegisterNumber());
        team.setImportedFields(fields);
        student.setTeam(team);
        team.getStudents().add(student);
        return teams.save(team);
    }

    @PutMapping("/admin/students/{studentId}")
    @Transactional
    public Student updateStudent(@RequestHeader("X-Admin-Password") String password,
                                 @PathVariable Long studentId, @RequestBody StudentRequest request) {
        requireAdmin(password);
        Student student = students.findById(studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Participant not found"));
        String previousRegister = normalizeRegisterNumber(student.getRegisterNumber());
        Student candidate = new Student();
        applyStudent(candidate, request);
        if (student.getTeam() != null) {
            ensureLoginIdentityAvailable(student.getTeam().getParticipantLoginIdentifier(),
                    candidate.getRegisterNumber(), student.getId());
        }
        String candidateEmail = candidate.getEmail();
        if (student.getTeam() != null && student.isLeader()) {
            if (candidateEmail == null || candidateEmail.isBlank()) {
                candidateEmail = student.getEmail();
            }
            if (!isValidEmail(candidateEmail)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A valid primary email is required for the team leader");
            }
            ensureTeamEmailAvailable(candidateEmail, student.getTeam().getId(), null);
        }
        student.setName(candidate.getName());
        student.setRegisterNumber(candidate.getRegisterNumber());
        student.setEmail(candidateEmail);
        if (student.getTeam() != null) {
            syncStudentRegistrationFields(student.getTeam(), student, previousRegister);
        }
        return students.save(student);
    }

    @DeleteMapping("/admin/students/{studentId}")
    @Transactional
    public void deleteStudent(@RequestHeader("X-Admin-Password") String password, @PathVariable Long studentId) {
        requireAdmin(password);
        Student student = students.findById(studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Participant not found"));
        if (student.isLeader()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Assign another team member as leader before deleting this participant");
        }
        Team team = student.getTeam();
        if (team != null) {
            clearRegistrationSlot(team, student);
            team.getStudents().remove(student);
            student.setTeam(null);
            teams.save(team);
        } else {
            students.delete(student);
        }
    }

    private void clearRegistrationSlot(Team team, Student student) {
        List<ImportedTeamField> fields = team.getImportedFields();
        String register = normalizeRegisterNumber(student.getRegisterNumber());
        for (int registerIndex : new int[]{5, 7, 9}) {
            if (register.equals(normalizeRegisterNumber(importedFieldValue(fields, registerIndex)))) {
                setImportedFieldValue(fields, registerIndex - 1, "");
                setImportedFieldValue(fields, registerIndex, "");
                team.setImportedFields(fields);
                return;
            }
        }
    }

    private void syncStudentRegistrationFields(Team team, Student student, String previousRegister) {
        List<ImportedTeamField> fields = team.getImportedFields();
        if (student.isLeader()) {
            setImportedFieldValue(fields, 11, student.getEmail());
            team.setImportedFields(fields);
            syncLeaderRegistrationFields(team, student);
            return;
        }
        for (int registerIndex : new int[]{5, 7, 9}) {
            if (previousRegister.equals(normalizeRegisterNumber(importedFieldValue(fields, registerIndex)))) {
                setImportedFieldValue(fields, registerIndex - 1, student.getName());
                setImportedFieldValue(fields, registerIndex, student.getRegisterNumber());
                team.setImportedFields(fields);
                return;
            }
        }
    }

    @PutMapping("/admin/students/{studentId}/leader")
    @Transactional
    public Team assignLeader(@RequestHeader("X-Admin-Password") String password,
                             @PathVariable Long studentId) {
        requireAdmin(password);
        Student leader = students.findById(studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Participant not found"));
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
        syncLeaderRegistrationFields(team, leader);
        return teams.save(team);
    }

    private void syncLeaderRegistrationFields(Team team, Student leader) {
        List<Student> members = team.getStudents().stream()
                .filter(student -> !student.getId().equals(leader.getId()))
                .toList();
        if (members.size() > 3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A team cannot have more than four participants");
        }
        List<ImportedTeamField> fields = team.getImportedFields();
        setImportedFieldValue(fields, 2, leader.getName());
        setImportedFieldValue(fields, 3, leader.getRegisterNumber());
        for (int slot = 1; slot <= 3; slot++) {
            int nameIndex = 4 + (slot - 1) * 2;
            int registerIndex = nameIndex + 1;
            Student member = slot <= members.size() ? members.get(slot - 1) : null;
            setImportedFieldValue(fields, nameIndex, member == null ? "" : member.getName());
            setImportedFieldValue(fields, registerIndex, member == null ? "" : member.getRegisterNumber());
        }
        team.setImportedFields(fields);
    }

    @PutMapping("/admin/teams/{teamId}/problem/{problemId}")
    @Transactional
    public Team assignProblem(@RequestHeader("X-Admin-Password") String password,
                              @PathVariable Long teamId, @PathVariable Long problemId) {
        requireAdmin(password);
        Team team = teams.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        team.setProblem(problems.findById(problemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found")));
        return teams.save(team);
    }

    @DeleteMapping("/admin/teams/{teamId}/problem")
    @Transactional
    public Team unassignProblem(@RequestHeader("X-Admin-Password") String password, @PathVariable Long teamId) {
        requireAdmin(password);
        Team team = teams.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
        team.setProblem(null);
        return teams.save(team);
    }

    private void requireAdmin(String password) {
        String clientIdentity = adminClientIdentity();
        attemptLimiter.checkAdmin(clientIdentity);
        if (password == null || adminPassword == null || !passwordEncoder.matches(password, adminPassword)) {
            attemptLimiter.adminFailed(clientIdentity);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin password");
        }
        attemptLimiter.adminSucceeded(clientIdentity);
    }

    private void requireAttendance(String password) {
        String clientIdentity = adminClientIdentity();
        attemptLimiter.checkAttendance(clientIdentity);
        String encodedPassword = settingValue("attendance-password", "");
        if (encodedPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Attendance access is not configured. Ask an administrator to set it up.");
        }
        if (password == null || !passwordEncoder.matches(password, encodedPassword)) {
            attemptLimiter.attendanceFailed(clientIdentity);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid attendance password");
        }
        attemptLimiter.attendanceSucceeded(clientIdentity);
    }

    private List<AttendanceTeamResponse> attendanceTeams() {
        return teams.findAll().stream()
                .sorted(Comparator.comparing(Team::getTeamNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(team -> new AttendanceTeamResponse(team.getId(), team.getName(), team.getTeamNumber(),
                        team.getStudents().stream().map(this::attendanceMember).toList()))
                .toList();
    }

    private AttendanceMemberResponse attendanceMember(Student student) {
        return new AttendanceMemberResponse(student.getId(), student.getName(), student.getRegisterNumber(),
                student.isLeader(), student.isPresent());
    }

    private String adminClientIdentity() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String remoteAddress = attributes.getRequest().getRemoteAddr();
            if (remoteAddress != null && !remoteAddress.isBlank()) {
                return remoteAddress;
            }
        }
        return "unknown-client";
    }

    private Team authenticateTeam(String email, String contactNumber) {
        return authenticateTeam(email, contactNumber, true);
    }

    private Team authenticateTeam(String email, String contactNumber, boolean requirePresentMember) {
        String identity = normalizeTeamUsername(email);
        String normalizedContactNumber = normalizeContactNumber(contactNumber);
        if (identity.isBlank() || normalizedContactNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid team email or primary contact number");
        }
        attemptLimiter.checkParticipant(identity, normalizedContactNumber);
        List<Team> matches = teams.findAllByPrimaryEmailIgnoreCase(identity).stream()
                .filter(team -> normalizeContactNumber(team.getPrimaryContactNumber())
                        .equals(normalizedContactNumber))
                .toList();
        if (matches.size() != 1) {
            attemptLimiter.participantFailed(identity, normalizedContactNumber);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid team email or primary contact number");
        }
        attemptLimiter.participantSucceeded(identity, normalizedContactNumber);
        Team team = matches.get(0);
        if (requirePresentMember && team.getStudents().stream().noneMatch(Student::isPresent)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No team members are checked in. Ask the Attendance Coordinator to mark at least one member present.");
        }
        return team;
    }

    private void applyStudent(Student student, StudentRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Participant details are required");
        }
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
        if (name.length() > 255 || registerNumber.length() > 80
                || (request.email() != null && request.email().trim().length() > 320)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Participant name, register number, or email exceeds its allowed length");
        }
        student.setName(name);
        student.setRegisterNumber(registerNumber);
        student.setEmail(request.email() == null || request.email().isBlank() ? null : request.email().trim());
    }

    private void ensureLoginIdentityAvailable(String username, String registerNumber, Long exceptStudentId) {
        ensureLoginIdentityAvailable(username, registerNumber, exceptStudentId, null);
    }

    private void ensureLoginIdentityAvailable(String username, String registerNumber, Long exceptStudentId,
                                              Integer csvRow) {
        String normalizedUsername = normalizeTeamUsername(username);
        String normalizedRegister = normalizeRegisterNumber(registerNumber);
        students.findByLoginUsernameAndRegisterNumber(normalizedUsername, normalizedRegister).stream()
                .filter(existing -> !Objects.equals(existing.getId(), exceptStudentId))
                .findFirst()
                .ifPresent(existing -> {
                    String rowMessage = csvRow == null ? "" : " on CSV row " + csvRow;
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The team email and register number combination is already assigned"
                                            + rowMessage + ". Use the matching team email or correct the duplicate.");
                });
    }

    private boolean isValidEmail(String email) {
        return email != null && email.trim().length() <= 320
                && email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private void ensureTeamEmailAvailable(String email, Long exceptTeamId, Integer csvRow) {
        String normalizedEmail = normalizeTeamUsername(email);
        teams.findAllByPrimaryEmailIgnoreCase(normalizedEmail).stream()
                .filter(existing -> !Objects.equals(existing.getId(), exceptTeamId))
                .findFirst()
                .ifPresent(existing -> {
                    String rowMessage = csvRow == null ? "" : " on CSV row " + csvRow;
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The primary email is already assigned to another team" + rowMessage
                                    + ". Each team must use a unique email.");
                });
    }

    private void ensureTeamContactNumberAvailable(String contactNumber, Long exceptTeamId, Integer csvRow) {
        String normalizedContactNumber = normalizeContactNumber(contactNumber);
        teams.findAll().stream()
                .filter(existing -> !Objects.equals(existing.getId(), exceptTeamId))
                .filter(existing -> normalizeContactNumber(existing.getPrimaryContactNumber())
                        .equals(normalizedContactNumber))
                .findFirst()
                .ifPresent(existing -> {
                    String rowMessage = csvRow == null ? "" : " on CSV row " + csvRow;
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The primary contact number is already assigned to another team" + rowMessage
                                    + ". Each team must use a unique contact number.");
                });
    }

    private String normalizeTeamUsername(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRegisterNumber(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeContactNumber(String value) {
        if (value == null || !value.trim().matches("[+()\\d\\s.-]+")) return "";
        return value.replaceAll("\\D", "");
    }

    private void requirePrimaryContactNumber(String contactNumber) {
        requirePrimaryContactNumber(contactNumber, null);
    }

    private void requirePrimaryContactNumber(String contactNumber, Integer csvRow) {
        String normalizedContact = normalizeContactNumber(contactNumber);
        if (normalizedContact.length() < 7 || normalizedContact.length() > 15) {
            String rowMessage = csvRow == null ? "" : " on CSV row " + csvRow;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid primary contact number is required for team login" + rowMessage);
        }
    }

    public record LoginRequest(String email, String contactNumber) {}
    public record StudentTeamResponse(String name, Integer teamNumber, String leaderRegisterNumber, Problem problem,
                                      List<Student> students, Submission submission,
                                      List<ImportedTeamField> importedFields, boolean advancedToRoundTwo,
                                      Problem roundTwoProblem, RoundTwoSubmission roundTwoSubmission,
                                      boolean roundTwoOpen, boolean roundTwoPublished,
                                      String roundTwoDeadline, String roundTwoStatus) {}
    public record ProblemRequest(String title, String statement) {}
    public record TeamRequest(String name, List<ImportedTeamField> importedFields) {}
    public record ImportedFieldsRequest(List<ImportedTeamField> fields) {}
    public record StudentRequest(String name, String registerNumber, String email) {}
    public record SettingsRequest(boolean loginEnabled, String startTime, String endTime, String attendancePassword) {}
    public record AttendanceLoginRequest(String password) {}
    public record AttendanceRequest(Boolean present) {}
    public record AttendanceMemberResponse(Long id, String name, String registerNumber, boolean leader,
                                           boolean present) {}
    public record AttendanceTeamResponse(Long id, String name, Integer teamNumber,
                                         List<AttendanceMemberResponse> students) {}
    public record PauseRequest(boolean paused) {}
    public record SubmissionRequest(String email, String contactNumber,
                                    String googleDriveLink, String githubLink) {}
    public record EnabledRequest(boolean enabled) {}
    public record RoundTwoSettingsRequest(boolean enabled, String deadline) {}
    public record QualificationRequest(String status) {}
    private record ImportedParticipant(String registerNumber, String name) {}
    private record LoginIdentity(String username, String registerNumber) {}

    private void saveSetting(String key, String value) {
        settings.save(new AppSetting(key, value));
    }

    private OffsetDateTime parseOptionalTime(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + " time must include a timezone", exception);
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
        payload.put("accessPaused", Boolean.parseBoolean(settingValue("login-paused", "false")));
        payload.put("startTime", localTime(settingValue("login-start", "")));
        payload.put("endTime", localTime(settingValue("login-end", "")));
        payload.put("attendancePasswordConfigured", !settingValue("attendance-password", "").isBlank());
        return payload;
    }

    private Map<String, Object> roundTwoSettingsPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("enabled", Boolean.parseBoolean(settingValue("round-two-enabled", "false")));
        payload.put("published", roundTwoPublished());
        payload.put("deadline", localTime(settingValue("round-two-deadline", "")));
        payload.put("open", isRoundTwoOpen());
        return payload;
    }

    private boolean roundTwoPublished() {
        return Boolean.parseBoolean(settingValue("round-two-published", "false"));
    }

    private boolean isRoundTwoOpen() {
        if (!Boolean.parseBoolean(settingValue("round-two-enabled", "false"))) return false;
        OffsetDateTime deadline = parseOptionalTime(settingValue("round-two-deadline", ""),
                "Round 2 deadline");
        return deadline == null || deadline.isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void requireRoundTwoOpen() {
        if (!isRoundTwoOpen()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Round 2 submissions are currently closed");
        }
    }

    private void requireParticipantNotPaused() {
        if (Boolean.parseBoolean(settingValue("login-paused", "false"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Participant access is temporarily paused by the coordinator");
        }
    }

    private Team findTeam(Long teamId) {
        return teams.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
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
        requireParticipantNotPaused();
        String start = settingValue("login-start", "");
        String end = settingValue("login-end", "");
        boolean scheduled = !start.isBlank() || !end.isBlank();
        if (!scheduled && !Boolean.parseBoolean(settingValue("login-enabled", "true"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Participant login is currently disabled");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
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

    private static List<List<String>> parseCsv(Reader reader) throws IOException {
        List<List<String>> records = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        boolean firstCharacter = true;
        int character;
        while ((character = reader.read()) != -1) {
            char value = (char) character;
            if (firstCharacter) {
                firstCharacter = false;
                if (value == '\uFEFF') continue;
            }
            if (quoted) {
                if (value == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        current.append('"');
                    } else {
                        quoted = false;
                        closedQuote = true;
                        if (next != -1) reader.reset();
                    }
                } else {
                    current.append(value);
                }
            } else if (closedQuote && value == ',') {
                values.add(current.toString());
                current.setLength(0);
                closedQuote = false;
            } else if (closedQuote && (value == '\n' || value == '\r')) {
                if (value == '\r') {
                    reader.mark(1);
                    if (reader.read() != '\n') reader.reset();
                }
                values.add(current.toString());
                records.add(values);
                values = new ArrayList<>();
                current.setLength(0);
                closedQuote = false;
            } else if (closedQuote && Character.isWhitespace(value)) {
                continue;
            } else if (closedQuote) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Malformed CSV: unexpected character after a quoted field");
            } else if (value == '"') {
                if (current.length() != 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Malformed CSV: quote inside an unquoted field");
                }
                quoted = true;
            } else if (value == ',') {
                values.add(current.toString());
                current.setLength(0);
            } else if (value == '\n' || value == '\r') {
                if (value == '\r') {
                    reader.mark(1);
                    if (reader.read() != '\n') reader.reset();
                }
                values.add(current.toString());
                records.add(values);
                values = new ArrayList<>();
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        if (quoted) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed CSV: unterminated quoted field");
        }
        if (!values.isEmpty() || current.length() > 0 || closedQuote) {
            values.add(current.toString());
            records.add(values);
        }
        return records;
    }
}
