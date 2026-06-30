package com.skyblock.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Name Validation & Profanity Filter
 *
 * Translated from name_validator.js.
 *
 * Rules:
 *   - Non-null, non-empty string
 *   - 1–20 characters
 *   - Only alphanumeric and underscores  (^[a-zA-Z0-9_]+$)
 *   - No blocked words (case-insensitive substring match)
 */
public class NameValidator {

    public NameValidator() {}

    private static final Pattern ALLOWED = Pattern.compile("^[a-zA-Z0-9_]+$");

    // Mirrors BLOCKED_WORDS in name_validator.js
    private static final Set<String> BLOCKED_WORDS = new HashSet<>(Arrays.asList(
            "fuck", "shit", "ass", "bitch", "cunt", "dick", "cock", "pussy",
            "nigger", "nigga", "faggot", "retard", "whore", "slut", "bastard",
            "damn", "hell"
    ));

    // ─── Result ───────────────────────────────────────────────────────────────

    public static class Result {
        public final boolean valid;
        public final String  reason; // null when valid

        private Result(boolean valid, String reason) {
            this.valid  = valid;
            this.reason = reason;
        }

        public static Result ok()               { return new Result(true,  null); }
        public static Result fail(String msg)   { return new Result(false, msg); }
    }

    // ─── Validate ─────────────────────────────────────────────────────────────

    /**
     * Validates an island or pwarp name.
     *
     * Mirrors validateName() in name_validator.js.
     */
    public static Result validate(String name) {
        if (name == null || name.isEmpty()) {
            return Result.fail("§cName cannot be empty.");
        }

        if (name.length() > 20) {
            return Result.fail("§cName must be 20 characters or less.");
        }

        if (!ALLOWED.matcher(name).matches()) {
            return Result.fail("§cName can only contain letters, numbers, and underscores.");
        }

        String lower = name.toLowerCase(Locale.ROOT);
        for (String word : BLOCKED_WORDS) {
            if (lower.contains(word)) {
                return Result.fail("§cThat name contains a prohibited word.");
            }
        }

        return Result.ok();
    }

    // instance form for callers who hold a NameValidator reference
    public Result validateName(String name) { return validate(name); }
}
