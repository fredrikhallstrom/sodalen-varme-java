package se.sodalen.heating.burner;

// Tupeln från relays_in_use: (styrkabel, huvudström).
public record Relays(boolean control, boolean power) {
}
