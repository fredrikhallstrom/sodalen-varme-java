# Steg 11: Modellen

> **Originalet:** [`model.py`](model.py), [`parsing.py`](parsing.py) och
> [`sunterm.py`](sunterm.py) i den här mappen är Pythonkoden du översätter,
> plus `as_dict`/`from_dict` i `estimators.py` (den ligger i steg 6). Varje
> funktion där blir en metod här, oftast rad för rad.

Det här är datamodellen regleringen står på, fri från Home Assistant: vad
en mätning är, vad en installatör kan vrida på, vad regleringen lärt sig om
just det här huset, och hur allt det *sparas och läses tillbaka*. Home
Assistant-sidan gör inget mer än att fylla i en `ControlInputs`, ta en
`ControlOutput` ut och spara `LearnedState` till disk.

Det enda stället med riktig räkning är läsningen från disk, och den är det
viktigaste i steget. En sparad fil är den enda indata regleringen inte
rår över: en nyckel kan saknas, vara `null`, en text, eller den form en
äldre version skrev. Varenda sådan ska komma tillbaka som ett användbart
standardvärde i stället för ett undantag, för **en reglering som inte
startar är värre än en som glömt vad den lärt sig.** Därför går allt genom
`parsing.py`: `as_float` (ett tal eller reserven), `as_optional_float`
(`None` förblir `None`, skräp blir `0.0`) och `as_mapping` (allt som inte är
en dict är en tom dict).

Fyra delar:

- **`Parsing`** (`helper`): de tre läsarna ovan, `plausible_temperature`
  (där en trasig givare slutar vara en temperatur: -273 från en bruten
  kabel, -999 från Unis markör, och DS18B20-proberna som får gå till 125)
  och `outdoor_in_use` (den kallaste utegivaren som svarar, för solen kan
  bara få en givare att läsa varmt).
- **Persistensen i `estimate`:** `asDict`/`fromDict` på `Ewma`, `DutyWatch`,
  `Regression` och `MultiRegression`, och `setSeconds`/`setHorizon`.
  Tidskonstanten och horisonten kommer *aldrig* från filen utan från
  tuningen som läser tillbaka den. En `MultiRegression` av fel storlek
  svarar på en annan fråga och kastas.
- **`SunTerm`** (`house`): solens del av inomhusfelet, lärd på timmedel.
  Kurvan lär sig av felet minus den här termen där den är känd nog, i
  solen också (Fredrik 2026-10-18). Två dygn av timmar innan den svarar,
  och sex av dem soliga.
- **`Model` och klasserna kring den** (`house`): konstanterna, `Tuning` med
  sina 140 fält, `LearnedState` med `forget`, `retune`, `asDict` och
  `fromDict` med tre migreringar från äldre filer, `tuningFromDict`, och
  de två paketen `ControlInputs`/`ControlOutput`.

## Testerna väntar sig

Skalet ligger i `src/main/java/se/sodalen/heating/helper/Parsing.java` och i
`src/main/java/se/sodalen/heating/house/`: `SunTerm`, `Model`,
`SensorReading`, `FusedSensor`, `Tuning`, `LearnedState`, `ControlInputs`,
`ControlOutput` och `LearnerStatus`, alla nya filer. Konstanterna och fälten
står färdiga med Pythons värden; metoderna är tomma.

**Befintliga klasser i `estimate` rör skalet inte.** Där lägger du själv
till det här, och det måste finnas *innan stegets tester ens kompilerar*:

```java
// Ewma: du har redan asDict() och fromDict(Map<String, ?>, double); se Klurigt om dem.
public final class DutyWatch {
    public Map<String, Object> asDict()                                   // {"seconds", "share", "weight"}
    public static DutyWatch fromDict(Map<String, ?> data, double seconds)  // share via asOptionalFloat, weight via asFloat(…, 0.0)
    public void setSeconds(double seconds)
}
public final class Regression {
    public Map<String, Object> asDict()                                   // {"horizon", "weight", "sum_x", "sum_y", "sum_xx", "sum_xy"}
    public static Regression fromDict(Map<String, ?> data, double horizon) // fem summor via asFloat(…, 0.0); "horizon" i filen läses inte
    public void setHorizon(double horizon)
}
public final class MultiRegression {
    public Map<String, Object> asDict()      // {"size" (int), "horizon", "weight", "xx" (List<List<Double>>, kopia), "xy" (List<Double>, kopia),
                                             //  "residual", "residual_data", "yy" (Double, kan vara null)}; ridge, priors och golv sparas inte
    public static MultiRegression fromDict(Map<String, ?> data, int size, double horizon)
                                             // fel size, eller xx/xy som inte är listor av tal med rätt form → new MultiRegression(size, horizon)
    public void setHorizon(double seconds)
}
```

