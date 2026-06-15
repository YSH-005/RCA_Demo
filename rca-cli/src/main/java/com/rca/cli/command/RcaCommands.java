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
import java.time.Instant;
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
            case "submit" -> handleSubmit(args);
            case "status" -> handleStatus(args);
            case "report" -> handleReport(args);
            case "poll"   -> handlePoll(args);
            default -> printHelp();
        }
    }

    private void handleSubmit(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("Usage: submit <url> <method> <durationMs> <statusCode>");
            return;
        }
        String url        = args[1];
        String method     = args[2];
        long   durationMs = Long.parseLong(args[3]);
        int    status     = Integer.parseInt(args[4]);
        String corrId     = UUID.randomUUID().toString();

        String body = mapper.writeValueAsString(java.util.Map.of(
                "correlationId",  corrId,
                "slowestUrl",     url,
                "slowestMethod",  method,
                "durationMs",     durationMs,
                "responseStatus", status,
                "timestamp",      Instant.now().toString()
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ingestionUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║           RCA Job Submitted              ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.printf ("║  Job ID  : %-30s ║%n", json.path("jobId").asText());
        System.out.printf ("║  Status  : %-30s ║%n", json.path("status").asText());
        System.out.printf ("║  Created : %-30s ║%n", json.path("createdAt").asText());
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("\nRun: status <jobId>   to check progress");
        System.out.println("Run: poll   <jobId>   to wait for completion");
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

        JsonNode report = job.path("rcaReport");
        if (report.isMissingNode() || report.isNull()) {
            System.out.println("\nNo RCA report available yet.");
            return;
        }

        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║              RCA Report                  ║");
        System.out.println("╠══════════════════════════════════════════╣");
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
                  submit <url> <method> <durationMs> <statusCode>
                      Submit a new RCA job
                      
                  status <jobId>
                      Check job status
                      
                  report <jobId>
                      View full RCA report
                      
                  poll <jobId> [timeoutSecs]
                      Wait for job completion and print report
                      
                Examples:
                  submit https://api.example.com/users GET 5000 500
                  status 6a259072788a801e0c0741cc
                  poll   6a259072788a801e0c0741cc 120
                """);
    }
}