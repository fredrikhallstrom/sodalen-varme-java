# Steg 8: Prognosen

> **Originalet:** [`forecast.py`](forecast.py) i den här mappen är Pythonkoden du
> översätter. Varje funktion där blir en metod här, oftast rad för rad.

Huset får väder från flera håll: SMHI, Yr och en till. Alla har fel på sitt
eget sätt. En ligger två grader för lågt för att dess rutnätspunkt sitter
trehundra meter upp på en kulle, och två av dem visar sig vara samma
väderdatormodell såld av olika bolag. Att ta medelvärdet behandlar allt det
som brus att dela med tre, och det är det inte.

Så varje källa betygsätts mot det enda i huset som inte är en prognos, givaren
på väggen, och betygen sparas som en **kovarians** i stället för tre
felstaplar var för sig. Det är hela modulen:

- **Offset dras av.** En källa som ligger två grader lågt är inte dålig, den
  har en förskjutning, och när den är känd är källan lika användbar som någon
  annan.
- **Källor som följs åt får inte rösta två gånger.** Den bästa kombinationen
  av korrelerade skattningar är `w = S⁻¹·1 / (1ᵀ·S⁻¹·1)`. Den ger två
  identiska källor en halv röst var utan att någon talat om att de är
  identiska.
- **Samma lösning ger osäkerheten.** `1 / (1ᵀ·S⁻¹·1)` är kombinationens
  varians, och det är den som den förutseende avstängningen behöver för att
  veta hur stor marginal den ska lämna.

`Ensemble` gör allt det: `observe` betygsätter, `weights` löser ut vikterna
och `combine` ger ett väder av många. `Forecasts.outdoorFromForecast` är en
liten sak bredvid: prognosens temperatur för nu, som får ersätta en utegivare
som tappat kontakten.

## Testerna väntar sig

Skalet ligger i `src/main/java/se/sodalen/heating/forecast/`. `Reading`,
`Combined` och `Weighting` är records utan logik och färdiga. I `Ensemble`
och `Forecasts` står konstanterna med sina värden, och du fyller i metoderna
och lägger till de fält du behöver.

```java
package se.sodalen.heating.forecast;

public record Reading(String name, Double temperature, Double wind, Double bearing, Double cloud) {
    public Reading(String name)                         // allt annat null
    public Reading(String name, Double temperature)     // vind, riktning och moln null
}
public record Combined(Double temperature, Double wind, Double bearing, Double cloud, Double uncertainty,
                       Map<String, Double> weights, int sources) {
    public Combined()                                   // Pythons Combined(): tomt
}
public record Weighting(Map<String, Double> weights, Double spread)   // Pythons (dict, float | None)

public final class Ensemble {
    konstanterna PRIOR_VARIANCE, DEFAULT_HORIZON_SECONDS, DIAGONAL_FLOOR

    public Ensemble()                                   // DEFAULT_HORIZON_SECONDS
    public Ensemble(double horizonSeconds)
    public double horizon(), weight()
    public Map<String, Double> bias()
    public Map<String, Map<String, Double>> covariance()
    public void retune(double horizonSeconds)
    public boolean observe(Double truth, List<Reading> readings, double dt)
    public Weighting weights(List<String> names)
    public Combined combine(List<Reading> readings)
}

public final class Forecasts {                          // forecast.py utanför klassen Ensemble
    public static final double STAND_IN_HOURS = 1.5;
    public static Double outdoorFromForecast(List<Forecast> forecast)
}
```

Där Python och Java skiljer sig:

- **Alla `dict` är `LinkedHashMap`**: `bias`, varje rad i `covariance` och
  kartan över raderna, felen i en `observe`, och vikterna. Ordningen ändrar
  inget tal, men det är den Python håller och den instrumentpanelen visar,
  och testerna läser nycklarna i ordning. `bias()` och `covariance()` lämnar
  ut kartorna själva, inte kopior.
- **Tupeln `(dict, float | None)`** som `weights` svarar med är recorden
  `Weighting`.
- **`_weighted` och `_weighted_bearing`** står utanför klassen i Python och
  blir privata `static`-metoder i `Ensemble`. `outdoor_from_forecast` och
  `STAND_IN_HOURS` hamnar i `Forecasts`, eftersom namnet `Forecast` redan är
  steg 7:s record för en lucka i prognosen.
- **`solve_symmetric`** är din `MultiRegression.solveSymmetric` från steg 6.
  Den svarar `null` när matrisen är singulär, precis som Pythons `None`.
