package se.sodalen.heating.forecast;

import java.util.Map;

// De bästa vikterna för några källor och spridningen som följer med dem: Pythons (dict, float | None).
public record Weighting(Map<String, Double> weights, Double spread) {
}