Och recordsens korta konstruktorer (Pythons standardvärden) skriver du
också innan det kompilerar; de står som kommentarer i skalet:

```java
public record SensorReading(String key, Double temperature, String role, double weight, double offset,
                            double stratification, Double maxAge, boolean active, boolean featured) {
    public SensorReading(String key, Double temperature)                        // ROLE_ROOM, 1.0, 0.0, 0.0, null, true, false
    public SensorReading(String key, Double temperature, String role, double weight)
    public SensorReading(String key, Double temperature, String role, double weight, double offset, double stratification)
    public boolean available()                                                  // temperature != null
}
public record FusedSensor(String key, String role, Double raw, Double corrected, double weight, boolean clamped,
                          double offset, boolean active, boolean featured) {
    public FusedSensor(String key, String role, Double raw, Double corrected, double weight)   // false, 0.0, true, false
    public boolean contributed()            // active && corrected != null && weight > 0
    public Map<String, Object> asDict()     // nio nycklar i Pythons ordning
}
public record LearnerStatus(boolean learning, String why, Double number)      // Pythons tupel i ControlOutput.learning
```

Resten:

```java
package se.sodalen.heating.helper;

public final class Parsing {
    public static final double PLAUSIBLE_LOW = -50.0, PLAUSIBLE_HIGH = 110.0, WIRED_MAX_C = 125.0;
    public static Map<String, ?> asMapping(Object value)          // en Map → den själva; annat och null → ny tom LinkedHashMap
    public static double asFloat(Object value, double fallback)   // Pythons float(): Number, Boolean (1/0), String; annars fallback, NaN och ±inf med
    public static Double asOptionalFloat(Object value)            // null → null; annars asFloat(value, 0.0)
    public static Double plausibleTemperature(Object raw)         // high = PLAUSIBLE_HIGH
    public static Double plausibleTemperature(Object raw, double high)   // float(raw) misslyckas, utanför [LOW, high] eller NaN → null
    public static Integer outdoorInUse(List<Map.Entry<String, Double>> readings)   // kallaste icke-null; lika → lägst index; ingen → null
}
```

```java
package se.sodalen.heating.house;

public final class SunTerm {
    public static final int SIZE = 4, LIGHT = 2, LIGHT_DEMAND = 3, MIN_HOURS = 48, MIN_SUNNY_HOURS = 6;
    public static final double HOUR_SECONDS = 3600.0, HORIZON_SECONDS = 21.0 * 24.0 * 3600.0, SUNNY = 0.02;
    public MultiRegression fit;        // new MultiRegression(SIZE, HORIZON_SECONDS)
    public double[] hour;              // {0, 0, 0, 0}: sekunder, och sekunder gånger demand, light och error
    public int hours, sunnyHours;
    public SunTerm()
    public void observe(Double demand, Double light, Double error, Double dt)
    public Double perKw(double demand)
    public Double spread(double demand)
    public Double share(double demand)
    public boolean known(double demand, double light, double limit)
    public double correction(double demand, double light)
    public Map<String, Object> asDict()                          // {"fit", "hour" (kopia som List<Double>), "hours", "sunny_hours"}
    public static SunTerm fromDict(Map<String, ?> data, double horizon)
}

public final class Model {
    konstanterna ROLE_*, ROLES, ROLE_DEFAULTS, MODE_*, LEARN_*, LEARNERS, EMERGENCY_*, EMERGENCY_KINDS, MODES_STOPPED,
                 TABLE_*, DELTA_QUIET_*, COLD_CAUSES, OFFSET_ERA_CURVATURE, OFFSET_ERA_OUTDOOR   (färdiga i skalet)
    public static double effectiveCurvature(Tuning tuning, LearnedState state)
    static double balanceFromOffset(double offset, LearnedState state, Tuning tuning)   // paketprivat, som Pythons _
    public static Tuning tuningFromDict(Map<String, ?> data)                            // base None
    public static Tuning tuningFromDict(Map<String, ?> data, Tuning base)
    public static String fieldName(String pythonName)                                   // "room_target" → "roomTarget"
    public static boolean truthy(Object value)                                          // Pythons bool(value)
}

public class Tuning {
    140 publika fält i Pythons ordning (färdiga i skalet)
    public double clampFlow(double flow)        // min(flowMax, max(flowMin, flow))
    public double designSpan()                  // max(1, roomTarget - designOutdoor)
}

public class LearnedState {
    publika fält i Pythons ordning med standardobjekten (färdiga i skalet)
    public void forget(Tuning tuning)
    public void retune(Tuning tuning)
    public Map<String, Object> asDict()
    public static LearnedState fromDict(Map<String, ?> data, Tuning tuning)
}

public class ControlInputs  { public double dt; … ; public ControlInputs(double dt) }   // färdig
public class ControlOutput  { … }                                                       // färdig
```

