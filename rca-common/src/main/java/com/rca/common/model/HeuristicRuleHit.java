package com.rca.common.model;

import com.rca.common.enums.BottleneckCategory;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HeuristicRuleHit {
    private String ruleId;
    private BottleneckCategory category;
    /** e.g. SHARE_OF_TOTAL, BASELINE_MULTIPLIER, THRESHOLD */
    private String formula;
    private double scoreContribution;
    private String evidence;
    private boolean triggered;
    /** true when signal came from stub data — excluded from scoring */
    private boolean stubSource;
}
