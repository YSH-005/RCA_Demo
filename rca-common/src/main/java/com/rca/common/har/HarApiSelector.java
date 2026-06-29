package com.rca.common.har;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Selects up to {@link HarSelectionPolicy#MAX_SLOW_ENTRIES} APIs at or above session p50,
 * classifies tiers by p50/p75/p95 duration percentiles, and picks a primary for log correlation.
 */
public final class HarApiSelector {

    private HarApiSelector() {
    }

    public static HarSelectionResult select(List<ParsedHarEntry> apiCandidates, long floorMs) {
        if (apiCandidates == null || apiCandidates.isEmpty()) {
            throw new IllegalArgumentException("No API candidates in HAR");
        }

        List<Long> allDurations = apiCandidates.stream().map(ParsedHarEntry::getDurationMs).toList();
        HarDurationStats stats = HarDurationStats.fromDurations(allDurations, floorMs);
        long p50 = stats.getP50Ms();
        long p75 = stats.getP75Ms();
        long p95 = stats.getP95Ms();

        for (ParsedHarEntry entry : apiCandidates) {
            entry.setPriority(isPriority(entry));
            entry.setDownloadDominated(isDownloadDominated(entry));
            entry.setTier(classifyPercentileTier(entry.getDurationMs(), p50, p75, p95));
        }

        List<ParsedHarEntry> aboveP50 = apiCandidates.stream()
                .filter(e -> e.getDurationMs() >= p50 && e.getTier() != HarApiTier.NORMAL)
                .sorted(Comparator.comparingLong(ParsedHarEntry::getDurationMs).reversed())
                .toList();

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ParsedHarEntry> selected = new ArrayList<>();
        appendUnique(selected, seen, aboveP50.stream()
                .filter(e -> e.getTier() == HarApiTier.HIGHLY_CRITICAL)
                .toList());
        appendUnique(selected, seen, aboveP50.stream()
                .filter(e -> e.getTier() == HarApiTier.CRITICAL)
                .toList());
        appendUnique(selected, seen, aboveP50.stream()
                .filter(e -> e.getTier() == HarApiTier.HIGH)
                .toList());

        // Priority Sprinklr endpoints are always included when present, beyond the slow-entry cap.
        appendUniqueUnbounded(selected, seen, apiCandidates.stream()
                .filter(HarApiSelector::isPriority)
                .sorted(Comparator.comparingLong(ParsedHarEntry::getDurationMs).reversed())
                .toList());

        if (selected.isEmpty()) {
            ParsedHarEntry fallback = apiCandidates.stream()
                    .max(Comparator.comparingLong(ParsedHarEntry::getDurationMs))
                    .orElseThrow();
            fallback.setTier(HarApiTier.HIGHLY_CRITICAL);
            fallback.setPriority(isPriority(fallback));
            fallback.setDownloadDominated(isDownloadDominated(fallback));
            selected = List.of(fallback);
            aboveP50 = List.of(fallback);
        }

        ParsedHarEntry primary = selected.stream()
                .sorted(Comparator
                        .comparingInt((ParsedHarEntry e) -> tierRank(e.getTier()))
                        .thenComparing(ParsedHarEntry::getDurationMs, Comparator.reverseOrder()))
                .findFirst()
                .orElse(selected.get(0));

        int highlyCritical = countTier(selected, HarApiTier.HIGHLY_CRITICAL);
        int critical = countTier(selected, HarApiTier.CRITICAL);
        int high = countTier(selected, HarApiTier.HIGH);
        String summary = "%d APIs selected (p50=%dms p75=%dms p95=%dms; %d highly-critical, %d critical, %d high; %d/%d at/above p50)"
                .formatted(selected.size(), p50, p75, p95,
                        highlyCritical, critical, high, aboveP50.size(), apiCandidates.size());

        return HarSelectionResult.builder()
                .primary(primary)
                .slowEntries(selected)
                .totalApiCandidates(apiCandidates.size())
                .aboveThresholdCount(aboveP50.size())
                .sessionMedianMs(stats.getMedianMs())
                .iqrThresholdMs(stats.getOutlierThresholdMs())
                .selectionSummary(summary)
                .build();
    }

    public static HarApiTier classifyPercentileTier(long durationMs, long p50, long p75, long p95) {
        if (durationMs >= p95) {
            return HarApiTier.HIGHLY_CRITICAL;
        }
        if (durationMs >= p75) {
            return HarApiTier.CRITICAL;
        }
        if (durationMs >= p50) {
            return HarApiTier.HIGH;
        }
        return HarApiTier.NORMAL;
    }

    static boolean isDownloadDominated(ParsedHarEntry entry) {
        long duration = entry.getDurationMs();
        if (duration <= 0) {
            return false;
        }
        long payload = entry.getResponseBodySize();
        if (payload < HarSelectionPolicy.LARGE_PAYLOAD_BYTES) {
            return false;
        }
        double receiveShare = (double) entry.getReceiveMs() / duration;
        double waitShare = (double) entry.getWaitMs() / duration;
        return receiveShare >= HarSelectionPolicy.SIDE_LARGE_RECEIVE_SHARE
                && waitShare < HarSelectionPolicy.SIDE_LARGE_MAX_WAIT_SHARE;
    }

    static boolean isPriority(ParsedHarEntry entry) {
        return HarParser.isPriorityApi(entry.getApiKind(), entry.getApiName(), entry.getUrl());
    }

    private static void appendUnique(List<ParsedHarEntry> selected, Set<String> seen, List<ParsedHarEntry> bucket) {
        appendUnique(selected, seen, bucket, HarSelectionPolicy.MAX_SLOW_ENTRIES);
    }

    private static void appendUniqueUnbounded(
            List<ParsedHarEntry> selected, Set<String> seen, List<ParsedHarEntry> bucket) {
        appendUnique(selected, seen, bucket, Integer.MAX_VALUE);
    }

    private static void appendUnique(
            List<ParsedHarEntry> selected, Set<String> seen, List<ParsedHarEntry> bucket, int maxEntries) {
        for (ParsedHarEntry entry : bucket) {
            if (selected.size() >= maxEntries) {
                return;
            }
            if (seen.add(dedupeKey(entry))) {
                selected.add(entry);
            }
        }
    }

    private static int tierRank(HarApiTier tier) {
        if (tier == null) {
            return 9;
        }
        return switch (tier) {
            case HIGHLY_CRITICAL -> 0;
            case CRITICAL -> 1;
            case HIGH -> 2;
            case NORMAL -> 3;
        };
    }

    private static int countTier(List<ParsedHarEntry> entries, HarApiTier tier) {
        return (int) entries.stream().filter(e -> e.getTier() == tier).count();
    }

    private static String dedupeKey(ParsedHarEntry entry) {
        return entry.getMethod() + " " + entry.getUrl();
    }
}