Där Python och Java skiljer sig:

- **`float | None` är `Double`,** `float` är `double`, `bool` är `boolean`,
  `str | None` är `String` som kan vara `null`. Dataclassernas fält är
  publika fält, och `@property` (`available`, `contributed`, `design_span`)
  är vanliga metoder.
- **Pythons dictar är `Map<String, Object>`** (eller `Map<String, ?>` när du
  bara läser), med `null` för `None`, `Double`/`Integer`/`Boolean`/`String`
  som värden, `List<Double>` för en lista av tal, `List<List<Double>>` för
  `xx`, och nästlade `Map` för filtren. Allt du läser kan vara vad som
  helst av det - en `Integer` där du väntade en `Double`, en text, `null`.
- **`List<Map.Entry<String, Double>>`** är Pythons lista av `(key, value)`-par
  i `outdoor_in_use`. Testerna bygger paren med
  `new AbstractMap.SimpleEntry<>(key, value)`, för `Map.entry` tål inte
  `null` som värde.
- **Tupeln i `ControlOutput.learning`** är recorden `LearnerStatus`, och
  kartan en `LinkedHashMap<String, LearnerStatus>`.
- **Modulkonstanterna i `sunterm.py`** är `static final` i `SunTerm`, och
  `_OFFSET_ERA_*` i `model.py` är publika i `Model` (testerna läser dem).
- **`_finite` i `sunterm.py`** blir en egen privat hjälpare i `SunTerm`,
  och `_ready` en privat metod.

## Ordning

1. Recordsens konstruktorer och `estimate`-metoderna ovan, så att stegets
   tester kompilerar. `./gradlew utanPython` ska gå att köra.
2. `ParsingTest`: `TheLimits`, `AsMapping`, `AsFloat`, `AsOptionalFloat`,
   `PlausibleTemperature`, `OutdoorInUse`, `FromTheCode`. Allt annat i
   steget läser genom de här.
3. `PersistenceTest`: `TheEwma`, `TheDutyWatch`, `TheRegression`,
   `TheMultiRegression`, `FromTheCode`.
4. `SunTermTest`: `WhatItTakes` och `FromTheCode` först (`observe` och
   konstanterna), sedan `ItFindsTheSun`, `HowSureItIs`, `KeepingIt`.
5. `ModelTest.TheConstants` och `TheReadings`: konstanterna och records.
6. `ModelTest.TheTuning`: `clampFlow`, `designSpan`, `tuningFromDict`.
7. `ModelTest.TheLearnedState`: `retune`, `forget`, `asDict`, `fromDict`
   med migreringarna, och `Model.balanceFromOffset` och
   `effectiveCurvature` som de behöver.
8. `ModelTest.TheInputsAndOutput` och `FromTheCode`.
9. `SameAsPython` i alla fyra: åtta facitfiler, knappt 700 fall.

## Klurigt

- **`Map.of`, `List.of` och `Set.of` tål inte `null`:** `Map.of(...).get(null)`,
  `List.of(...).contains(null)` och `Set.of(...).contains(null)` kastar
  `NullPointerException`. `from_dict` frågar `COLD_CAUSES` om ett sparat
  `cold_after` som kan vara `None`, och `tuningFromDict` får nycklar som
  kan vara `null`. Därför är listorna och mängderna i skalet byggda som
  `Arrays.asList`/`LinkedHashSet` och låsta med `Collections.unmodifiable…`,
  och alla dictar du bygger själv ska vara `LinkedHashMap`: den håller
  Pythons ordning och tar `null` som värde. Testerna jämför nycklarnas
  *ordning* i varje `asDict`.
- **Pythons `float(value)`** är i Java tre fall: ett `Number` ger
  `doubleValue()` (en `Integer` från JSON är ett tal), ett `Boolean` ger
  1.0 eller 0.0, en `String` går genom `Double.parseDouble` i en `try` som
  fångar `NumberFormatException`; `null`, listor och kartor misslyckas.
  `parseDouble` tål `" 2 "`, `"1e3"`, `"+5"`, `"2."` och `".5"` som Python,
  och vad den gör annorlunda (`"1_000"`, hexadecimalt, `"1d"`) finns inte i
  facit. `as_float` lägger sedan på **ändlighetskollen**: `NaN` och ±oändligt
  blir reserven, också när de kom som texten `"nan"`, `"inf"` eller
  `"1e400"`.
