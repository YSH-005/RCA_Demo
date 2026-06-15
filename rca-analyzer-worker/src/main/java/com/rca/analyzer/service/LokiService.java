package com.rca.analyzer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
public class LokiService {

    private final Random random = new Random();

    public List<String> fetchLogs(String correlationId, String startTime, String endTime) {
        log.info("Simulating logs for correlationId={}", correlationId);

        int scenario = random.nextInt(5);
        return switch (scenario) {
            case 0 -> databaseBottleneckLogs(correlationId);
            case 1 -> cpuSaturationLogs(correlationId);
            case 2 -> memoryPressureLogs(correlationId);
            case 3 -> networkTimeoutLogs(correlationId);
            default -> slowQueryLogs(correlationId);
        };
    }

    private List<String> databaseBottleneckLogs(String corrId) {
        String ts = Instant.now().toString();
        return List.of(
            ts + " INFO  [http-nio-8080-exec-3] c.e.api.UserController - Incoming GET /users correlationId=" + corrId,
            ts + " DEBUG [http-nio-8080-exec-3] c.e.service.UserService - Fetching users from DB",
            ts + " WARN  [http-nio-8080-exec-3] c.e.repo.UserRepository - Connection pool exhausted, waiting for available connection (poolSize=10, active=10)",
            ts + " WARN  [http-nio-8080-exec-3] c.e.repo.UserRepository - Slow query detected: SELECT * FROM users WHERE active=true took 3240ms",
            ts + " WARN  [http-nio-8080-exec-3] c.e.repo.UserRepository - Slow query detected: SELECT count(*) FROM orders WHERE user_id IN (...) took 1890ms",
            ts + " ERROR [http-nio-8080-exec-3] c.e.db.ConnectionPool - HikariPool-1 - Connection is not available, request timed out after 5000ms",
            ts + " ERROR [http-nio-8080-exec-3] c.e.service.UserService - Database operation failed: could not acquire connection from pool",
            ts + " INFO  [http-nio-8080-exec-3] c.e.api.UserController - Response 500 sent after 6120ms correlationId=" + corrId
        );
    }

    private List<String> cpuSaturationLogs(String corrId) {
        String ts = Instant.now().toString();
        return List.of(
            ts + " INFO  [http-nio-8080-exec-7] c.e.api.ReportController - Incoming GET /reports/daily correlationId=" + corrId,
            ts + " DEBUG [http-nio-8080-exec-7] c.e.service.ReportService - Starting report generation",
            ts + " WARN  [scheduling-1] c.e.monitor.SystemMonitor - CPU usage at 94% (threshold: 80%)",
            ts + " WARN  [scheduling-1] c.e.monitor.SystemMonitor - CPU usage at 97% - all cores saturated",
            ts + " WARN  [http-nio-8080-exec-7] c.e.service.ReportService - Thread pool queue depth: 48/50 - near capacity",
            ts + " WARN  [http-nio-8080-exec-7] c.e.service.ReportService - Request processing delayed due to CPU contention, waited 2100ms in queue",
            ts + " ERROR [http-nio-8080-exec-7] c.e.service.ReportService - Task execution rejected: thread pool exhausted",
            ts + " INFO  [http-nio-8080-exec-7] c.e.api.ReportController - Response 503 sent after 7340ms correlationId=" + corrId
        );
    }

    private List<String> memoryPressureLogs(String corrId) {
        String ts = Instant.now().toString();
        return List.of(
            ts + " INFO  [http-nio-8080-exec-2] c.e.api.PaymentController - Incoming POST /payments correlationId=" + corrId,
            ts + " DEBUG [http-nio-8080-exec-2] c.e.service.PaymentService - Processing payment batch size=1500",
            ts + " WARN  [GC-Monitor] c.e.monitor.JvmMonitor - GC pause detected: G1 GC pause 1240ms - heap 87% used",
            ts + " WARN  [GC-Monitor] c.e.monitor.JvmMonitor - GC pause detected: G1 GC pause 2100ms - heap 93% used",
            ts + " WARN  [http-nio-8080-exec-2] c.e.service.PaymentService - Large object allocation detected: 245MB for payment batch processing",
            ts + " ERROR [GC-Monitor] c.e.monitor.JvmMonitor - GC overhead limit exceeded - JVM spending >98% time in GC",
            ts + " ERROR [http-nio-8080-exec-2] c.e.service.PaymentService - OutOfMemoryError: Java heap space",
            ts + " INFO  [http-nio-8080-exec-2] c.e.api.PaymentController - Response 500 sent after 9870ms correlationId=" + corrId
        );
    }

    private List<String> networkTimeoutLogs(String corrId) {
        String ts = Instant.now().toString();
        return List.of(
            ts + " INFO  [http-nio-8080-exec-5] c.e.api.InventoryController - Incoming PUT /inventory/update correlationId=" + corrId,
            ts + " DEBUG [http-nio-8080-exec-5] c.e.service.InventoryService - Calling upstream warehouse service at warehouse-api:8090",
            ts + " WARN  [http-nio-8080-exec-5] c.e.client.WarehouseClient - Connect attempt 1 to warehouse-api:8090 timed out after 1000ms",
            ts + " WARN  [http-nio-8080-exec-5] c.e.client.WarehouseClient - Connect attempt 2 to warehouse-api:8090 timed out after 1000ms",
            ts + " WARN  [http-nio-8080-exec-5] c.e.client.WarehouseClient - Connect attempt 3 to warehouse-api:8090 timed out after 1000ms",
            ts + " ERROR [http-nio-8080-exec-5] c.e.client.WarehouseClient - All retry attempts exhausted: java.net.SocketTimeoutException: Read timed out",
            ts + " ERROR [http-nio-8080-exec-5] c.e.service.InventoryService - Upstream dependency failure: warehouse-api unreachable after 3 retries",
            ts + " INFO  [http-nio-8080-exec-5] c.e.api.InventoryController - Response 504 sent after 4560ms correlationId=" + corrId
        );
    }

    private List<String> slowQueryLogs(String corrId) {
        String ts = Instant.now().toString();
        return List.of(
            ts + " INFO  [http-nio-8080-exec-1] c.e.api.SearchController - Incoming GET /products/search correlationId=" + corrId,
            ts + " DEBUG [http-nio-8080-exec-1] c.e.service.SearchService - Executing search query with 12 filters",
            ts + " WARN  [http-nio-8080-exec-1] c.e.repo.SearchRepository - COLLSCAN detected on collection 'products' - missing index on field 'category'",
            ts + " WARN  [http-nio-8080-exec-1] c.e.repo.SearchRepository - Query examined 2450000 documents, returned 142 - poor selectivity",
            ts + " WARN  [http-nio-8080-exec-1] c.e.service.SearchService - Search query exceeded 3000ms SLA: actual=4230ms",
            ts + " INFO  [http-nio-8080-exec-1] c.e.api.SearchController - Response 200 sent after 4350ms correlationId=" + corrId
        );
    }
}
