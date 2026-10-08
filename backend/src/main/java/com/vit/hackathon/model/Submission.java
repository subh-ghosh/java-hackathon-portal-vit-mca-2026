package com.vit.hackathon.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "submissions")
public class Submission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "team_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_submissions_team"))
    private Team team;
    private String googleDriveLink;
    private String githubLink;
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team value) { team = value; }
    public String getGoogleDriveLink() { return googleDriveLink; }
    public void setGoogleDriveLink(String value) { googleDriveLink = value; }
    public String getGithubLink() { return githubLink; }
    public void setGithubLink(String value) { githubLink = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { updatedAt = Instant.now(); }
}