- **`tuning_from_dict` använder `float()` utan ändlighetskollen:** ett
  sparat `nan` eller `inf` *tas* i ett talfält. Sexton facitrader har det.
  `plausible_temperature` har inte heller kollen men avvisar `NaN` ändå,
  för `NaN` faller på båda jämförelserna - skriv villkoret som
  `!(LOW <= value && value <= high)`, inte som två `<`-fall var för sig.
- **Pythons `bool(value)`** är inte `Boolean.parseBoolean`: `None`, `False`,
  `0`, `0.0`, `""`, `[]` och `{}` är falska och **allt annat är sant** -
  texten `"false"` är sann, `"0"` är sann och `NaN` är sann. Det är regeln
  för de elva booleska fälten i `tuningFromDict` och för de sex flaggorna i
  `LearnedState.fromDict`. Skriv den en gång, `Model.truthy`.
- **Reserven bara när nyckeln saknas.** Pythons `data.get("needs_reseed",
  True)` ger `True` bara när nyckeln inte finns; finns den med `None` är
  svaret `None`, och `bool(None)` är `False`. Samma sak med
  `data.get("seconds_cold", data.get("seconds_shortfall_cold"))`: reserven
  gäller bara när `seconds_cold` *saknas*, inte när den är `null`. Och
  migreringen av `curve_offset` körs bara när nyckeln `balance_shift` inte
  finns alls - en `balance_shift` som är `null` eller skräp stoppar den.
  I Java: `containsKey` före `get`.
- **`as_float(data.get(key), default)`** är däremot alltid samma sak:
  nyckeln saknas, är `null` eller skräp ger reserven, och reserven är
  fältets eget standardvärde (`1e6`, `1e9`, `tuning.gainReference`), inte
  noll.
- **Reflektion över `Tuning`.** `tuning_from_dict` loopar över
  dataclassens fält; i Java är det `Tuning.class.getFields()`, som ger de
  publika fälten. Nycklarna i filen är Pythons `snake_case` och fälten
  `camelCase`: `Model.fieldName("room_target")` ger `"roomTarget"`, och en
  nyckel som redan är `"roomTarget"`, eller `"Room_Target"`, är *ingen*
  nyckel och hoppas över. `field.getType() == boolean.class` säger om
  fältet är booleskt, `field.setDouble`/`setBoolean` skriver.
  `getFields()` lovar ingen ordning, så lita inte på den.
- **Basen kopieras, muteras inte.** `tuning_from_dict` bygger en *ny*
  `Tuning` med basens värden och skriver datan ovanpå. Testerna kollar att
  basen är orörd efteråt och att svaret inte är samma objekt.
- **`Math.clamp` är fel verktyg** för `clampFlow`: den kastar
  `IllegalArgumentException` när `min > max`, och Pythons
  `min(flow_max, max(flow_min, flow))` ger `flow_max` då. Ett test har
  `flowMin > flowMax`. Skriv min/max-paret.
- **Records får inte ändras fält för fält,** så de korta konstruktorerna är
  `this(...)` med Pythons standardvärden. `SensorReading("x", 21.0)` är en
  rumsgivare med vikt 1 och inga offsets; `maxAge` är `null`, inte `0`.
- **`Ewma.fromDict`-signaturen** är `fromDict(Map<String, ?> data, double
  seconds)`: tidskonstanten kommer från tuningen, inte från filen, och
  `data` går genom `Parsing.asMapping` först så att `null` är en tom dict.
  Värdet läses med `asOptionalFloat`, inte med en cast: en `Integer` i
  filen är ett tal, `"abc"` är `0.0`, och `null` förblir `null`. Dagens
  `(Double) data.get("value")` kastar `ClassCastException` på en `Integer`
  och `NullPointerException` på `null`-data.
- **`asDict` gärna `Map<String, Object>`.** Din `Ewma.asDict()` och
  `DutyWatch.asDict()` returnerar `Map<String, Double>`, och testerna tar
  emot dem som `Map<String, ?>` så att det kompilerar. Men
  `LearnedState.asDict()` ska vara `Map<String, Object>` och stoppar in dem
  som värden, `MultiRegression.asDict()` har en `Integer`, listor och en
  `Double` som kan vara `null`, och `SunTerm.asDict()` har en karta och en
  lista. Samma typ överallt är enklast.
