package se.sodalen.heating.house;

// En lärares utlåtande en cykel: om den lär, varför inte (en kod texts.learning_rows sätter ord på,
// "" medan den lär) och talet som hör till (sekunder kvar av en väntan, lärda timmar; null utan).
// Pythons tupel (learning, why, number) i ControlOutput.learning.
public record LearnerStatus(boolean learning, String why, Double number) {
}
