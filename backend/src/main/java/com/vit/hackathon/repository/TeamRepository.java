package com.vit.hackathon.repository;

import com.vit.hackathon.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findAllByPrimaryEmailIgnoreCase(String primaryEmail);
}
