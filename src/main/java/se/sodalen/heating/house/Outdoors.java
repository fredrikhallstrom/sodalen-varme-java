package se.sodalen.heating.house;

// Utetemperaturen på två tidsskalor, Pythons tupel (outdoor, outdoor_summer) ur _outdoor: den
// filtrerade som kurvan läser (timmar) och säsongens som sommarstoppet läser (ett dygn).
public record Outdoors(double filtered, double summer) {
}
