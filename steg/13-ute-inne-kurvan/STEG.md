# Steg 13: Ute, inne, klockorna och kurvan

> **Originalet:** [`controller.py`](controller.py) i den här mappen, raderna
> `_outdoor` till och med `curve_at` (440–612). Det här är den första fasen i
> `step()`: det som mäts görs om till de tal resten av cykeln räknar på.

`step()` börjar så här (raderna 274–284), och det är de fem anropen som är
steget:

```python
outdoor, outdoor_summer = self._outdoor(inputs, dt, out)
indoor, indoor_slow = self._indoor(inputs, dt, out)
self._timers(inputs, dt, out)
...
curve_outdoor = self._curve_outdoor(inputs.outdoor, outdoor)
curve_flow, effective_slope = self._curve(curve_outdoor)
```

Fem delar:

- **`outdoor`:** utetemperaturen på två tidsskalor. Kurvan vill ha timmar,
  ungefär så fort som ett hus svarar på vädret. Sommarstoppet vill ha ett
  dygn, för det avgör årstiden och inte eftermiddagen. Utan givare följer
  kurvan prognosen som hämtades innan nätet försvann, med flaggan
  `outdoor_forecast`, och har ingen temperatur någonsin mätts antas
  dimensionerande kyla: att gissa fel åt det hållet kostar ved, åt andra
  hållet ett fruset hus.
- **`indoor`:** `fuseIndoor` från steg 12 genom två filter, det snabba som
  rumskorrigeringen läser och det långsamma som inläraren läser. Saknas
  givarna blir svaret null, aldrig ett gammalt värde: en inaktuell
  innetemperatur ser ut som fungerande återkoppling och skulle hålla huset
  där det var när givarna dog.
- **`timers`:** klockorna som inlärningens grindar läser i steg 18. De
  flyttas här, före lägesbeslutet, och några av dem nollas senare i
  `step()` när läget och börvärdet är kända. Därför bara räknar
  `secondsSinceCoast`, `secondsSinceShortfall` och `secondsSinceOff` upp
  här. Vakthundens klocka är särskild: Shellyn säger själv hur länge sedan
  den lämnade tillbaka ventilen, och det talet får dra ner klockan men
  aldrig vrida upp den.
- **`curveOutdoor`:** det ute kurvan läser. Normalt den filtrerade, men i
  en kallfront en del av vägen ner mot det som mäts just nu, högst
  `outdoorFallLimit` grader för en givare som gått sönder. Natten
  2026-11-05 föll det 6 grader på sju timmar och huset sjönk 0,9 under
  målet med ett tretimmarssnitt som släpade efter.
- **Kurvan:** `curve` ger tilloppet och lutningen det räknades med,
  `effectiveSlope` är installatörens lutning plus det lärda inom
  `slopeMin`–`slopeMax`, `balance` är rumsmålet plus den lärda
  förskjutningen, `curvature` är tabellens böj när den lärt en, och
  `curveAt` ritar kurvan med gränserna `flowMin`–`flowMax` på. Själva
  `curve` håller inte tilloppet inom gränserna, det gör `step()` med hela
  börvärdet.

## Testerna väntar sig

Skalet ligger i `src/main/java/se/sodalen/heating/house/`: tre nya record
för Pythons tupler, och metoderna sist i `HeatingController`.

```java
package se.sodalen.heating.house;

public record Outdoors(double filtered, double summer)      // Pythons (outdoor, outdoor_summer)
public record Indoors(Double fused, Double slow)            // Pythons (indoor, indoor_slow); null, null utan givare
public record CurvePoint(double flow, double slope)         // Pythons (curve_flow, effective_slope)

public class HeatingController {
    Outdoors outdoor(ControlInputs inputs, double dt, ControlOutput out)      // _outdoor
    Indoors indoor(ControlInputs inputs, double dt, ControlOutput out)        // _indoor
    void timers(ControlInputs inputs, double dt, ControlOutput out)           // _timers
    double curveOutdoor(Double measured, double filtered)                     // _curve_outdoor
    CurvePoint curve(double outdoor)                                          // _curve
    public double curvature()
    public double effectiveSlope()
    public double balance()
    double curveFlow(double outdoor, double slope, double balance)            // _curve_flow utan böj
    double curveFlow(double outdoor, double slope, double balance, Double curvature)
    public double curveAt(double outdoor)                                     // kurvan i bruk
    public double curveAt(double outdoor, Double slope, Double balance, Double curvature)
}
```

