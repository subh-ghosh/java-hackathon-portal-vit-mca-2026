package com.vit.hackathon.api;

import com.vit.hackathon.model.*;
import com.vit.hackathon.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class HackathonController {
    private final TeamRepository teams;
    private final StudentRepository students;
    private final ProblemRepository problems;
    private final String adminPassword;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public HackathonController(TeamRepository teams, StudentRepository students, ProblemRepository problems,
                               @Value("${app.admin.password}") String adminPassword) {
        this.teams = teams;
        this.students = students;
        this.problems = problems;
        this.adminPassword = adminPassword;
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
        return new StudentTeamResponse(team.getName(), team.getProblem(), team.getStudents());
    }

    @GetMapping("/admin/teams")
    public List<Team> listTeams(@RequestHeader("X-Admin-Password") String password) {
        requireAdmin(password);
        return teams.findAll();
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

    @PostMapping("/admin/teams")
    public Team createTeam(@RequestHeader("X-Admin-Password") String password, @RequestBody TeamRequest request) {
        requireAdmin(password);
        Team team = new Team();
        team.setName(request.name());
        return teams.save(team);
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
    public Team assignLeader(@RequestHeader("X-Admin-Password") String password,
                             @PathVariable Long studentId) {
        requireAdmin(password);
        Student leader = students.findById(studentId).orElseThrow();
        Team team = leader.getTeam();
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
        if (request.accessPassword() != null && !request.accessPassword().isBlank()) {
            student.setAccessPassword(passwordEncoder.encode(request.accessPassword()));
        }
    }

    public record LoginRequest(String registerNumber, String password) {}
    public record StudentTeamResponse(String name, Problem problem, List<Student> students) {}
    public record ProblemRequest(String title, String statement) {}
    public record TeamRequest(String name) {}
    public record StudentRequest(String name, String registerNumber, String email, String accessPassword) {}
}