- **`MultiRegression.fromDict` och `int(data.get("size") or 0)`:** Pythons
  `int()` tar `3`, `3.0` (och `2.9` → `2`) och texten `"3"`, men inte
  `"3.0"`, `True` (det är `1`) eller en lista, och `None` är `0` via
  `or`. Fel storlek, `xx`/`xy` som inte är listor, en rad av fel längd, en
  `null` eller en text i matrisen: allt ger `new MultiRegression(size,
  horizon)`. `residual_data` som saknas tar `residual`; `yy` som saknas
  eller är `null` är `null`.
- **`MultiRegression.yy()` och `null`.** Ditt `yy()` returnerar `double` ur
  ett `Double`-fält: med ett `yy` som är `null` efter `fromDict` kastar det
  `NullPointerException` vid uppackningen. Testerna läser `yy` genom
  `asDict().get("yy")` och kollar att `inSampleSigma()` faller tillbaka
  till `sigma()`; `yy()` själv anropas inte med `null`. Håll fältet
  `Double` och titta på `null` i `add` och `inSampleSigma` som Python gör.
- **`xx` och `xy` i `asDict` är kopior:** Pythons `[list(row) for row in
  self.xx]`. Ett test ändrar listan den fick och väntar sig att
  anpassningen är orörd. Samma för `SunTerm.asDict()`:s `hour`.
- **`SunTerm.observe`:** `_finite` på alla fyra, `dt <= 0` avbryter, ljuset
  klipps vid noll (`max(0, light)`) när det *läggs till timmen*, inte när
  timmen lämnas till anpassningen. Timmen går till `fit.add` med `dt =
  timmens sekunder`, inte en etta, och `hours`/`sunny_hours` räknas med
  `>` mot `SUNNY` på timmens medel. `int(as_float(...))` i `from_dict`
  trunkerar mot noll: `7.9` är `7`, `-2.5` är `-2` - Javas `(int)` gör
  samma sak.
- **`NaN` i jämförelser.** `assertEquals` i testerna räknar `NaN` lika med
  `NaN`, som Pythons facit gör (`_same`). Men i din kod gäller IEEE:
  `NaN == NaN` är falskt, `NaN <= x` är falskt. Det är därför
  `plausibleTemperature` ska skrivas med en negation (se ovan) och
  `Double.isFinite` är din vän i `asFloat`.
- **`forget` byter objekt,** behåller inte och nollar: `new Ewma(...)`,
  `new DutyWatch(...)`, `new Regression(...)` för `deltaScale`, `starved`,
  `curveFit`, `levelFit`, `gainFit`, med tuningens längder, och sist
  `retune`. Utefiltret, inomhusfiltren, `deltaFilter`, `sunHeat`,
  `sunTerm`, integralen, timrarna och flaggorna lämnas: ett tömt utefilter
  bad om 56 grader tillopp en septembermorgon.
- **`retune` på tolv ställen,** `sunTerm.fit.setHorizon` med. Och
  `fromDict` slutar med `retune` som Python gör, fast det är hängslen:
  längderna kommer redan från tuningen i varje nästlad `fromDict`, så
  facit ser ingen skillnad om du glömmer den. Håll Pythons ordning ändå.
- **`learning_hold_cell`** tas bara om det är en `String` - `""` med; ett
  tal eller `null` blir `null`. **`cold_after`** tas bara om det är en av
  `COLD_CAUSES` exakt (`"SWEEP"` är ingen), annars `"shortfall"` om det
  gamla `shortfall_cold` är sant enligt Pythons `bool`, annars `null`.
- **Facit:** åtta filer, `;`-separerade med tom cell för `None`. Dictar
  skrivs som i steg 10, `nyckel=typ:värde` med `|` emellan, med tre nya
  typer: `l:` en lista av tal med `,` emellan, `m:` en matris med rader
  skilda av `/`, och `d:` en nästlad dict en nivå ner med `&` mellan
  posterna och `~` i stället för `=`. Läsaren heter `Stored` (steg 7 har
  redan en `house.Facit`), och `Stored.assertSameDict` jämför två dictar
  som Python gör: samma nycklar i samma ordning, `null` mot `null`, `int`
  mot `int`, flyttal inom en miljarddel (residualerna en miljondel) och
  `NaN` lika med `NaN`. Felet säger vilken nyckel som skiljer, med sökvägen
  (`sun_term.fit.xx[1][0]`).
