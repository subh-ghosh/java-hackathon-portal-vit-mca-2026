package com.vit.hackathon.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "teams")
public class Team {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String name;
    @Column(unique = true)
    private Integer teamNumber;
    @ManyToOne(fetch = FetchType.EAGER)
    private Problem problem;
    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Student> students = new ArrayList<>();
    @Convert(converter = ImportedTeamFieldsConverter.class)
    @Column(name = "imported_fields", columnDefinition = "TEXT")
    private List<ImportedTeamField> importedFields = new ArrayList<>();
    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getTeamNumber() { return teamNumber; }
    public void setTeamNumber(Integer teamNumber) { this.teamNumber = teamNumber; }
    public Problem getProblem() { return problem; }
    public void setProblem(Problem problem) { this.problem = problem; }
    public List<Student> getStudents() { return students; }
    public List<ImportedTeamField> getImportedFields() { return importedFields; }
    public void setImportedFields(List<ImportedTeamField> importedFields) {
        this.importedFields = importedFields == null ? new ArrayList<>() : importedFields;
    }
}
