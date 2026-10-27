package se.sodalen.heating.house;

// En lucka i vädret framåt: (timmar härifrån, vädret då). Så ser prognosen ut när den gått genom
// ensemblen i steg 8, och en lista av dem är det outlook går igenom.
public record Forecast(double hours, Conditions conditions) {
}
