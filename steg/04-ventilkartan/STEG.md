# Steg 4: Ventilkartan

> **Originalet:** [`valvemap.py`](valvemap.py) i den här mappen är Pythonkoden du
> översätter. Varje funktion där blir en metod här, oftast rad för rad.

Shuntventilen ställer inte in en temperatur. Den blandar pannvatten med
returvatten, och läget avgör hur stor **andel** pannvatten som kommer med:

```
tillopp = retur + andel(läge) * (panna - retur)
```

Samma 20 % ger alltså varmare tillopp när pannan är 84 grader än när den är
60, och pelletsbrännaren pendlar mellan dem varje halvtimme. Kartan lär sig
andelen för varje läge, så att regulatorn kan fråga "vilket läge ger 40
grader med den här returen och den här pannan?" Svaret används när värmen
startar efter ett stopp, och för att veta hur hårt PID:en ska ta i där
ventilen står.

- **Band:** andelen lärs per 5 % av vägen, 21 band från 0 till 100 %. En
  ventil är långt ifrån rak: här släpper de första 10-20 % igenom nästan
  inget pannvatten.
- **Bara stilla avläsningar räknas:** ventilen ska ha stått still i fem
  minuter, så att motorn är framme och vattnet har hunnit runt till givaren.
- **Glömskan:** en avläsning räknas i ungefär en månad. Ett nytt värde vägs in
  efter sin tid mot det gamla.
- **Det kartan inte sett** fylls med en linje från det sista band den litar på
  till 100 % pannvatten vid helt öppen (`fillPrior`).
- **Skuggpoängen:** hur långt kartans val låg från där PID:en faktiskt
  landade, i snitt över tid.

Det här är första steget med **tillstånd**: kartan minns mellan anropen. Därför
är det en vanlig klass med fält och konstruktor, inte bara `static`-metoder.

## Testerna väntar sig

```java
package se.sodalen.heating.valve;

public record ValvePoint(double position, double share)

public final class ValveMap {
    public static final double BAND     // 5.0
    public static final int BANDS       // 21: 0, 5, ... 100 %

    public ValveMap()                   // 30 dygn, 300 s stilla, 15 K lyft, 1800 s per band
    public ValveMap(double horizonSeconds, double settleSeconds, double minLift, double minBandSeconds)

    public Double share(int band)       // null: inget lärt där än
    public double weight(int band)      // sekunder data, med glömskan
    public Double shadowError()         // null: inget att jämföra med än
    public double shadowSeconds()

    public boolean settled()
    public Double observe(Double position, Double flow, Double ret, Double boiler, double dt)
    public void fillPrior()
    public List<ValvePoint> known()
    public Double positionFor(Double flow, Double ret, Double boiler)
    public Double gainAt(Double position, Double lift)
    public void score(Double predicted, Double actual, double dt)
}
```

`as_dict`, `from_dict` och `summary` i Python, alltså att spara och visa
kartan, kommer i steg 11 tillsammans med resten av det lärda tillståndet.

## Ordning

1. `TheBands`: vilket band en avläsning hamnar i
2. `Learning`: `observe` och `settled`
3. `Prediction`: `known` och `positionFor`
4. `TheGainItGives`: `gainAt`
5. `WhatItShouldBe`: `fillPrior`
6. `ShadowScore`: `score`
7. `SameAsPython`: 1600 serier av avläsningar

## Klurigt

- **Pythons `round()`** avrundar en halva till jämnt tal: 12,5 % blir band 2
  och 22,5 % band 4. `Math.rint` gör likadant, men `Math.round` ger 3 och 5.
- **Understreck = `private`:** `_last_position` och `_still_seconds` är
  kartans eget minne mellan anropen. De blir `private` fält, inte lokala
  variabler.
- **`Double[]` mot `double[]`:** `new Double[21]` är fullt av `null` (inget
  lärt), `new double[21]` fullt av `0.0`.
- **`None in (flow, ret, boiler)`** är `flow == null || ret == null || boiler == null`.
- **`-math.inf`** är `Double.NEGATIVE_INFINITY`.
- **`zip(points, points[1:])`** går igenom grannpar: `get(i)` och `get(i + 1)`
  för `i` från 0 till näst sista.
- **`max([...])` över en lista med villkor:** en loop som sparar den största.
  Hittas ingen alls blir svaret `null`.
- **En krypande ventil:** det sista läget sparas bara när ventilen har flyttat
  sig mer än en halv procent, inte varje varv. Två steg på 0,4 är en flytt,
  eftersom 0,8 räknas från där den stod.
- **En lucka är ingen flytt:** saknas tillopp, retur eller panna en gång räknas
  väntan vidare. Saknas läget, eller är `dt` noll, börjar den om.
- **Pythonfallen är serier:** varje rad i facit är en följd av avläsningar
  som testet spelar upp mot din karta. Felet säger vilket band eller vilken
  fråga som skiljer, till exempel `share[7]` eller `positionFor[3]`.
