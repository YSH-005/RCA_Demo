package com.rca.common.enums;

/**
 * Problem-statement bottleneck taxonomy:
 * (A) Network, (B) Backend/CPU, (C) Database/ES, (D) Exceptions.
 */
public enum BottleneckCategory {
    A_NETWORK,
    B_BACKEND_CPU,
    C_DATABASE,
    D_EXCEPTION,
    UNKNOWN;

    public String label() {
        return switch (this) {
            case A_NETWORK -> "A — Network latency";
            case B_BACKEND_CPU -> "B — Backend / CPU";
            case C_DATABASE -> "C — Database / ES execution";
            case D_EXCEPTION -> "D — Unhandled exception";
            case UNKNOWN -> "Unknown";
        };
    }
}
