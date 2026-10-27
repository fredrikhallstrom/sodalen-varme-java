# Steg 7: Husmodellen

> **Originalet:** [`thermal.py`](thermal.py) i den här mappen är Pythonkoden du
> översätter. Varje funktion där blir en metod här, oftast rad för rad.

Regulatorn svarar på "vilket tillopp vill huset ha nu". Det här svarar på en
annan fråga: **om värmen stannade, hur länge skulle huset hålla sig?** Det är
en modell av en byggnad, inte en regulator, och därför bor den för sig.

Fysiken, delad med husets värmekapacitet så att allt går att lära sig ur
temperaturer:

```
dT/dt =  q                      husets egen värme: människor, matlagning, teven
       + s · I                  solen genom fönstren, I = ljuset på väggarna i kW/m²
       + h · delta              radiatorerna, när de är på
       - (g0 + gv·v + gb·v·cos(dir) + gc·v·sin(dir)) · (T_inne - T_ute)
```

plus två termer för dygnet, cos och sin av klockan, så att modellen själv
hittar när hushållet värmer huset och hur mycket. Nio parametrar, linjära
allihop, så en `MultiRegression` från steg 6 lär sig hela huset, och samma
matris säger hur långt utanför sin data den gissar. Det avgör om en förutsägelse
är värd att handla på.

Tre anpassningar lever i `HouseModel`:

- **Driften** (`fit`, nio regressorer): K/h ur varje prov. Härifrån kommer
  `drift`, `driftUncertainty`, `reach` och `outlook`, alltså hur länge huset
  håller sig över golvet med värmen av. Det är vad den förutseende
  avstängningen står på.
- **Balansen** (`steady`, sju): samma fysik löst för skillnaden inne-ute,
  över hela dygn, så att hushållets dag tar ut sig. `balance` säger var
  värmekurvan börjar: utetemperaturen där huset håller sig självt.
- **Natten** (`night`, sex): ett prov per natt, för `nightBalance`. Värmen går
  mest på natten, och hushållet ger mindre då än dygnets medel.

Två saker modellen får veta i stället för att lära sig: väggförlusten och
radiatorerna har kända tecken och får golv, och de dras mot rimliga värden
(`housePriors`) tills datan kan skilja dem åt. Ett reglerat hus kör
radiatorerna hårdast precis när det svalnar, så datan säger lätt fel om
tecknet. `coolsWhenLeftAlone` och `lossOnFloor` är regulatorns två
förtroendefrågor: en anpassning som säger att ett kallt, mörkt och stilla hus
värmer sig självt har inte lärt sig byggnaden.

## Testerna väntar sig

Skalet ligger i `src/main/java/se/sodalen/heating/house/`. `Conditions`,
`Outlook`, `Forecast` och `Balance` är records utan logik och färdiga. I
`Thermal` står konstanterna med sina värden, och i `Thermal` och `HouseModel`
fyller du i metoderna och lägger till de fält du behöver.

```java
package se.sodalen.heating.house;

public record Conditions(double outdoor, Double wind, Double bearing, double sun, Double hour) {
    public Conditions(double outdoor)                                   // utan vind, sol och klocka
    public Conditions(double outdoor, Double wind, Double bearing)
    public Conditions(double outdoor, Double wind, Double bearing, double sun)
    public Conditions withHour(Double hour)                             // replace(conditions, hour=...)
}
public record Outlook(Double hours, boolean holds, Double uncertainty, boolean known, Double turnsAt)
public record Forecast(double hours, Conditions conditions)             // (timmar härifrån, vädret då)
public record Balance(double outdoor, double error)                     // (balance, dess medelfel)

public final class Thermal {                      // thermal.py utanför klassen HouseModel
    konstanterna REGRESSORS, LOSS, WIND, ..., DEFAULT_HORIZON_SECONDS, DEFAULT_PRIORS, STEP_HOURS

    public static double solarIndex(Double elevation, Double cloud)
    public static double[] regressors(double indoor, double outdoor, Double wind, Double bearing,
                                      double sun, double delta, Double hour)
    public static Map<Integer, Prior> housePriors(double designSpan, double designDelta, double sampleSeconds)
    public static double[] steadyRegressors(double sun, double delta, double slope, double[] wind)
    public static Double radiatorExcess(Double flow, Double ret, Double indoor)
    public static double[] nightRegressors(double heat, double slope, double[] wind)
    public static MultiRegression houseFit(double horizonSeconds)
    public static MultiRegression steadyFit(double horizonSeconds)
    public static MultiRegression nightFit(double horizonSeconds)
    public static Conditions weatherAt(List<Forecast> weather, double hours)
}

public final class HouseModel {
    public HouseModel()                           // de tre anpassningarna med DEFAULT_HORIZON_SECONDS
    public MultiRegression fit(), steady(), night()
    public double[] window(), dayTotals(), stretch()
    public List<double[]> day()
    public double sinceSteady(), secondsSinceSample(), observedHours()
    public Double lastIndoor()
    public void retune(double horizonSeconds)
    public void setDesign(double designSpan, double designDelta, double sampleSeconds)
    public boolean lossOnFloor()
    public boolean observe(Double indoor, Conditions conditions, double delta, double dt, double sampleSeconds)
    public boolean observe(Double indoor, Conditions conditions, double delta, double dt, double sampleSeconds,
                           Double radiators, Boolean night)
    public Double drift(double indoor, Conditions conditions)
    public Double driftUncertainty(double indoor, Conditions conditions)
    public Double reach(double indoor, Conditions conditions)
    public Balance balance(double indoor)
    public Balance nightBalance(double indoor)
    public Boolean coolsWhenLeftAlone()
    public Outlook outlook(double indoor, double floor, List<Forecast> weather, double confidence)
    public Outlook outlook(double indoor, double floor, List<Forecast> weather, double confidence, double stepHours)
}
```

