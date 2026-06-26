package com.rca.analyzer.har;

import com.rca.common.har.HarApiSelector;
import com.rca.common.har.HarApiTier;
import com.rca.common.har.HarSelectionResult;
import com.rca.common.har.ParsedHarEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarApiSelectorTest {

    @Test
    void classifiesSideLargeWhenDownloadDominates() {
        ParsedHarEntry noise = ParsedHarEntry.builder()
                .url("https://x/api/ping")
                .method("GET")
                .durationMs(100L)
                .eventTime(Instant.parse("2026-06-22T07:19:59Z"))
                .build();
        ParsedHarEntry entry = ParsedHarEntry.builder()
                .url("https://x/ui/graphql/care/otherGql")
                .method("POST")
                .apiKind("gql")
                .apiName("otherGql")
                .durationMs(1200L)
                .waitMs(400L)
                .receiveMs(700L)
                .responseBodySize(600_000L)
                .eventTime(Instant.parse("2026-06-22T07:20:00Z"))
                .build();

        HarSelectionResult result = HarApiSelector.select(List.of(noise, entry), 200L);

        assertEquals(HarApiTier.SIDE_LARGE, result.getSlowEntries().get(0).getTier());
    }

    @Test
    void selectsCriticalPriorityOutlierFirst() {
        ParsedHarEntry fast = ParsedHarEntry.builder()
                .url("https://x/api/ping")
                .method("GET")
                .apiKind("")
                .apiName("")
                .durationMs(250L)
                .eventTime(Instant.parse("2026-06-22T07:20:00Z"))
                .build();
        ParsedHarEntry slow = ParsedHarEntry.builder()
                .url("https://x/ui/graphql/care/universalCases")
                .method("POST")
                .apiKind("gql")
                .apiName("universalCases")
                .durationMs(8000L)
                .waitMs(7900L)
                .eventTime(Instant.parse("2026-06-22T07:20:01Z"))
                .build();

        HarSelectionResult result = HarApiSelector.select(List.of(fast, slow), 200L);

        assertEquals("universalCases", result.getPrimary().getApiName());
        assertEquals(HarApiTier.CRITICAL, result.getSlowEntries().get(0).getTier());
    }
}
