package se.sodalen.heating.house;

// Innetemperaturen på två tidsskalor, Pythons tupel (indoor, indoor_slow) ur _indoor: den
// filtrerade som rumskorrigeringen läser och den långsamma som inläraren läser. Båda null utan
// givare, aldrig ett gammalt värde.
public record Indoors(Double fused, Double slow) {
}
