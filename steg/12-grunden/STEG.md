# Steg 12: Regulatorns grund

> **Originalet:** [`controller.py`](controller.py) och [`texts.py`](texts.py)
> i den här mappen. Det här steget tar början av `controller.py`, alltså
> modulens hjälpare, konstanterna, `fuse_indoor` och konstruktorn i
> `HeatingController` med `apply_tuning`, och tre saker ur `texts.py`.

Regulatorn är den yttre slingan. Den gör väder och inomhustemperatur till
ett börvärde för tilloppet, och den inre slingan (steg 2) gör felet i det
till ett ventilläge. Nästan hela `controller.py` är klassen
`HeatingController`, och dess `step()` är en cykel som anropar en metod per
fas. Regulatorn kommer i nio steg i den ordning `step()` kör dem:

| Steg | Del |
|---|---|
| 12 | Grunden: givarfusionen, hjälparna, texterna, konstruktorn och `apply_tuning` |
| 13 | Ute, inne, klockorna och kurvan |
| 14 | Deltat och radiatorvakten |
| 15 | Läget och värmekällan |
| 16 | Utrullningen (`_coast`) |
| 17 | Inre loopen och ventilkommandot |
| 18 | Inlärningen och dess grindar |
| 19 | Tabellen och förstärkningen |
| 20 | Hela cykeln: `step()`, sluten slinga och persistens |

Varje stegs tester skrivs när du närmar dig det. Allt ligger i paketet
`house` (ditt val), bredvid `Model`, `LearnedState` och husmodellen.

Fyra delar i det här steget:

- **`Texts`:** skälet för varje läge, nödkörningens namn och statusrad, och
  vad som sägs medan en sotning håller brännaren av. Konstanterna står
  färdiga i skalet, och du skriver `emergencyReason`.
- **Hjälparna**, som de senare stegen lutar sig mot: `deadbanded` (felet
  som PID:en ser det), `clamp`, `toward`, `risingSlowly` (ventilens
  förstärkning, mjukare med en gång och djärvare bara med tiden) och
  `sunlightNow` (solen på väggarna, eller den gamla gissningen ur solhöjd
  och moln).
- **`fuseIndoor`:** tre inomhusgivare till det enda tal den yttre slingan
  reglerar på. Varje avläsning får sin kalibrering, och när tilloppet går
  över rumsmålet dras en skiktningsterm av. Solrummet hålls ovanifrån mot
  resten av huset, aldrig underifrån, för ett rum som verkligen är kallt
  är det viktigaste en givare kan säga.
- **`HeatingController`:** fälten, konstruktorn, `applyTuning`,
  `retuneLearners` och `burnerLeadSeconds`. En ny tuning tas in utan att
  det lärda försvinner, utom lutningens trim när installatören vrider på
  kurvan. Den som vrider på kurvan ska få den kurvan, som på EVR-reglern.

## Testerna väntar sig

Skalet ligger i `src/main/java/se/sodalen/heating/house/`:
`HeatingController`, `Texts` och `IndoorFusion`, alla nya filer.
Konstanterna, fälten och recorden står färdiga, och metoderna är tomma.

```java
package se.sodalen.heating.house;

public record IndoorFusion(Double temperature, List<FusedSensor> details)   // Pythons tupel (fused, details)

public final class Texts {
    public static final Map<String, String> MODE_REASONS, EMERGENCY_NAMES;      // färdiga i skalet
    public static final String SWEEP_REASON;                                     // färdig
    public static String emergencyReason(String kind, double position, double flow)
}

public class HeatingController {
    public static final Map<String, String> COLD_CLOCKS, COLD_WHY;               // färdiga
    public static final String SUN_RULE_LIGHT, SUN_RULE_TERM, SUN_RULE_NONE;     // färdiga
    public static final double STARVED_RELEASE = 0.8;

    public Tuning tuning;
    public LearnedState state;
    public HouseModel house;
    public Ensemble ensemble;
    public ValveMap valveMap;
    public PointTable points;
    public CurveFit tableFit = null;
    public Balance houseBalance = null, houseNightBalance = null;
    double sinceFit = Double.POSITIVE_INFINITY;   // Pythons _since_fit
    boolean starved = false;                       // Pythons _starved
    double burnerLead = 1800.0;                    // Pythons _burner_lead

    public HeatingController(Tuning tuning)
    public HeatingController(Tuning tuning, LearnedState state, HouseModel house, Ensemble ensemble,
                             ValveMap valveMap, PointTable points)        // null ger ett nytt standardobjekt
    public void applyTuning(Tuning tuning)
    void retuneLearners()                                                  // paketprivat, som Pythons _
    public double burnerLeadSeconds()
    public double burnerLeadSeconds(Double seconds)                        // null sätter inget

    static double deadbanded(double error, double deadband)
    public static double sunlightNow(ControlInputs inputs)
    public static IndoorFusion fuseIndoor(List<SensorReading> readings, Tuning tuning, Double flow)
    static double toward(double value, double goal, double step)
    static double risingSlowly(Double last, double wanted, double dt, double seconds)
    static double clamp(double value, double low, double high)
}
```

