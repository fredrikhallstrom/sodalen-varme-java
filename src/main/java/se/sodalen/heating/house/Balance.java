package se.sodalen.heating.house;

// Utetemperaturen där huset, ouppvärmt, varken värms eller svalnar, och skattningens eget
// medelfel i K. Pythons tupel (balance, error).
public record Balance(double outdoor, double error) {
}
