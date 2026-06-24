package com.rca.common.har;

import com.rca.common.model.HarForensicsFinding;
import com.rca.common.model.SlowHarEntry;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class HarForensicsResult {
    @Builder.Default
    private List<HarForensicsFinding> findings = new ArrayList<>();
    @Builder.Default
    private List<SlowHarEntry> enrichedSlowEntries = new ArrayList<>();
}
