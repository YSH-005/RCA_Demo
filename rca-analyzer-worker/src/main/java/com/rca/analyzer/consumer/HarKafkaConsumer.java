package com.rca.analyzer.consumer;

import com.rca.analyzer.service.AnalyzerService;
import com.rca.common.dto.KafkaHarMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HarKafkaConsumer {

    private final AnalyzerService analyzerService;

    @KafkaListener(
            topics = "har-ingestion",
            groupId = "rca-analyzer-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(KafkaHarMessage message) {
        log.info("Consumed Kafka message: jobId={} correlationId={}",
                message.getJobId(), message.getCorrelationId());
        analyzerService.analyze(message);
    }
}