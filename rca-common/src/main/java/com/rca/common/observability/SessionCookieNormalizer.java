package com.rca.common.observability;

import java.util.ArrayList;
import java.util.List;

/** Trims pasted browser Cookie headers down to the SSO tokens each service needs. */
public final class SessionCookieNormalizer {

    private SessionCookieNormalizer() {
    }

    public static String stripWrappingQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    /** Keep Graylog SSO cookies when user pastes the full browser Cookie header. */
    public static String graylog(String value) {
        String trimmed = stripWrappingQuotes(value);
        if (trimmed.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : trimmed.split(";")) {
            String token = part.trim();
            if (token.startsWith("authentication=")
                    || token.startsWith("session=")
                    || token.startsWith("user.env.type=")
                    || token.startsWith("_gcl_au=")) {
                parts.add(token);
            }
        }
        return parts.isEmpty() ? trimmed : String.join("; ", parts);
    }

    /** Keep Grafana session cookies when user pastes the full browser Cookie header. */
    public static String grafana(String value) {
        String trimmed = stripWrappingQuotes(value);
        if (trimmed.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : trimmed.split(";")) {
            String token = part.trim();
            if (token.startsWith("grafana_session_expiry=")
                    || token.startsWith("grafana_session=")
                    || token.startsWith("user.env.type=")) {
                parts.add(token);
            }
        }
        return parts.isEmpty() ? trimmed : String.join("; ", parts);
    }
}
