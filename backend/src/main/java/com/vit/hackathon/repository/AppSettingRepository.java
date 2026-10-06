package com.vit.hackathon.repository;

import com.vit.hackathon.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {}
