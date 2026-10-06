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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class HackathonController {
    private final TeamRepository teams;
    private final StudentRepository students;
    private final ProblemRepository problems;
    private final AppSettingRepository settings;
    private final String adminPassword;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public HackathonController(TeamRepository teams, StudentRepository students, ProblemRepository problems,
                               AppSettingRepository settings,
                               @Value("${app.admin.password}") String adminPassword) {
        this.teams = teams;
        this.students = students;
        this.problems = problems;
        this.settings = settings;
        this.adminPassword = adminPassword;
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

    @PostMapping("/student/login")
    public StudentTeamResponse login(@RequestBody LoginRequest request) {
        Student student = students.findByRegisterNumberIgnoreCase(request.registerNumber())
                .filter(item -> verifyStudentPassword(item, request.password()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid register number or password"));
        Team team = teams.findById(student.getTeam().getId()).orElseThrow();
        ensureTeamNumber(team);
        String leaderRegisterNumber = team.getStudents().stream()
                .filter(Student::isLeader)
                .map(Student::getRegisterNumber)
                .findFirst()
                .orElse(null);
        return new StudentTeamResponse(team.getName(), team.getTeamNumber(), student.getRegisterNumber(),
                leaderRegisterNumber, team.getProblem(), team.getStudents());
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

    @GetMapping("/admin/access-password")
    public Map<String, Boolean> accessPasswordStatus(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        return Map.of("configured", settings.findById("student-access-password").isPresent());
    }

    @PutMapping("/admin/access-password")
    public Map<String, Boolean> updateAccessPassword(@RequestHeader("X-Admin-Password") String password,
                                                      @RequestBody AccessPasswordRequest request) {
        requireAdmin(password);
        if (request.password() == null || request.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Access password cannot be blank");
        }
        String hash = passwordEncoder.encode(request.password());
        settings.save(new AppSetting("student-access-password", hash));
        students.findAll().forEach(student -> {
            student.setAccessPassword(hash);
            students.save(student);
        });
        return Map.of("configured", true);
    }

    @PostMapping("/admin/problems")
    public Problem createProblem(@RequestHeader("X-Admin-Password") String password, @RequestBody ProblemRequest request) {
        requireAdmin(password);
        Problem problem = new Problem();
        problem.setTitle(request.title());
        problem.setStatement(request.statement());
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
        team.setName(request.name());
        team.setTeamNumber(nextTeamNumber());
        return teams.save(team);
    }

    @PostMapping("/admin/teams/import")
    public List<Team> importTeams(@RequestHeader("X-Admin-Password") String password,
                                  @RequestPart("file") MultipartFile file) {
        requireAdmin(password);
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file is empty");
        }
        String globalPasswordHash = settings.findById("student-access-password")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Set the global student access password first"))
                .getValue();
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
            Integer representativeColumn = findColumnAny(columns,
                    "team representative register number",
                    "register number roll number of the group leader",
                    "register number of the group leader");
            if (representativeColumn == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV must contain the group leader register number column");
            }
            List<Integer> memberColumns = columns.entrySet().stream()
                    .filter(entry -> entry.getKey().contains("member") && entry.getKey().contains("register"))
                    .map(Map.Entry::getValue)
                    .sorted()
                    .toList();
            if (memberColumns.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV must contain at least one member register number column");
            }
            List<Team> imported = new ArrayList<>();
            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line);
                String leaderRegister = valueAt(values, representativeColumn);
                if (isIgnoredRegister(leaderRegister)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Missing representative register number on CSV row " + rowNumber);
                }
                List<String> registers = new ArrayList<>();
                registers.add(leaderRegister);
                for (Integer column : memberColumns) {
                    String register = valueAt(values, column);
                    if (!isIgnoredRegister(register) && !registers.contains(register)) registers.add(register);
                }
                Team team = new Team();
                team.setTeamNumber(nextTeamNumber());
                team.setName(String.format("Team %03d", team.getTeamNumber()));
                for (int i = 0; i < registers.size(); i++) {
                    Student student = new Student();
                    String register = registers.get(i);
                    Integer nameColumn = i == 0
                            ? findColumnContaining(columns, "group leader", "name")
                            : findColumnContaining(columns, "member " + (i + 1), "name");
                    String name = nameColumn == null ? "" : valueAt(values, nameColumn);
                    student.setName(name.isBlank() ? register : name);
                    student.setRegisterNumber(register);
                    student.setAccessPassword(globalPasswordHash);
                    student.setLeader(i == 0);
                    student.setTeam(team);
                    team.getStudents().add(student);
                }
                imported.add(teams.save(team));
            }
            return imported;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read CSV file", exception);
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

    @DeleteMapping("/admin/teams/{teamId}")
    public void deleteTeam(@RequestHeader("X-Admin-Password") String password, @PathVariable Long teamId) {
        requireAdmin(password);
        teams.deleteById(teamId);
    }

    @DeleteMapping("/admin/teams")
    public void clearTeams(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        students.deleteAllInBatch();
        teams.deleteAllInBatch();
    }

    @PostMapping("/admin/teams/{teamId}/students")
    public Team addStudent(@RequestHeader("X-Admin-Password") String password,
                           @PathVariable Long teamId, @RequestBody StudentRequest request) {
        requireAdmin(password);
        Team team = teams.findById(teamId).orElseThrow();
        String globalPasswordHash = settings.findById("student-access-password")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Set the global student access password first"))
                .getValue();
        Student student = new Student();
        applyStudent(student, request);
        student.setAccessPassword(globalPasswordHash);
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

    private boolean verifyStudentPassword(Student student, String password) {
        if (student.getAccessPassword() == null || password == null) {
            return false;
        }
        if (student.getAccessPassword().startsWith("$2a$") || student.getAccessPassword().startsWith("$2b$")) {
            return passwordEncoder.matches(password, student.getAccessPassword());
        }
        if (student.getAccessPassword().equals(password)) {
            student.setAccessPassword(passwordEncoder.encode(password));
            students.save(student);
            return true;
        }
        return false;
    }

    private void applyStudent(Student student, StudentRequest request) {
        student.setName(request.name());
        student.setRegisterNumber(request.registerNumber());
        student.setEmail(request.email());
        settings.findById("student-access-password")
                .ifPresent(setting -> student.setAccessPassword(setting.getValue()));
    }

    public record LoginRequest(String registerNumber, String password) {}
    public record StudentTeamResponse(String name, Integer teamNumber, String ownRegisterNumber,
                                      String leaderRegisterNumber, Problem problem, List<Student> students) {}
    public record ProblemRequest(String title, String statement) {}
    public record AccessPasswordRequest(String password) {}
    public record TeamRequest(String name) {}
    public record StudentRequest(String name, String registerNumber, String email, String accessPassword) {}

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