Där Python och Java skiljer sig:

- **`retune` sätter horisonten på de tre anpassningarna**, `self.fit.horizon =
  …`. Din `MultiRegression` från steg 6 har bara `horizon()`. Lägg till
  `public void setHorizon(double seconds)` där, och ta bort `final` på fältet.
  Det är den enda ändringen i steg 6.
- Funktionerna som står utanför klassen i Python (`solar_index`,
  `regressors`, `house_priors`, `radiator_excess`, de tre `*_fit` och
  `_weather_at`) blir `static` i `Thermal`, och modulens konstanter likaså.
  `_DEFAULT_PRIORS` heter `DEFAULT_PRIORS`, `21.0 * 24.0 * 3600.0` i
  dataclassens standardvärden heter `DEFAULT_HORIZON_SECONDS`, och `outlook`s
  `step_hours=0.25` är `STEP_HOURS` och en extra överlagring.
- `None` blir `null`: `Double` för `wind`, `bearing`, `hour`, `indoor` i
  `observe`, `radiators`, och `Boolean` för `night`. `observe` med fem
  argument är samma sak med `null, null` sist.
- Tuplerna blir records: `(balance, error)` är `Balance`, `(hours,
  conditions)` i väderlistan är `Forecast`, och `Outlook` är en record i stället
  för en dataclass. `dataclasses.replace(conditions, hour=…)` är
  `conditions.withHour(…)`.
- `wind` till `steady_regressors` och `night_regressors` är en tupel med tre
  tal i Python och en `double[]` med tre tal här.
- `from .sunlight import SUNLIGHT_PER_INDEX`: hela `sunlight.py` kommer som
  ett eget steg längre fram. Här behövs bara talet, och det står som konstant
  i `Thermal`.
- `as_dict`, `from_dict`, `_sun_in_walls`, `_with_squares`, `SUN_UNIT` och
  `SUN_UNITS_BEFORE` är sparandet och hör till steg 11, som i steg 6. Också
  `house_fit(horizon, fit)` med en befintlig anpassning kommer då.

## Ordning

1. `ThermalTest`: `TheSolarIndex`, `TheRadiatorsHeat`, `TheRows`,
   `ThePhysicsItIsTold`, `TheWeatherAhead`, `TheConditions`, `TheFits`. Allt
   det här är korta funktioner utan tillstånd.
2. `HouseModelTest.Sampling`: konstruktorn, `observe` utan att något lärs
   (provintervallet, luckan i avläsningarna).
3. `HouseModelTest.LearningAHouse`: `observe` hela vägen, `drift`. Testerna
   lär modellen ett simulerat hus (`WeatherHouse` och `Teaching` i testmappen,
   översatta från Pythontesterna) och frågar sedan.
4. `HouseModelTest.ConfidenceIsLocal`: `driftUncertainty` och `reach`.
5. `HouseModelTest.HowLongItHolds`: `outlook`.
6. `HouseModelTest.TheHouseholdsOwnDay` och `ASetbackWalksThroughTheClock`:
   dygnstermerna och klockan som går i `outlook`.
7. `HouseModelTest.TheBalance` och `TheBalanceOfAHouseInService`: dygnets
   summor och `balance`.