Innan du börjar: `ControlOutput` i Python har sedan 70aabd9 fältet
`curve_outdoor` mellan `outdoor_filtered` och `outdoor_summer`. Lägg till
`public Double curveOutdoor = null;` där. Inget test i det här steget
läser det, men `step()` i steg 20 skriver det.

Där Python och Java skiljer sig:

- **Tuplerna** `return filtered, seasonal` blir `new Outdoors(filtered,
  seasonal)`, som `IndoorFusion` i steg 12.
- **`outdoor_from_forecast`** finns redan: `Forecasts.outdoorFromForecast`
  i paketet `forecast`, från steg 8. `curve.supply` är `Curve.supply` i
  paketet `curve`, steg 3, och `effective_curvature` är
  `Model.effectiveCurvature`, steg 11.
- **`item.contributed`** är en property i Python och metoden
  `contributed()` i Java.
- **`self.state.indoor_slow.value`** är `state.indoorSlow.value()`, som
  kan vara null.
- **`inputs.emergency in EMERGENCY_KINDS`** är
  `Model.EMERGENCY_KINDS.contains(inputs.emergency)`.
- **Pythons valfria argument** i `_curve_flow` och `curve_at` blir
  överlagringar, där null är "den i bruk".

## Ordning

1. `curveOutdoor` (`ReadingsAndCurveTest.TheColdFront`): sex tester, en
   rad kod.
2. `effectiveSlope`, `balance`, `curvature`, `curveFlow`, `curve` och
   `curveAt` (`TheCurve`).
3. `outdoor` (`TheOutdoor`).
4. `indoor` (`TheIndoor`).
5. `timers` (`TheTimers`): längst, men varje klocka är tre rader.
6. `SameAsPython`: fyra facitfiler, 2 819 fall.

## Klurigt

- **`seasonal if seasonal is not None else filtered`:** sommarfiltret kan
  sakna värde fast det snabba har ett, om det lagrade tillståndet kommer
  från en äldre version. Då får sommaren den filtrerade.
- **`if filtered is None`** är inte "ingen mätning den här cykeln" utan
  "aldrig mätt": `Ewma.update(null, dt)` lämnar värdet som det var, så
  först när filtret aldrig fått ett tal blir svaret `designOutdoor`.
- **Prognosen går in i filtren.** `outdoor` är prognosens tal när givaren
  saknas, och det är det som matas i `state.outdoor.update(...)`. Bara
  `inputs.outdoor` står kvar som None, så att inget lär sig av en prognos
  som om den vore mätt.
- **`contributing and contributing < len(details)`:** flaggan
  `indoor_partial` bara när några men inte alla bidrar. Ingen alls är
  `degraded`, inte `indoor_partial`.
- **Filtren rörs inte när `fused` är None.** Svaret är `(null, null)`, men
  `state.indoor.value()` står kvar på det gamla. Testet
  aStaleValueIsNotReused kollar båda.
- **`light > learn_sunlight`** är strikt: exakt på tröskeln är ingen sol.
  Samma med `slow > room_target + learn_over_target`: exakt på marginalen
  är på målet.
- **Vakthundens klocka:** först `+= dt`, sedan
  `min(klockan, max(0, since_failsafe))` om Shellyn svarade. Ett svar
  under noll är noll, och ett svar större än klockan ändrar ingenting.
- **`degraded` i Pythons ordning:** `"flow"` före `"return"`. Testet
  jämför hela listan.
- **`_curve_outdoor` med `measured >= filtered`:** lika är inget fall,
  och `min(filtered - measured, outdoor_fall_limit)` är fallet, inte
  utetemperaturen.
- **`curve_at` håller, `_curve` håller inte:** `curveAt` går genom
  `tuning.clampFlow`, `curve` gör det inte. Över balansen ber kurvan om
  rumsmålet, och `clampFlow` lyfter det till `flowMin` i `curveAt`.
