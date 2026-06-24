package com.rca.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@Component
public class RcaCommands implements CommandLineRunner {

    @Value("${rca.ingestion-url}")
    private String ingestionUrl;

    @Value("${rca.query-url}")
    private String queryUrl;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return;
        }

        switch (args[0]) {
            case "analyze" -> handleAnalyze(args);
            case "status" -> handleStatus(args);
            case "report" -> handleReport(args);
            case "poll"   -> handlePoll(args);
            default -> printHelp();
        }
    }

    private void handleAnalyze(String[] args) throws Exception {
        String harPath = null;
        String from = null;
        String to = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--har" -> harPath = args[++i];
                case "--from" -> from = args[++i];
                case "--to" -> to = args[++i];
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (harPath == null) {
            System.out.println("Usage: analyze --har <file.har> [--from <iso>] [--to <iso>]");
            return;
        }

        Path file = Path.of(harPath);
        if (!Files.exists(file)) {
            System.out.println("HAR file not found: " + harPath);
            return;
        }

        String boundary = "----RcaBoundary" + UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, file, from, to);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ingestionUrl))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            System.out.println("Analyze failed: HTTP " + resp.statusCode());
            System.out.println(resp.body());
            return;
        }

        JsonNode json = mapper.readTree(resp.body());
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║           RCA Job Submitted              ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.printf ("║  Job ID  : %-30s ║%n", json.path("jobId").asText());
        System.out.printf ("║  Status  : %-30s ║%n", json.path("status").asText());
        System.out.printf ("║  Created : %-30s ║%n", json.path("createdAt").asText());
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("\nRun: poll <jobId> to wait for completion");
    }

    private byte[] buildMultipartBody(String boundary, Path harFile, String from, String to) throws Exception {
        String filePart = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + harFile.getFileName() + "\"\r\n"
                + "Content-Type: application/json\r\n\r\n";
        String end = "\r\n--" + boundary + "--\r\n";

        var baos = new java.io.ByteArrayOutputStream();
        baos.write(filePart.getBytes());
        baos.write(Files.readAllBytes(harFile));

        if (from != null) {
            baos.write(("\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"from\"\r\n\r\n"
                    + from).getBytes());
        }
        if (to != null) {
            baos.write(("\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"to\"\r\n\r\n"
                    + to).getBytes());
        }

        baos.write(end.getBytes());
        return baos.toByteArray();
    }

    private void handleStatus(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("Usage: status <jobId>"); return; }
        JsonNode job = fetchJob(args[1]);
        printJobSummary(job);
    }

    private void handleReport(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("Usage: report <jobId>"); return; }
        JsonNode job = fetchJob(args[1]);
        printFullReport(job);
    }

    private void handlePoll(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("Usage: poll <jobId> [timeoutSecs]"); return; }
        String jobId   = args[1];
        int    timeout = args.length >= 3 ? Integer.parseInt(args[2]) : 120;
        int    elapsed = 0;

        System.out.printf("Polling job %s (timeout: %ds)...%n", jobId, timeout);

        while (elapsed < timeout) {
            JsonNode job    = fetchJob(jobId);
            String   status = job.path("status").asText();

            System.out.printf("[%3ds] status: %s%n", elapsed, status);

            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                System.out.println();
                printFullReport(job);
                return;
            }

            Thread.sleep(5000);
            elapsed += 5;
        }

        System.out.println("Timeout reached — job still processing.");
    }

    private JsonNode fetchJob(String jobId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(queryUrl + "/" + jobId))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            System.out.println("Job not found: " + jobId);
            System.exit(1);
        }
        return mapper.readTree(resp.body());
    }

    private void printJobSummary(JsonNode job) {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║              Job Status                  ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.printf ("║  Job ID      : %-26s ║%n", job.path("id").asText());
        System.out.printf ("║  Status      : %-26s ║%n", job.path("status").asText());
        System.out.printf ("║  Created     : %-26s ║%n", job.path("createdAt").asText("-"));
        System.out.printf ("║  Completed   : %-26s ║%n", job.path("completedAt").asText("-"));
        System.out.printf ("║  ProcessedMs : %-26s ║%n", job.path("processingMs").asText("-"));
        System.out.println("╚══════════════════════════════════════════╝");
    }

    private void printFullReport(JsonNode job) {
        printJobSummary(job);

        JsonNode telemetry = job.path("telemetry");
        if (!telemetry.isMissingNode() && !telemetry.isNull()) {
            System.out.println("\nTelemetry:");
            System.out.printf("  requestId : %s%n", telemetry.path("requestId").asText("-"));
            System.out.printf("  api       : %s / %s%n",
                    telemetry.path("harApiKind").asText("-"),
                    telemetry.path("harApiName").asText("-"));
            System.out.printf("  url       : %s%n", telemetry.path("slowestUrl").asText("-"));
            System.out.printf("  har wait  : %s ms%n", telemetry.path("harWaitMs").asText("-"));
            System.out.printf("  pod       : %s%n", telemetry.path("podName").asText("-"));
            int kibanaCount = telemetry.path("kibanaLogs").isArray() ? telemetry.path("kibanaLogs").size() : 0;
            int graylogCount = telemetry.path("graylogLogs").isArray() ? telemetry.path("graylogLogs").size() : 0;
            System.out.printf("  kibana    : %d log(s)%n", kibanaCount);
            System.out.printf("  graylog   : %d log(s)%n", graylogCount);
        }

        JsonNode report = job.path("rcaReport");
        if (report.isMissingNode() || report.isNull()) {
            System.out.println("\nNo RCA report available yet.");
            return;
        }

        JsonNode findings = report.path("structuredFindings");
        if (!findings.isMissingNode() && findings.has("observabilitySources")) {
            JsonNode sources = findings.path("observabilitySources");
            System.out.printf("%nObservability: kibana=%s graylog=%s grafana=%s%n",
                    sources.path("kibana").asText("-"),
                    sources.path("graylog").asText("-"),
                    sources.path("grafana").asText("-"));
        }

        JsonNode scores = report.path("categoryScores");
        if (scores.isObject() && !scores.isEmpty()) {
            System.out.print("Category scores: ");
            scores.fields().forEachRemaining(e ->
                    System.out.printf("%s=%.2f ", e.getKey(), e.getValue().asDouble()));
            System.out.println();
        }

        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║              RCA Report                  ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.printf ("║  Bottleneck  : %-26s ║%n",
                report.path("bottleneckCategory").asText(report.path("faultClassification").asText()));
        System.out.printf ("║  Fault       : %-26s ║%n",
                report.path("faultClassification").asText());
        System.out.printf ("║  Confidence  : %-26s ║%n",
                report.path("confidenceScore").asText());
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  Summary:                                ║");
        wrapPrint(report.path("summary").asText(), 40);
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  Evidence:                               ║");
        report.path("evidence").forEach(e -> System.out.println("║  • " + truncate(e.asText(), 37) + " ║"));
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  Recommended Actions:                    ║");
        report.path("recommendedActions").forEach(a -> System.out.println("║  → " + truncate(a.asText(), 37) + " ║"));
        System.out.println("╚══════════════════════════════════════════╝");
    }

    private void wrapPrint(String text, int width) {
        for (int i = 0; i < text.length(); i += width) {
            String chunk = text.substring(i, Math.min(i + width, text.length()));
            System.out.printf("║  %-40s ║%n", chunk);
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private void printHelp() {
        System.out.println("""
                RCA CLI — Root Cause Analysis Tool
                ════════════════════════════════════
                Commands:
                  analyze --har <file.har> [--from <iso>] [--to <iso>]
                      Upload a HAR file for RCA
                      
                  status <jobId>
                      Check job status
                      
                  report <jobId>
                      View full RCA report
                      
                  poll <jobId> [timeoutSecs]
                      Wait for job completion and print report
                      
                Examples:
                  analyze --har capture.har
                  analyze --har capture.har --from 2026-06-22T07:16:09Z --to 2026-06-22T07:31:09Z
                  poll 6a259072788a801e0c0741cc 120
                """);
    }
}
