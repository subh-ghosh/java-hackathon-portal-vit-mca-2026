package com.vit.hackathon.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.util.Locale;

@Entity
@Table(name = "students",
        uniqueConstraints = @UniqueConstraint(name = "uk_students_login_identity",
                columnNames = {"login_username", "register_number"}),
        indexes = @Index(name = "idx_students_team_id", columnList = "team_id"))
public class Student {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 80)
    private String registerNumber;
    @Column(name = "login_username", length = 320)
    @JsonIgnore
    private String loginUsername;
    @Column(nullable = false)
    private String name;
    @Column(length = 320)
    private String email;
    @Column(nullable = false)
    private boolean leader = false;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", foreignKey = @ForeignKey(name = "fk_students_team"))
    @JsonIgnore
    private Team team;

    public Long getId() { return id; }
    public String getRegisterNumber() { return registerNumber; }
    public void setRegisterNumber(String value) {
        registerNumber = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
    public String getLoginUsername() { return loginUsername; }
    public void setLoginUsername(String value) {
        loginUsername = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value; }
    public boolean isLeader() { return leader; }
    public void setLeader(boolean value) { leader = value; }
    public Team getTeam() { return team; }
    public void setTeam(Team value) {
        team = value;
        setLoginUsername(value == null ? null : value.getRegistrationUsername());
    }
}
