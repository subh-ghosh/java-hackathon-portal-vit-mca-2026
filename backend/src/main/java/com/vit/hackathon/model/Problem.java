package com.vit.hackathon.model;

import jakarta.persistence.*;

@Entity
@Table(name = "problems")
public class Problem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false, length = 5000)
    private String statement;
    @Column(nullable = false)
    private boolean enabled = false;

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getStatement() { return statement; }
    public void setStatement(String value) { statement = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
}
