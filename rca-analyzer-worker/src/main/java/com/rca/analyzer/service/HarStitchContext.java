package com.rca.analyzer.service;

import com.rca.common.dto.KafkaHarMessage;

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

    public static HarStitchContext empty() {
        return new HarStitchContext(0, 0, 0, 0, "", "GET", "", "");
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
