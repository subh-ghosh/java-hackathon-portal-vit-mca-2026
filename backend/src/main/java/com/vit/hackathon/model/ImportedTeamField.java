package com.vit.hackathon.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ImportedTeamField {
    private int columnIndex;
    @Column(length = 1000)
    private String fieldName;
    @Column(length = 5000)
    private String fieldValue;

    public ImportedTeamField() {}

    public ImportedTeamField(int columnIndex, String fieldName, String fieldValue) {
        this.columnIndex = columnIndex;
        this.fieldName = fieldName;
        this.fieldValue = fieldValue;
    }

    public int getColumnIndex() { return columnIndex; }
    public void setColumnIndex(int columnIndex) { this.columnIndex = columnIndex; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getFieldValue() { return fieldValue; }
    public void setFieldValue(String fieldValue) { this.fieldValue = fieldValue; }
}
