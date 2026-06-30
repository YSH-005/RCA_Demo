package com.rca.common.har;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class HarSelectionResult {
    private ParsedHarEntry primary;
    @Builder.Default
    private List<ParsedHarEntry> slowEntries = new ArrayList<>();
    private int totalApiCandidates;
    private int aboveThresholdCount;
    private String selectionSummary;
}
