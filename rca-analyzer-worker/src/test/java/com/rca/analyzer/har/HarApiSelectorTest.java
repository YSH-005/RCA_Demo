package com.rca.analyzer.har;

import com.rca.common.har.HarApiSelector;
import com.rca.common.har.HarApiTier;
import com.rca.common.har.HarDurationStats;
import com.rca.common.har.HarSelectionResult;
import com.rca.common.har.ParsedHarEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarApiSelectorTest {

    @Test
    void classifiesDownloadDominatedWithoutSeparateTier() {
        ParsedHarEntry noise = entry("ping", "", 100L, 10L, 5L, 0L);
        ParsedHarEntry large = entry("otherGql", "gql", 1200L, 400L, 700L, 600_000L);

        HarSelectionResult result = HarApiSelector.select(List.of(noise, large), 200L);

        ParsedHarEntry selected = result.getSlowEntries().get(0);
        assertTrue(selected.isDownloadDominated());
        assertEquals(HarApiTier.HIGHLY_CRITICAL, selected.getTier());
    }

    @Test
    void selectsHighlyCriticalPriorityOutlierFirst() {
        ParsedHarEntry fast = entry("ping", "", 250L, 200L, 0L, 0L);
        ParsedHarEntry slow = entry("universalCases", "gql", 8000L, 7900L, 0L, 0L);

        HarSelectionResult result = HarApiSelector.select(List.of(fast, slow), 200L);

        assertEquals("universalCases", result.getPrimary().getApiName());
        assertEquals(HarApiTier.HIGHLY_CRITICAL, result.getSlowEntries().get(0).getTier());
    }

    @Test
    void percentileTiersFollowP50P75P95() {
        assertEquals(HarApiTier.NORMAL, HarApiSelector.classifyPercentileTier(40, 100, 200, 500));
        assertEquals(HarApiTier.HIGH, HarApiSelector.classifyPercentileTier(100, 100, 200, 500));
        assertEquals(HarApiTier.CRITICAL, HarApiSelector.classifyPercentileTier(200, 100, 200, 500));
        assertEquals(HarApiTier.HIGHLY_CRITICAL, HarApiSelector.classifyPercentileTier(500, 100, 200, 500));
    }

    @Test
    void selectsSlowestTenAtOrAboveP50() {
        List<ParsedHarEntry> candidates = new ArrayList<>();
        IntStream.range(0, 20).forEach(i ->
                candidates.add(entry("api" + i, "rest", 100L + i * 50L, 80L + i * 40L, 0L, 0L)));

        HarSelectionResult result = HarApiSelector.select(candidates, 200L);
        HarDurationStats stats = HarDurationStats.fromDurations(
                candidates.stream().map(ParsedHarEntry::getDurationMs).toList(), 200L);

        assertEquals(10, result.getSlowEntries().size());
        assertTrue(result.getSlowEntries().stream().allMatch(e -> e.getDurationMs() >= stats.getP50Ms()));
        assertTrue(result.getSelectionSummary().contains("p50="));
    }

    @Test
    void alwaysIncludesPriorityEndpointsBeyondSlowCap() {
        List<ParsedHarEntry> candidates = new ArrayList<>();
        IntStream.range(0, 20).forEach(i ->
                candidates.add(entry("api" + i, "rest", 100L + i * 50L, 80L + i * 40L, 0L, 0L)));
        candidates.add(entry("caseStreamFeed", "gql", 90L, 70L, 0L, 0L));
        candidates.add(entry("universalCases", "gql", 85L, 65L, 0L, 0L));

        HarSelectionResult result = HarApiSelector.select(candidates, 200L);

        assertEquals(12, result.getSlowEntries().size());
        assertTrue(result.getSlowEntries().stream().anyMatch(e -> "caseStreamFeed".equals(e.getApiName())));
        assertTrue(result.getSlowEntries().stream().anyMatch(e -> "universalCases".equals(e.getApiName())));
        assertTrue(result.getSlowEntries().stream().filter(ParsedHarEntry::isPriority).count() >= 2);
    }

    private static ParsedHarEntry entry(
            String apiName, String apiKind, long durationMs, long waitMs, long receiveMs, long bodyBytes) {
        return ParsedHarEntry.builder()
                .url("https://x/ui/graphql/care/" + apiName)
                .method("POST")
                .apiKind(apiKind)
                .apiName(apiName)
                .durationMs(durationMs)
                .waitMs(waitMs)
                .receiveMs(receiveMs)
                .responseBodySize(bodyBytes)
                .eventTime(Instant.parse("2026-06-22T07:20:00Z"))
                .build();
    }
}
