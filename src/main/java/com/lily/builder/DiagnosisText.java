package com.lily.builder;

import java.util.regex.Pattern;

/** Sanitizes quoted evidence and response text. Raw provider errors are never returned. */
final class DiagnosisText {
    private static final Pattern ASSIGNED = Pattern.compile(
            "(?i)((?:[a-z0-9_-]*(?:password|passwd|secret|token|api[_-]?key|authorization|cookie))[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern AUTH = Pattern.compile("(?i)(?:bearer|basic)\\s+[^\\s\"\',;}]+");
    private static final Pattern CREDENTIAL_URL = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+:[^\\s/@]+@");
    private static final Pattern KNOWN_TOKEN = Pattern.compile("\\b(?:AKIA[0-9A-Z]{16}|gh[pousr]_[a-zA-Z0-9]{10,}|github_pat_[a-zA-Z0-9_]+|sk-[a-zA-Z0-9_-]{10,}|eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+)\\b");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");

    private DiagnosisText() {}

    static String mask(String text) {
        if (text == null) return "";
        String result = PRIVATE_KEY.matcher(text).replaceAll("***");
        result = AUTH.matcher(result).replaceAll("***");
        result = ASSIGNED.matcher(result).replaceAll("$1***");
        result = CREDENTIAL_URL.matcher(result).replaceAll("$1***:***@");
        return KNOWN_TOKEN.matcher(result).replaceAll("***");
    }
}