Där Python och Java skiljer sig:

- **Pythons `_namn` är paketprivata** i Java, som `Model.balanceFromOffset`
  i steg 11. Testerna ligger i samma paket och når dem.
- **Modulens fria funktioner** (`fuse_indoor`, `sunlight_now`, `_clamp` och
  de andra) är statiska metoder i `HeatingController`.
- **Pythons valfria argument** blir överlagringar:
  `HeatingController(tuning)` är alla fem andra None, och
  `burner_lead_seconds()` och `burner_lead_seconds(seconds)` är två metoder.
- **`state or LearnedState(process_gain=tuning.gain_reference)`** är en
  koll av None här, för ingen av klasserna har `__len__` eller `__bool__`.
  En ny `LearnedState` får `processGain` satt till `tuning.gainReference`
  efteråt, för Javas `LearnedState` har bara den tomma konstruktorn.
- **`getattr(self, "_burner_lead", 1800.0)`** är fältet `burnerLead`, som
  börjar på 1800.

## Ordning

1. `Texts.emergencyReason`: att värma upp på, och avrundningen i Klurigt.
2. `clamp`, `toward`, `deadbanded` och `risingSlowly`
   (`HeatingControllerTest.TheClamp`, `Toward`, `TheDeadband`,
   `RisingSlowly`).
3. `sunlightNow` (`TheSun`).
4. `fuseIndoor` (`IndoorFusionTest.TheFusion`, `TheCalibration`,
   `FromTheCode`).
5. Konstruktorerna och `retuneLearners` (`TheController`).
6. `applyTuning` (`ApplyingTuning`) och `burnerLeadSeconds`
   (`TheBurnersLead`).
7. `SameAsPython` i alla tre: fyra facitfiler, drygt 3600 fall.

## Klurigt

- **`f"{x:.0f}"` avrundar hälften till jämnt** på det exakta talet, som
  Pythons `round`: 50,5 blir 50 och 51,5 blir 52. Javas `%.0f` avrundar
  hälften uppåt och ger 51. `Math.rint` före formateringen gör som Python,
  som `round` i steg 9. Ta `Locale.ROOT` i `String.format`, så att ingen
  standardlocale lägger sig i.
- **`kind == EMERGENCY_VALVE` tål None** i Python. I Java är `kind.equals(...)`
  en `NullPointerException` när `kind` är null. Vänd på den, så att
  konstanten frågar: `Model.EMERGENCY_VALVE.equals(kind)`.
- **`FusedSensor` är en record**, och Python ändrar `item.clamped = ...` på
  objektet i listan. I Java gör du en ny `FusedSensor` med `clamped` satt
  och lägger den på samma plats med `details.set(i, ...)`. Håll alltså
  reda på var i `details` varje bidragande givare står, till exempel med
  index i stället för objekten.
- **`others or contributing`** är "de andra om det finns några, annars alla
  som bidrar". Det motsvarar `others.isEmpty() ? contributing : others`.
- **`if reading.stratification and flow is not None`:** ett flyttal är sant
  i Python när det inte är 0. I Java blir det
  `reading.stratification() != 0.0 && flow != null`.
- **`_clamp` är inte `Math.clamp`.** `Math.clamp` kastar
  `IllegalArgumentException` när `low > high`, medan Pythons
  `min(high, max(low, value))` ger `high`. Samma fälla som `clampFlow` i
  steg 11. Den syns i `toward` med ett negativt steg.
- **`float("inf")`** är `Double.POSITIVE_INFINITY`.
- **`self.house.set_design(...)`** är `house.setDesign(tuning.designSpan(),
  tuning.deltaDesign, tuning.thermalSampleSeconds)`. `design_span` är en
  `@property` i Python och en metod i Java.
