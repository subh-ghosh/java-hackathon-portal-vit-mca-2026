package com.vit.hackathon.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "students", indexes = @Index(name = "idx_students_team_id", columnList = "team_id"))
public class Student {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String registerNumber;
    @Column(nullable = false)
    private String name;
    private String email;
    @Column(nullable = false)
    private boolean leader = false;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", foreignKey = @ForeignKey(name = "fk_students_team"))
    @JsonIgnore
    private Team team;

    public Long getId() { return id; }
    public String getRegisterNumber() { return registerNumber; }
    public void setRegisterNumber(String value) { registerNumber = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value; }
    public boolean isLeader() { return leader; }
    public void setLeader(boolean value) { leader = value; }
    public Team getTeam() { return team; }
    public void setTeam(Team value) { team = value; }
}
