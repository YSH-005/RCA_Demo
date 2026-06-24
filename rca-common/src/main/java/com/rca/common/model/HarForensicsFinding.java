package com.rca.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HarForensicsFinding {
    private String issueId;
    private String title;
    private String symptom;
    private String evidence;
    private String recommendation;
    private String url;
    private boolean triggered;
}
