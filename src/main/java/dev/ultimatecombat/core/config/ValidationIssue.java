package dev.ultimatecombat.core.config;

/**
 * A single problem found while reading a YAML file. Issues never stop the plugin: every
 * issue carries the fallback that was applied instead of the invalid value.
 *
 * @param file     file name relative to the plugin folder, for example {@code combat.yml}
 * @param path     YAML path, for example {@code pvp.duration-seconds}
 * @param value    the raw value the administrator entered (may be {@code null})
 * @param problem  human readable explanation
 * @param expected description of accepted values
 * @param fallback the value used instead
 * @param severity how serious the problem is
 */
public record ValidationIssue(String file, String path, String value, String problem, String expected, String fallback,
                              Severity severity) {

    public enum Severity {
        /** The value was invalid and replaced by a fallback. */
        WARNING,
        /** The file could not be parsed at all; built-in defaults are used for the whole file. */
        ERROR,
        /** Informational notice (for example a deprecated key). */
        INFO
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(file).append("] ");
        if (path != null && !path.isEmpty()) {
            sb.append(path).append(": ");
        }
        sb.append(problem);
        if (value != null) {
            sb.append(" (found: '").append(value).append("')");
        }
        if (expected != null) {
            sb.append(" Expected: ").append(expected).append('.');
        }
        if (fallback != null) {
            sb.append(" Using: ").append(fallback).append('.');
        }
        return sb.toString();
    }
}
