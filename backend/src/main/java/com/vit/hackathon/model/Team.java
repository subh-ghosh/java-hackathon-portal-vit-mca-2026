package com.vit.hackathon.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "teams", indexes = @Index(name = "idx_teams_problem_id", columnList = "problem_id"))
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(name = "team_number", unique = true)
    private Integer teamNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "problem_id", foreignKey = @ForeignKey(name = "fk_teams_problem"))
    private Problem problem;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Student> students = new ArrayList<>();

    @Column(name = "registration_timestamp", length = 100)
    private String registrationTimestamp;

    @Column(name = "registration_username", length = 320)
    private String registrationUsername;

    @Column(name = "group_leader_name", length = 255)
    private String groupLeaderName;

    @Column(name = "group_leader_register_number", length = 80)
    private String groupLeaderRegisterNumber;

    @Column(name = "member_2_name", length = 255)
    private String member2Name;

    @Column(name = "member_2_register_number", length = 80)
    private String member2RegisterNumber;

    @Column(name = "member_3_name", length = 255)
    private String member3Name;

    @Column(name = "member_3_register_number", length = 80)
    private String member3RegisterNumber;

    @Column(name = "member_4_name", length = 255)
    private String member4Name;

    @Column(name = "member_4_register_number", length = 80)
    private String member4RegisterNumber;

    @Column(name = "primary_contact_number", length = 50)
    private String primaryContactNumber;

    @Column(name = "primary_email", length = 320)
    private String primaryEmail;

    @Column(length = 100)
    private String programme;

    @Column(length = 255)
    private String specialization;

    @Column(length = 255)
    private String institution;

    @Column(name = "payment_reference_number", length = 255)
    private String paymentReferenceNumber;

    @Column(name = "institute_name", length = 255)
    private String instituteName;

    @Column(length = 120)
    private String city;

    @Column(length = 120)
    private String state;

    @JsonIgnore
    @Convert(converter = ImportedTeamFieldsConverter.class)
    @Column(name = "imported_extras", columnDefinition = "TEXT")
    private List<ImportedTeamField> importedExtras = new ArrayList<>();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getTeamNumber() { return teamNumber; }
    public void setTeamNumber(Integer teamNumber) { this.teamNumber = teamNumber; }
    public Problem getProblem() { return problem; }
    public void setProblem(Problem problem) { this.problem = problem; }
    public List<Student> getStudents() { return students; }
    public String getRegistrationTimestamp() { return registrationTimestamp; }
    public String getRegistrationUsername() { return registrationUsername; }
    public String getGroupLeaderName() { return groupLeaderName; }
    public String getGroupLeaderRegisterNumber() { return groupLeaderRegisterNumber; }
    public String getMember2Name() { return member2Name; }
    public String getMember2RegisterNumber() { return member2RegisterNumber; }
    public String getMember3Name() { return member3Name; }
    public String getMember3RegisterNumber() { return member3RegisterNumber; }
    public String getMember4Name() { return member4Name; }
    public String getMember4RegisterNumber() { return member4RegisterNumber; }
    public String getPrimaryContactNumber() { return primaryContactNumber; }
    public String getPrimaryEmail() { return primaryEmail; }
    public String getProgramme() { return programme; }
    public String getSpecialization() { return specialization; }
    public String getInstitution() { return institution; }
    public String getPaymentReferenceNumber() { return paymentReferenceNumber; }
    public String getInstituteName() { return instituteName; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public List<ImportedTeamField> getImportedExtras() { return importedExtras; }

    public List<ImportedTeamField> getImportedFields() {
        List<String> values = List.of(
                value(registrationTimestamp), value(registrationUsername), value(groupLeaderName),
                value(groupLeaderRegisterNumber), value(member2Name), value(member2RegisterNumber),
                value(member3Name), value(member3RegisterNumber), value(member4Name),
                value(member4RegisterNumber), value(primaryContactNumber), value(primaryEmail),
                value(programme), value(specialization), value(institution),
                value(paymentReferenceNumber), value(instituteName), value(city), value(state)
        );
        List<ImportedTeamField> fields = new ArrayList<>(TeamRegistrationFields.LABELS.size()
                + importedExtras.size());
        for (int index = 0; index < TeamRegistrationFields.LABELS.size(); index++) {
            fields.add(new ImportedTeamField(index, TeamRegistrationFields.LABELS.get(index), values.get(index)));
        }
        for (int index = 0; index < importedExtras.size(); index++) {
            ImportedTeamField extra = importedExtras.get(index);
            fields.add(new ImportedTeamField(TeamRegistrationFields.LABELS.size() + index,
                    extra.getFieldName(), extra.getFieldValue()));
        }
        return fields;
    }

    public void setImportedFields(List<ImportedTeamField> fields) {
        List<ImportedTeamField> extras = new ArrayList<>();
        if (fields != null) {
            for (ImportedTeamField field : fields) {
                if (field == null) continue;
                int index = TeamRegistrationFields.indexOf(field.getFieldName());
                if (index < 0 && field.getColumnIndex() >= 0 && field.getColumnIndex() < TeamRegistrationFields.LABELS.size()
                        && TeamRegistrationFields.LABELS.get(field.getColumnIndex()).equals(field.getFieldName())) {
                    index = field.getColumnIndex();
                }
                if (index < 0) {
                    extras.add(new ImportedTeamField(TeamRegistrationFields.LABELS.size() + extras.size(),
                            field.getFieldName(), value(field.getFieldValue())));
                    continue;
                }
                String fieldValue = value(field.getFieldValue());
                switch (index) {
                    case 0 -> registrationTimestamp = fieldValue;
                    case 1 -> registrationUsername = fieldValue;
                    case 2 -> groupLeaderName = fieldValue;
                    case 3 -> groupLeaderRegisterNumber = fieldValue;
                    case 4 -> member2Name = fieldValue;
                    case 5 -> member2RegisterNumber = fieldValue;
                    case 6 -> member3Name = fieldValue;
                    case 7 -> member3RegisterNumber = fieldValue;
                    case 8 -> member4Name = fieldValue;
                    case 9 -> member4RegisterNumber = fieldValue;
                    case 10 -> primaryContactNumber = fieldValue;
                    case 11 -> primaryEmail = fieldValue;
                    case 12 -> programme = fieldValue;
                    case 13 -> specialization = fieldValue;
                    case 14 -> institution = fieldValue;
                    case 15 -> paymentReferenceNumber = fieldValue;
                    case 16 -> instituteName = fieldValue;
                    case 17 -> city = fieldValue;
                    case 18 -> state = fieldValue;
                    default -> throw new IllegalStateException("Unexpected registration field index: " + index);
                }
            }
        }
        importedExtras = extras;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
