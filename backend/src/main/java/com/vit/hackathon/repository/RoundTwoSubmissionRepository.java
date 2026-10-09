package com.vit.hackathon.repository;

import com.vit.hackathon.model.RoundTwoSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoundTwoSubmissionRepository extends JpaRepository<RoundTwoSubmission, Long> {
    Optional<RoundTwoSubmission> findByTeamId(Long teamId);
}
