package com.vit.hackathon.repository;

import com.vit.hackathon.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {
}
