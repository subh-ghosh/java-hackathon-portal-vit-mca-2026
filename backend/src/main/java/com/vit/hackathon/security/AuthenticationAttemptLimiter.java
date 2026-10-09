package com.vit.hackathon.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AuthenticationAttemptLimiter {
    private static final int MAX_TRACKED_IDENTITIES = 10_000;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int MAX_PARTICIPANT_FAILURES = 20;
    private static final int MAX_ADMIN_FAILURES = 30;
    private final Map<String, AttemptWindow> attempts = new LinkedHashMap<>(128, 0.75f, true);

    public synchronized void checkParticipant(String username, String registerNumber) {
        check(key("participant", username + "\n" + registerNumber), MAX_PARTICIPANT_FAILURES);
    }

    public synchronized void participantFailed(String username, String registerNumber) {
        failed(key("participant", username + "\n" + registerNumber), MAX_PARTICIPANT_FAILURES);
    }

    public synchronized void participantSucceeded(String username, String registerNumber) {
        attempts.remove(key("participant", username + "\n" + registerNumber));
    }

    public synchronized void checkAdmin(String clientIdentity) {
        check(key("admin", clientIdentity), MAX_ADMIN_FAILURES);
    }

    public synchronized void adminFailed(String clientIdentity) {
        failed(key("admin", clientIdentity), MAX_ADMIN_FAILURES);
    }

    public synchronized void adminSucceeded(String clientIdentity) {
        attempts.remove(key("admin", clientIdentity));
    }

    public synchronized void checkAttendance(String clientIdentity) {
        check(key("attendance", clientIdentity), MAX_ADMIN_FAILURES);
    }

    public synchronized void attendanceFailed(String clientIdentity) {
        failed(key("attendance", clientIdentity), MAX_ADMIN_FAILURES);
    }

    public synchronized void attendanceSucceeded(String clientIdentity) {
        attempts.remove(key("attendance", clientIdentity));
    }

    private void check(String key, int limit) {
        AttemptWindow window = currentWindow(key);
        if (window != null && window.failures() >= limit) {
            long remainingSeconds = Duration.between(Instant.now(), window.startedAt().plus(WINDOW)).toSeconds();
            long retryAfter = Math.max(1, (remainingSeconds + 59) / 60);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed sign-in attempts. Try again in about " + retryAfter + " minute(s).");
        }
    }

    private void failed(String key, int limit) {
        Instant now = Instant.now();
        AttemptWindow window = currentWindow(key);
        attempts.put(key, window == null
                ? new AttemptWindow(now, 1)
                : new AttemptWindow(window.startedAt(), window.failures() + 1));
        if (attempts.size() > MAX_TRACKED_IDENTITIES) {
            Iterator<String> iterator = attempts.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        check(key, limit);
    }

    private AttemptWindow currentWindow(String key) {
        AttemptWindow window = attempts.get(key);
        if (window != null && Duration.between(window.startedAt(), Instant.now()).compareTo(WINDOW) >= 0) {
            attempts.remove(key);
            return null;
        }
        return window;
    }

    private String key(String scope, String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((scope + "\n" + identity).getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte value : digest) encoded.append(String.format("%02x", value));
            Arrays.fill(digest, (byte) 0);
            return encoded.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record AttemptWindow(Instant startedAt, int failures) {}
}
