package com.rca.analyzer.service;

import com.rca.common.dto.KafkaHarMessage;

import com.rca.common.model.SlowHarEntry;

/**
 * HAR fields needed to synthesize Graylog/Grafana shapes aligned with the slow request.
 */
public record HarStitchContext(
        long waitMs,
        long durationMs,
        long receiveMs,
        long sendMs,
        String url,
        String method,
        String apiKind,
        String apiName
) {
    public static HarStitchContext from(KafkaHarMessage message) {
        if (message == null) {
            return empty();
        }
        return new HarStitchContext(
                message.getWaitMs(),
                message.getDurationMs(),
                message.getReceiveMs(),
                message.getSendMs(),
                nullToEmpty(message.getSlowestUrl()),
                nullToEmpty(message.getSlowestMethod()),
                nullToEmpty(message.getApiKind()),
                nullToEmpty(message.getApiName()));
    }

    public static HarStitchContext from(SlowHarEntry entry) {
        if (entry == null) {
            return empty();
        }
        return new HarStitchContext(
                entry.getWaitMs(),
                entry.getDurationMs(),
                entry.getReceiveMs(),
                entry.getSendMs(),
                nullToEmpty(entry.getUrl()),
                nullToEmpty(entry.getMethod()),
                nullToEmpty(entry.getApiKind()),
                nullToEmpty(entry.getApiName()));
    }

    public static HarStitchContext empty() {
        return new HarStitchContext(0, 0, 0, 0, "", "GET", "", "");
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
