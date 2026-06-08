package com.rca.simulator.controller;

import com.rca.simulator.service.SimulatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/simulator")
@RequiredArgsConstructor
public class SimulatorController {

    private final SimulatorService simulatorService;

    // Manually trigger one simulation without waiting for the schedule
    @PostMapping("/trigger")
    public ResponseEntity<String> trigger() {
        simulatorService.simulateTraffic();
        return ResponseEntity.ok("Simulation triggered");
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("rca-simulator is up");
    }
}