8. `HouseModelTest.TheNight`: natten som pågår och `nightBalance`.
9. `HouseModelTest.WhatItIsTold`: `retune`, `setDesign`, `lossOnFloor`,
   `coolsWhenLeftAlone`.
10. `SameAsPython` i båda: 600 funktionsanrop och 220 följder av cykler på
    en ny modell, uträknade av Pythonversionen.

## Klurigt

- **`[0.0] * 7`** är `new double[7]`, och `self.stretch = [0.0] * 6 +
  [indoor]` är en ny `double[7]` med `indoor` på plats 6.
- **`self.day.append(sums + [start])`** lägger en *kopia* av de sju summorna
  plus starttemperaturen, åtta tal, sist i listan. Sedan `self.window = [0.0]
  * 7` ger intervallet en ny tom lista; i Java kan du antingen skapa en ny
  array eller nollställa den gamla, men dygnets kopia måste vara en egen array
  (`Arrays.copyOf(window, 8)`).
- **`self.day.pop(0)`** tar bort det *första*, äldsta, intervallet:
  `day.remove(0)` på en `ArrayList`, eller en `ArrayDeque`. `self.day[0][7]`
  är starttemperaturen i det äldsta intervallet som är kvar.
- **`for index, value in enumerate((1.0, heat, …))`** är en loop över sju tal
  där `index` räknas upp. Enklast: lägg talen i en lokal `double[]` och
  loopa.
- **`float(wind or 0.0)`** är `wind == null ? 0.0 : wind`. **`bool(night)`**
  är `night != null && night`: okänt är inte natt.
- **Pythons `%` är aldrig negativt.** `(weather[0][1].hour - weather[0][0])
  % 24.0` kan ha ett negativt tal till vänster, och då ger Javas `%` ett
  negativt svar. Skriv `((x % 24.0) + 24.0) % 24.0`.
- **`math.radians(x)`** är `Math.toRadians(x)`, med samma konstant och samma
  svar. **`x ** 1.3`** är `Math.pow(x, 1.3)`, `math.isfinite(s)` är
  `Double.isFinite(s)` och `float("inf")` är `Double.POSITIVE_INFINITY`.
- **`-speed * math.cos(angle) * difference`** räknas från vänster:
  `(-speed * cos) * difference`. Skriv det i samma ordning, annars skiljer
  sig sista decimalen från facit.
- **`samples * WIND_SPREAD ** 2`:** `**` binder hårdare än `*`, så det är
  `samples * (WIND_SPREAD * WIND_SPREAD)` eller `samples * Math.pow(WIND_SPREAD, 2)`.
  `samples * WIND_SPREAD * WIND_SPREAD` multipliceras från vänster och kan ge
  en annan sista decimal.
- **Ordningen i `observe`:** `seconds_since_sample` räknas upp först. Luckan
  (`indoor` eller `conditions` är `null`) nollställer `lastIndoor`,
  `secondsSinceSample`, `window`, `day`, `dayTotals` och `stretch` och svarar
  `false`. Intervallets summor och natten som pågår fylls på *varje* cykel,
  före kollen mot `sampleSeconds`. Dygnet och `observedHours` får bara de
  cykler där ett prov togs.
- **`_stretch` och `_add_to_day`** är två privata hjälpmetoder som du skriver
  själv, liksom `_row`. Behåll uppdelningen, det är lättare att hitta fel.
- **`range(0, 360, 45)`** är `for (int bearing = 0; bearing < 360; bearing +=
  45)`. **`break`** i `cools_when_left_alone` lämnar bara den inre loopen:
  utan vind är alla riktningar samma, så en räcker.
- **`turns_at`** är `0.0`, inte `null`, när vädret redan är fint vid första
  steget. `None` är bara när huset aldrig slutar svalna före golvet.
- **`weather[-1][0]`** är sista luckans timmar: `weather.get(weather.size() -
  1).hours()`.
- **`balance` kör `configure` på balansens anpassning varje gång**, också när
  driften inte svarar. Det är det som gör att solprioriteten följer med när
  driften lär sig mer.
- **`elapsed += step_hours`** i `outlook` är flyttal som läggs ihop, och
  facit är uträknat precis så. Byt inte till `i * step_hours`.
- **Facit:** `thermal.csv` har ett anrop per rad, `kind;args;expected`, och
  `house_model.csv` följder av anrop på en ny `HouseModel`:
  `observe,inne,ute,vind,riktning,sol,timme,delta,dt,intervall,radiatorer,natt
  |drift,…|outlook,…` och så vidare, med svaren i samma ordning och modellens
  tillstånd efteråt. Felet säger vilket anrop som skiljer, till exempel
  `ops[17] drift`.
