package com.rca.analyzer.har;

import com.rca.common.har.HarParser;
import com.rca.common.har.HarSelectionResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarParserTest {

    @Test
    void selectSlow_includesAllPriorityEndpointsAboveThreshold() {
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
                har.getBytes(StandardCharsets.UTF_8), null, null, 2000);

        assertEquals(2, result.getSlowEntries().size());
        assertTrue(result.getSlowEntries().stream().anyMatch(e -> "universalCases".equals(e.getApiName())));
        assertEquals("universalCases", result.getPrimary().getApiName());
    }
}
