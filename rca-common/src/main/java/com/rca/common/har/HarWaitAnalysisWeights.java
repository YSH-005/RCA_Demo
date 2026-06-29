package com.rca.common.har;

import com.rca.common.model.SlowHarEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wait-time-proportional weights for scoring across selected slow HAR APIs.
 */
public final class HarWaitAnalysisWeights {

    private HarWaitAnalysisWeights() {
    }

    public static Map<String, Double> byApiKey(List<SlowHarEntry> entries) {
        Map<String, Double> weights = new LinkedHashMap<>();
        if (entries == null || entries.isEmpty()) {
            return weights;
        }
        long totalWait = entries.stream().mapToLong(HarWaitAnalysisWeights::weightBasisMs).sum();
        if (totalWait <= 0) {
            totalWait = entries.size();
            for (SlowHarEntry entry : entries) {
                weights.put(apiKey(entry), 1.0 / entries.size());
                entry.setAnalysisWeight(weights.get(apiKey(entry)));
            }
            return weights;
        }
        for (SlowHarEntry entry : entries) {
            double w = (double) weightBasisMs(entry) / totalWait;
            weights.put(apiKey(entry), w);
            entry.setAnalysisWeight(w);
        }
        return weights;
    }

    public static String apiKey(SlowHarEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.getRequestId() != null && !entry.getRequestId().isBlank()) {
            return entry.getRequestId().trim();
        }
        return apiKey(entry.getMethod(), entry.getUrl(), entry.getApiName());
    }

    public static String apiKey(String method, String url, String apiName) {
        String m = method != null ? method.trim().toUpperCase(Locale.ROOT) : "GET";
        String u = url != null ? url.trim() : "";
        if (!u.isBlank()) {
            return m + " " + u;
        }
        return m + " " + (apiName != null ? apiName : "unknown");
    }

    private static long weightBasisMs(SlowHarEntry entry) {
        if (entry.getWaitMs() > 0) {
            return entry.getWaitMs();
        }
        return Math.max(0, entry.getDurationMs());
    }
}
