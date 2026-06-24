package com.rca.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class AnalyzerWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyzerWorkerApplication.class, args);
    }
}