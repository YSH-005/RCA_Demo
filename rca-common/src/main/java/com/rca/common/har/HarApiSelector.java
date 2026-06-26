package com.rca.common.har;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selects up to {@link HarSelectionPolicy#MAX_SLOW_ENTRIES} APIs for RCA using a duration floor,
 * Tukey IQR outliers, and tiered priority (critical / high / side-large).
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

        long maxDuration = apiCandidates.stream().mapToLong(ParsedHarEntry::getDurationMs).max().orElse(0);
        List<ParsedHarEntry> atOrAboveFloor = apiCandidates.stream()
                .filter(e -> e.getDurationMs() >= floorMs)
                .sorted(Comparator.comparingLong(ParsedHarEntry::getDurationMs).reversed())
                .toList();

        if (atOrAboveFloor.isEmpty()) {
            ParsedHarEntry fallback = apiCandidates.stream()
                    .max(Comparator.comparingLong(ParsedHarEntry::getDurationMs))
                    .orElseThrow();
            fallback.setTier(HarApiTier.CRITICAL);
            fallback.setPriority(isPriority(fallback));
            return HarSelectionResult.builder()
                    .primary(fallback)
                    .slowEntries(List.of(fallback))
                    .totalApiCandidates(apiCandidates.size())
                    .aboveThresholdCount(0)
                    .sessionMedianMs(stats.getMedianMs())
                    .iqrThresholdMs(stats.getOutlierThresholdMs())
                    .selectionSummary("1 entry (fallback below %dms floor; slowest API in session)".formatted(floorMs))
                    .build();
        }

        Map<HarApiTier, List<ParsedHarEntry>> buckets = new EnumMap<>(HarApiTier.class);
        for (HarApiTier tier : HarApiTier.values()) {
            buckets.put(tier, new ArrayList<>());
        }

        for (ParsedHarEntry entry : atOrAboveFloor) {
            boolean priority = isPriority(entry);
            entry.setPriority(priority);
            HarApiTier tier = classifyTier(entry, stats, floorMs, maxDuration, priority);
            entry.setTier(tier);
            buckets.get(tier).add(entry);
        }

        for (List<ParsedHarEntry> bucket : buckets.values()) {
            bucket.sort(Comparator.comparingLong(ParsedHarEntry::getDurationMs).reversed());
        }

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ParsedHarEntry> selected = new ArrayList<>();
        appendTier(selected, seen, buckets.get(HarApiTier.CRITICAL));
        appendTier(selected, seen, buckets.get(HarApiTier.HIGH));
        appendTier(selected, seen, buckets.get(HarApiTier.SIDE_LARGE));

        if (selected.size() < HarSelectionPolicy.MAX_SLOW_ENTRIES) {
            for (ParsedHarEntry entry : atOrAboveFloor) {
                if (selected.size() >= HarSelectionPolicy.MAX_SLOW_ENTRIES) {
                    break;
                }
                if (seen.add(dedupeKey(entry))) {
                    if (entry.getTier() == null) {
                        entry.setTier(HarApiTier.HIGH);
                    }
                    selected.add(entry);
                }
            }
        }

        selected.sort(Comparator
                .comparingInt((ParsedHarEntry e) -> tierRank(e.getTier()))
                .thenComparing(ParsedHarEntry::getDurationMs, Comparator.reverseOrder()));

        ParsedHarEntry primary = selected.stream()
                .filter(e -> e.getTier() == HarApiTier.CRITICAL)
                .max(Comparator.comparingLong(ParsedHarEntry::getDurationMs))
                .orElse(selected.get(0));

        int critical = countTier(selected, HarApiTier.CRITICAL);
        int high = countTier(selected, HarApiTier.HIGH);
        int sideLarge = countTier(selected, HarApiTier.SIDE_LARGE);
        String summary = "%d APIs selected (floor=%dms, IQR≥%dms, median=%dms, %d critical, %d high, %d side-large, %d/%d candidates)"
                .formatted(selected.size(), floorMs, stats.getOutlierThresholdMs(), stats.getMedianMs(),
                        critical, high, sideLarge, atOrAboveFloor.size(), apiCandidates.size());

        return HarSelectionResult.builder()
                .primary(primary)
                .slowEntries(selected)
                .totalApiCandidates(apiCandidates.size())
                .aboveThresholdCount(atOrAboveFloor.size())
                .sessionMedianMs(stats.getMedianMs())
                .iqrThresholdMs(stats.getOutlierThresholdMs())
                .selectionSummary(summary)
                .build();
    }

    private static HarApiTier classifyTier(
            ParsedHarEntry entry,
            HarDurationStats stats,
            long floorMs,
            long maxDuration,
            boolean priority) {

        long duration = entry.getDurationMs();
        if (duration < floorMs) {
            return HarApiTier.HIGH;
        }

        boolean outlier = duration >= stats.getOutlierThresholdMs();
        boolean topSession = duration == maxDuration;
        boolean sideLarge = isSideLarge(entry);
        double waitShare = duration > 0 ? (double) entry.getWaitMs() / duration : 0;

        if (priority && (outlier || topSession || duration >= stats.getQ3Ms()) && waitShare >= 0.40) {
            return HarApiTier.CRITICAL;
        }
        if (sideLarge) {
            return HarApiTier.SIDE_LARGE;
        }
        if (priority && (outlier || topSession || duration >= stats.getQ3Ms())) {
            return HarApiTier.CRITICAL;
        }
        if (outlier || topSession) {
            return HarApiTier.HIGH;
        }
        return HarApiTier.HIGH;
    }

    static boolean isSideLarge(ParsedHarEntry entry) {
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

    private static void appendTier(List<ParsedHarEntry> selected, Set<String> seen, List<ParsedHarEntry> bucket) {
        for (ParsedHarEntry entry : bucket) {
            if (selected.size() >= HarSelectionPolicy.MAX_SLOW_ENTRIES) {
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
            case CRITICAL -> 0;
            case HIGH -> 1;
            case SIDE_LARGE -> 2;
        };
    }

    private static int countTier(List<ParsedHarEntry> entries, HarApiTier tier) {
        return (int) entries.stream().filter(e -> e.getTier() == tier).count();
    }

    private static String dedupeKey(ParsedHarEntry entry) {
        return entry.getMethod() + " " + entry.getUrl();
    }
}
