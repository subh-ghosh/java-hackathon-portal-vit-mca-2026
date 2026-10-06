package com.vit.hackathon.model;

import jakarta.persistence.*;

@Entity
@Table(name = "app_settings")
public class AppSetting {
    @Id
    private String settingKey;
    @Column(nullable = false)
    private String value;

    public AppSetting() {}

    public AppSetting(String settingKey, String value) {
        this.settingKey = settingKey;
        this.value = value;
    }

    public String getSettingKey() { return settingKey; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
