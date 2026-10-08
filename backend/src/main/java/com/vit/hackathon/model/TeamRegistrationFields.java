package com.vit.hackathon.model;

import java.util.List;
import java.util.Locale;

public final class TeamRegistrationFields {
    public static final List<String> LABELS = List.of(
            "Timestamp",
            "Username",
            "Name of the Group Leader (As per SSLC Record- USE UPPERCASE FORMAT only)",
            "Register Number/Roll Number of the Group Leader",
            "Team Member 2 Name (As per SSLC Record- USE UPPERCASE FORMAT only)",
            "Team Member 2 Registration Number/Roll Number",
            "Team Member 3 Name (As per SSLC Record- USE UPPERCASE FORMAT only)",
            "Team Member 3 Registration Number/Roll Number",
            "Team Member 4 Name (As per SSLC Record- USE UPPERCASE FORMAT only)",
            "Team Member 4 Registration Number/Roll Number",
            "Primary Contact Number (preferably Whatsapp Number)",
            "Primary Email Id",
            "Programme",
            "Specialization (say for example CSE/ECE/EEE/ CSE SPEC. IN AI ML)",
            "Institution",
            "Payment Reference Number (Check your Payment Receipt- Refer Reference No column)",
            "Name of the Institute",
            "City",
            "State"
    );

    private TeamRegistrationFields() {}

    public static int indexOf(String fieldName) {
        String name = normalize(fieldName);
        if (name.equals("timestamp")) return 0;
        if (name.equals("username")) return 1;
        if (name.contains("group leader") && name.contains("name")) return 2;
        if (name.contains("group leader") && (name.contains("regist") || name.contains("roll number"))) return 3;
        if (name.startsWith("team member") || name.startsWith("member ")) {
            int start = name.startsWith("team member") ? "team member".length() : "member".length();
            String remainder = name.substring(start).trim();
            int numberEnd = 0;
            while (numberEnd < remainder.length() && Character.isDigit(remainder.charAt(numberEnd))) numberEnd++;
            if (numberEnd > 0) {
                int memberNumber = Integer.parseInt(remainder.substring(0, numberEnd));
                if (memberNumber >= 2 && memberNumber <= 4) {
                    if (name.contains("name")) return 4 + (memberNumber - 2) * 2;
                    if (name.contains("regist") || name.contains("enrollment") || name.contains("roll number")) {
                        return 5 + (memberNumber - 2) * 2;
                    }
                }
            }
        }
        if (name.contains("primary contact number")) return 10;
        if (name.contains("primary email")) return 11;
        if (name.equals("programme") || name.equals("program")) return 12;
        if (name.startsWith("specialization") || name.startsWith("specialisation")) return 13;
        if (name.equals("institution")) return 14;
        if (name.contains("payment reference")) return 15;
        if (name.contains("name of the institute") || name.equals("name institute")) return 16;
        if (name.equals("city")) return 17;
        if (name.equals("state")) return 18;
        return -1;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }
}
