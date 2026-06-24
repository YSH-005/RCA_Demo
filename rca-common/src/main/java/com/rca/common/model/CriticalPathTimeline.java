package com.rca.common.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CriticalPathTimeline {
    private List<TimelineSegment> segments = new ArrayList<>();
    private long totalMs;
    private String dominantPhase;
    private long dominantMs;
}
