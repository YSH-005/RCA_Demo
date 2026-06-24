package com.rca.analyzer.heuristics;

import com.rca.common.har.HarForensicsAnalyzer;
import com.rca.common.har.HarForensicsResult;
import com.rca.common.har.HarParser;
import com.rca.common.har.HarSelectionResult;
import com.rca.common.model.HarForensicsFinding;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HarForensicsAnalyzerTest {

    @Test
    void detectsHighTtfbPollingAndCompressionIssues() {
        String har = """
                {
                  "log": {
                    "entries": [
                      {
                        "startedDateTime": "2026-06-22T07:23:09.861Z",
                        "time": 6200,
                        "request": {
                          "method": "POST",
                          "url": "https://app.example.com/ui/graphql/care/universalCases?op=UniversalCaseLookup",
                          "headers": [{"name":"x-request-id","value":"req-1"}],
                          "postData": {"text": "{\\"operationName\\":\\"UniversalCaseLookup\\"}"}
                        },
                        "response": {
                          "status": 200,
                          "headers": [
                            {"name":"content-type","value":"application/json"},
                            {"name":"x-cache","value":"Miss from cloudfront"}
                          ],
                          "content": {"size": 600000, "mimeType": "application/json"},
                          "bodySize": 600000
                        },
                        "timings": {"blocked":0,"dns":0,"connect":0,"ssl":0,"send":10,"wait":6100,"receive":90}
                      },
                      {
                        "startedDateTime": "2026-06-22T07:23:12.000Z",
                        "time": 6100,
                        "request": {
                          "method": "POST",
                          "url": "https://app.example.com/ui/graphql/care/universalCases?op=UniversalCaseLookup",
                          "headers": [{"name":"x-request-id","value":"req-2"}]
                        },
                        "response": {"status": 200, "headers": [], "content": {"size": 1000, "mimeType":"application/json"}},
                        "timings": {"blocked":0,"dns":0,"connect":0,"ssl":0,"send":10,"wait":6000,"receive":90}
                      },
                      {
                        "startedDateTime": "2026-06-22T07:23:15.000Z",
                        "time": 6050,
                        "request": {
                          "method": "POST",
                          "url": "https://app.example.com/ui/graphql/care/universalCases?op=UniversalCaseLookup",
                          "headers": [{"name":"x-request-id","value":"req-3"}]
                        },
                        "response": {"status": 200, "headers": [], "content": {"size": 1000, "mimeType":"application/json"}},
                        "timings": {"blocked":0,"dns":0,"connect":0,"ssl":0,"send":10,"wait":5950,"receive":90}
                      },
                      {
                        "startedDateTime": "2026-06-22T07:23:18.000Z",
                        "time": 6080,
                        "request": {
                          "method": "POST",
                          "url": "https://app.example.com/ui/graphql/care/universalCases?op=UniversalCaseLookup",
                          "headers": [{"name":"x-request-id","value":"req-4"}]
                        },
                        "response": {"status": 200, "headers": [], "content": {"size": 1000, "mimeType":"application/json"}},
                        "timings": {"blocked":0,"dns":0,"connect":0,"ssl":0,"send":10,"wait":5980,"receive":90}
                      },
                      {
                        "startedDateTime": "2026-06-22T07:23:20.000Z",
                        "time": 300,
                        "request": {"method":"GET","url":"https://cdn.example.com/assets/logo.png"},
                        "response": {"status":200,"headers":[],"content":{"size":300000,"mimeType":"image/png"}},
                        "timings": {"blocked":0,"dns":0,"connect":0,"ssl":0,"send":0,"wait":50,"receive":250}
                      }
                    ]
                  }
                }
                """;
        byte[] bytes = har.getBytes(StandardCharsets.UTF_8);
        HarSelectionResult selection = HarParser.selectSlow(bytes, null, null, 0);
        HarForensicsResult result = HarForensicsAnalyzer.analyze(bytes, selection);

        Set<String> ids = result.getFindings().stream()
                .map(HarForensicsFinding::getIssueId)
                .collect(Collectors.toSet());

        assertTrue(ids.contains("P01_high_ttfb"));
        assertTrue(ids.contains("P06_polling"));
        assertTrue(ids.contains("P11_api_over_fetching"));
        assertTrue(ids.contains("P12_unoptimized_images"));
    }
}
