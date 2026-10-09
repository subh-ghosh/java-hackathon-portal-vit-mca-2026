package com.vit.hackathon.repository;

import com.vit.hackathon.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StudentRepository extends JpaRepository<Student, Long> {
    List<Student> findAllByRegisterNumberIgnoreCase(String registerNumber);
}
