# Steg 3: Värmekurvan

> **Originalet:** [`curve.py`](curve.py) i den här mappen är Pythonkoden du
> översätter. Varje funktion där blir en metod här, oftast rad för rad.

Hur varmt tilloppet ska vara vid en viss utetemperatur:

```
tillopp = rum + lutning * spann * B ^ böjning
B       = (balans - ute) / (balans - dimensionerande ute), aldrig under 0
spann   = rum - dimensionerande ute (minst 1)
```

- **Lutningen** avgör den kalla änden. Vid dimensionerande ute (-18) är B
  alltid 1, vad balansen än är.
- **Balansen** är utetemperaturen där huset slutar behöva värme, eftersom
  folk, apparater och sol värmer resten. Den lärs in under hösten.
- **Böjningen** (0,8) kommer av att radiatorer avger mer än linjärt när de
  blir varmare (EN 442, exponent 1,3).

**EVR-skalan** är kurvnumren 0–7 på shuntautomatens karta i pannrummet. Den
används bara för att visa lutningen i siffror som en värmetekniker känner
igen.

## Testerna väntar sig

```java
package se.sodalen.heating.curve;

public final class Curve {
    public static final List<Double> EVR_RISE     // 0.42, 0.58, 0.74, 0.91, 1.32, 1.74, 2.24, 2.73
    public static double supply(double outdoor, double room, double designOutdoor,
                                double slope, double curvature, double balance)
    public static double balanceThrough(double outdoor, double wanted, double room,
                                        double designOutdoor, double slope, double curvature)
    public static double evrNumber(double slope, double room, double designOutdoor)
    public static double evrSlope(double number, double room, double designOutdoor)
    public static double indoorSensitivity(double outdoor, double room, double designOutdoor,
                                           double slope, double curvature, double balance)
    public static double middleDeviation(double room, double designOutdoor, double slope,
                                         double balance, double curvature, double reference)
}
```

`balanceThrough` är `supply` baklänges: vilken balans ger just det här
tilloppet vid just det här vädret? Det är så inlärningen flyttar kurvan.
`MAX_SHARE` (0,95) hindrar att svaret rusar mot oändligheten.
