package com.vit.hackathon.repository;

import com.vit.hackathon.model.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    Optional<Submission> findByTeamId(Long teamId);
}
