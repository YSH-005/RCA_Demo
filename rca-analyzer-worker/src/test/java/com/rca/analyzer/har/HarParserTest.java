package com.rca.analyzer.har;

import com.rca.common.har.HarApiTier;
import com.rca.common.har.HarDurationStats;
import com.rca.common.har.HarParser;
import com.rca.common.har.HarSelectionPolicy;
import com.rca.common.har.HarSelectionResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarParserTest {

    @Test
    void selectSlow_includesAllPriorityEndpointsAboveFloor() {
        String har = """
                {
                  "log": {
                    "entries": [
                      {"startedDateTime":"2026-06-22T07:20:00.000Z","time":6100,"request":{"method":"POST","url":"https://x/ui/graphql/care/universalCases","headers":[{"name":"x-request-id","value":"space-test"}]},"response":{"status":200},"timings":{"wait":6098,"receive":70}},
                      {"startedDateTime":"2026-06-22T07:20:01.000Z","time":3200,"request":{"method":"POST","url":"https://x/ui/graphql/care/caseStreamFeed","headers":[{"name":"x-request-id","value":"space-test"}]},"response":{"status":200},"timings":{"wait":3100,"receive":50}}
                    ]
                  }
                }
                """;

        HarSelectionResult result = HarParser.selectSlow(
                har.getBytes(StandardCharsets.UTF_8), null, null, 0);

        assertEquals(2, result.getSlowEntries().size());
        assertTrue(result.getSlowEntries().stream().anyMatch(e -> "universalCases".equals(e.getApiName())));
        assertTrue(result.getSlowEntries().stream().anyMatch(e -> "caseStreamFeed".equals(e.getApiName())));
        assertEquals("universalCases", result.getPrimary().getApiName());
        assertEquals(HarApiTier.HIGHLY_CRITICAL, result.getPrimary().getTier());
        assertTrue(result.getSelectionSummary().contains("p50="));
    }

    @Test
    void selectSlow_includesApisAtOrAboveSessionP50() {
        String har = """
                {
                  "log": {
                    "entries": [
                      {"startedDateTime":"2026-06-22T07:20:00.000Z","time":80,"request":{"method":"POST","url":"https://x/ui/graphql/care/caseStreamFeed","headers":[]},"response":{"status":200},"timings":{"wait":70,"receive":5}},
                      {"startedDateTime":"2026-06-22T07:20:01.000Z","time":95,"request":{"method":"POST","url":"https://x/ui/graphql/care/caseStreamFeed","headers":[]},"response":{"status":200},"timings":{"wait":81,"receive":2}},
                      {"startedDateTime":"2026-06-22T07:20:02.000Z","time":120,"request":{"method":"POST","url":"https://x/ui/graphql/care/caseStreamFeed","headers":[]},"response":{"status":200},"timings":{"wait":100,"receive":10}},
                      {"startedDateTime":"2026-06-22T07:20:03.000Z","time":5000,"request":{"method":"POST","url":"https://x/ui/graphql/care/universalCases","headers":[{"name":"x-request-id","value":"space-slow"}]},"response":{"status":200},"timings":{"wait":4900,"receive":50}}
                    ]
                  }
                }
                """;

        HarSelectionResult result = HarParser.selectSlow(
                har.getBytes(StandardCharsets.UTF_8), null, null, 0);

        assertTrue(result.getSlowEntries().size() >= 2);
        assertEquals("universalCases", result.getPrimary().getApiName());
        assertEquals(HarApiTier.HIGHLY_CRITICAL, result.getPrimary().getTier());
        assertTrue(result.getSelectionSummary().contains("p50="));
    }

    @Test
    void durationStats_computesTukeyFence() {
        HarDurationStats stats = HarDurationStats.fromDurations(
                List.of(50L, 80L, 95L, 120L, 5000L), 200L);

        assertEquals(95L, stats.getMedianMs());
        assertEquals(95L, stats.getP50Ms());
        assertTrue(stats.getP95Ms() >= stats.getP75Ms());
        assertTrue(stats.getOutlierThresholdMs() >= 200L);
        assertTrue(stats.getP50Ms() <= stats.getP75Ms());
        assertTrue(stats.getP75Ms() <= stats.getP95Ms());
    }
}
