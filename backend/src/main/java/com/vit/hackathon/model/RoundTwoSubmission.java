package com.vit.hackathon.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "round_two_submissions")
public class RoundTwoSubmission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "team_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_round_two_submissions_team"))
    private Team team;

    private String googleDriveLink;
    private String githubLink;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }
    public String getGoogleDriveLink() { return googleDriveLink; }
    public void setGoogleDriveLink(String googleDriveLink) { this.googleDriveLink = googleDriveLink; }
    public String getGithubLink() { return githubLink; }
    public void setGithubLink(String githubLink) { this.githubLink = githubLink; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { updatedAt = Instant.now(); }
}