- **Prognoslistan** som `outdoor_from_forecast` läser är steg 7:s
  `List<Forecast>`, och temperaturen är `conditions().outdoor()`.
- **`None` blir `null`:** `Double` överallt i `Reading` och `Combined` utom
  `sources`, och `truth` i `observe`.
- **`as_dict` och `from_dict`** är sparandet och hör till steg 11, som i steg
  6 och 7. Pythontesterna i `TestKeepingIt` kommer då.

## Ordning

1. `EnsembleTest.TheRecords` och `TheStandIn`: records utan logik och
   `Forecasts.outdoorFromForecast`.
2. `EnsembleTest.Scoring`: konstruktorn, `retune`, `observe` och vad den gör
   med `weight`, `bias` och `covariance`.
3. `EnsembleTest.TheWeighting`: matrisen och `weights`.
4. `EnsembleTest.TheBias` och `CorrelatedSources`: hela vägen från många
   cykler till vikter. Testerna matar ensemblen med ett simulerat väder
   (`Feeding` i testmappen, översatt från Pythontesterna).
5. `EnsembleTest.Combining`: `combine` med vind, riktning och moln.
6. `SameAsPython`: 250 följder av anrop på en ny `Ensemble`, uträknade av
   Pythonversionen.

## Klurigt

- **`first == second`** på namn är `first.equals(second)`. `==` på två
  `String` jämför referenser och är ibland sant, ibland inte.
- **`self.bias.get(name, error)`** är `bias.getOrDefault(name, error)`:
  första felet blir startvärdet, inte 0. Det syns först när en källa kommer
  till efter några cykler. **`setdefault(first, {})`** är
  `covariance.computeIfAbsent(first, k -> new LinkedHashMap<>())`.
- **`self.covariance.get(first, {}).get(second)`** är `null` både när raden
  saknas och när kolumnen saknas. Hämta raden först och kolla den.
- **`2.718281828459045 ** -ratio`** är `Math.pow(Math.E, -ratio)`, som i
  steg 6. **`variance ** 0.5`** är `Math.sqrt(variance)`.
- **`round(value, 4)`** avrundar till fyra decimaler med jämn avrundning på
  det exakta talet: `new BigDecimal(value).setScale(4,
  RoundingMode.HALF_EVEN).doubleValue()`. `Math.round(value * 1e4) / 1e4` ger
  samma svar utom precis på en halva, och facit skiljer dem inte åt.
- **`math.degrees(math.atan2(east, north)) % 360.0`:** Pythons `%` ger
  alltid ett svar med divisorns tecken, Javas ger ett med täljarens. En
  vindriktning strax väster om norr blir `-1e-15` i Java och `360.0` i
  Python. Skriv Pythons `%` själv:

  ```java
  double remainder = degrees % 360.0;
  return remainder < 0.0 ? remainder + 360.0 : remainder;
  ```

  Testerna jämför riktningar på cirkeln, så `0.0` och `360.0` räknas lika,
  men svaret måste ligga mellan 0 och 360: en västlig vind är 270, inte
  `-90`.
- **`sum(solved)`** och **`zip(names, solved)`** är vanliga loopar. **`any(v
  < 0.0 for v in …)`** är en loop som letar efter ett negativt tal.
- **`if not names`** och **`if not forecast`** är sanna både för `null` och
  för en tom lista.
- **`offered`** är avläsningarna med en temperatur. `names`, vikterna och
  temperaturen räknas över dem, men `_weighted` och `_weighted_bearing` loopar
  över **alla** avläsningar: en utan temperatur har vikt 0 och hoppas över,
  så svaret blir detsamma, men gör som Python.
- **`weights.get(reading.name, 0.0)`** är `getOrDefault(name, 0.0)`. En källa
  som frågas efter men aldrig betygsatts får `PRIOR_VARIANCE` på diagonalen
  och 0 utanför, inte ett `NullPointerException`.
- **`getattr(conditions, "outdoor", None)`** är `conditions == null ? null :
  conditions.outdoor()`.
- **`len(names) == 1`** har en egen väg: vikten 1,0 och spridningen är roten
  ur matrisens enda tal. Inget löses.
- **Facit:** `ensemble.csv` har följder av anrop på en ny `Ensemble`:
  `observe,sanning,dt,källa:temp:vind:riktning:moln/källa:…|weights,a/b/c
  |combine,…|retune,…|standin,timmar:ute/…` med svaren i samma ordning och
  `weight`, `bias` och `covariance` efteråt. Felet säger vilket anrop som
  skiljer, till exempel `ops[17] combine wind`.
