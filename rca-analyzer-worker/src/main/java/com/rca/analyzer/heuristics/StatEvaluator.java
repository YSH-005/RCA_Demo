package com.rca.analyzer.heuristics;

import java.util.List;

final class StatEvaluator {

    private StatEvaluator() {
    }

    static boolean triggered(StatFormula formula, double value, EvalContext ctx) {
        return switch (formula) {
            case THRESHOLD -> value >= ctx.threshold();
            case SHARE_OF_TOTAL -> ctx.denominator() > 0
                    && (value / ctx.denominator()) >= ctx.ratioThreshold();
            case BASELINE_MULTIPLIER -> value >= ctx.baseline() * ctx.multiplier();
            case DELTA_OVER_BASELINE -> (value - ctx.baseline()) >= ctx.delta();
            case PRESENT -> value > 0;
            case MEDIAN_DEVIATION -> medianDeviationTriggered(value, ctx.samples(), ctx.ratioThreshold());
            case RELATIVE_BASELINE -> relativeBaselineTriggered(value, ctx);
            case PRESENT_IF_BASELINE_LOW -> value > 0 && ctx.baseline() < ctx.threshold();
            case BELOW_THRESHOLD -> value < ctx.threshold();
            case BELOW_IF_BASELINE_ABOVE -> value < ctx.threshold() && ctx.baseline() >= ctx.ratioThreshold();
        };
    }

    static double scoreContribution(StatFormula formula, double value, EvalContext ctx, double weight) {
        if (!triggered(formula, value, ctx)) {
            return 0;
        }
        double strength = switch (formula) {
            case THRESHOLD -> clamp01(value / Math.max(ctx.threshold(), 1));
            case SHARE_OF_TOTAL -> ctx.denominator() > 0
                    ? clamp01((value / ctx.denominator()) / ctx.ratioThreshold())
                    : 0;
            case BASELINE_MULTIPLIER -> ctx.baseline() > 0
                    ? clamp01(value / (ctx.baseline() * ctx.multiplier()))
                    : clamp01(value);
            case DELTA_OVER_BASELINE -> ctx.delta() > 0
                    ? clamp01((value - ctx.baseline()) / ctx.delta())
                    : 1;
            case PRESENT -> 1;
            case MEDIAN_DEVIATION -> medianDeviationStrength(value, ctx.samples(), ctx.ratioThreshold());
            case RELATIVE_BASELINE -> relativeBaselineStrength(value, ctx);
            case PRESENT_IF_BASELINE_LOW -> value > 0 && ctx.baseline() < ctx.threshold() ? 1 : 0;
            case BELOW_THRESHOLD -> value < ctx.threshold() ? 1 : 0;
            case BELOW_IF_BASELINE_ABOVE -> value < ctx.threshold() && ctx.baseline() >= ctx.ratioThreshold() ? 1 : 0;
        };
        return weight * Math.min(1.0, strength);
    }

    static double median(List<Double> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        List<Double> sorted = samples.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static boolean relativeBaselineTriggered(double incident, EvalContext ctx) {
        if (ctx.baseline() <= 0) {
            return incident >= ctx.delta();
        }
        double ratio = incident / ctx.baseline();
        return ratio >= ctx.multiplier() && (incident - ctx.baseline()) >= ctx.delta();
    }

    private static double relativeBaselineStrength(double incident, EvalContext ctx) {
        if (ctx.baseline() <= 0) {
            return ctx.delta() > 0 ? clamp01(incident / ctx.delta()) : clamp01(incident);
        }
        double ratioStrength = clamp01(ratioStrength(incident / ctx.baseline(), ctx.multiplier()));
        double deltaStrength = ctx.delta() > 0
                ? clamp01((incident - ctx.baseline()) / ctx.delta())
                : 1;
        return Math.min(ratioStrength, deltaStrength);
    }

    private static double ratioStrength(double ratio, double multiplier) {
        if (multiplier <= 0) {
            return 1;
        }
        return ratio / multiplier;
    }

    private static boolean medianDeviationTriggered(double value, List<Double> samples, double ratioThreshold) {
        if (samples == null || samples.size() < 2) {
            return false;
        }
        double med = median(samples);
        if (med <= 0) {
            return value > 0;
        }
        return Math.abs(value - med) / med >= ratioThreshold;
    }

    private static double medianDeviationStrength(double value, List<Double> samples, double ratioThreshold) {
        if (samples == null || samples.size() < 2) {
            return 0;
        }
        double med = median(samples);
        if (med <= 0) {
            return value > 0 ? 1 : 0;
        }
        return clamp01((Math.abs(value - med) / med) / ratioThreshold);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    record EvalContext(
            double threshold,
            double denominator,
            double ratioThreshold,
            double baseline,
            double multiplier,
            double delta,
            List<Double> samples
    ) {
        static EvalContext threshold(double thresholdMs) {
            return new EvalContext(thresholdMs, 0, 0, 0, 1, 0, List.of());
        }

        static EvalContext share(double denominator, double ratioThreshold) {
            return new EvalContext(0, denominator, ratioThreshold, 0, 1, 0, List.of());
        }

        static EvalContext baselineMultiplier(double baseline, double multiplier) {
            return new EvalContext(0, 0, 0, baseline, multiplier, 0, List.of());
        }

        static EvalContext deltaOverBaseline(double baseline, double delta) {
            return new EvalContext(0, 0, 0, baseline, 1, delta, List.of());
        }

        static EvalContext present() {
            return new EvalContext(0, 0, 0, 0, 1, 0, List.of());
        }

        static EvalContext medianDeviation(List<Double> samples, double ratioThreshold) {
            return new EvalContext(0, 0, ratioThreshold, 0, 1, 0, samples);
        }

        static EvalContext relativeBaseline(double baseline, double multiplier, double minDelta) {
            return new EvalContext(0, 0, 0, baseline, multiplier, minDelta, List.of());
        }

        static EvalContext presentIfBaselineLow(double baselineMax, double ceiling) {
            return new EvalContext(ceiling, 0, 0, baselineMax, 1, 0, List.of());
        }

        /** {@code threshold} is the unhealthy ceiling (e.g. 0.5 → values below are down). */
        static EvalContext belowThreshold(double threshold) {
            return new EvalContext(threshold, 0, 0, 0, 1, 0, List.of());
        }

        /**
         * Trigger when {@code incident < threshold} and {@code baseline >= healthyFloor}.
         * {@code ratioThreshold} carries the healthy floor for baseline.
         */
        static EvalContext belowIfBaselineAbove(double threshold, double baseline, double healthyFloor) {
            return new EvalContext(threshold, 0, healthyFloor, baseline, 1, 0, List.of());
        }
    }
}
