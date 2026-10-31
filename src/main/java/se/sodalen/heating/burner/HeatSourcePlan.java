package se.sodalen.heating.burner;

// Vad varje relä ska göra den här cykeln. null betyder lämna det i fred, vilket inte är samma som false:
// under en utbrinning är huvudströmmen med avsikt kvar.
public record HeatSourcePlan(Boolean control, Boolean power, boolean burningOut) {
}
