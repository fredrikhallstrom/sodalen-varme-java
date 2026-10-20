# Steg 5: Kurvtabellen

> **Originalet:** [`points.py`](points.py) i den här mappen är Pythonkoden du
> översätter. Varje funktion där blir en metod här, oftast rad för rad.

Varje timme som huset håller sitt mål med värmen igång är tilloppet det
behövde ett mått på kurvan. Det säger inte hur fel den gamla kurvan var,
eftersom det svaret blir inaktuellt så fort kurvan flyttas. Det säger vad
huset behöver. Tabellen samlar måtten i fack om två grader ute och sparar
dem i månader, så att en köldknäpp i december fortfarande räknas i februari.
Prickarna i värmekurvans diagram är facken härifrån, och kurvanpassningen i
steg 9 lägger kurvan genom dem.

- **Fack:** två grader breda, numrerade från noll. Fack 1 går från 2 till 4
  grader och fack -1 från -2 till 0.
- **Dygnets delar:** fyra om sex timmar, 0–6, 6–12, 12–18 och 18–24. Mitt på
  dagen behöver huset mycket mindre än på natten, eftersom solen och hushållet
  värmer. På hösten går värmen dessutom bara nätter och morgnar. Därför är
  ett facks behov **medelvärdet av delarna**, var och en lika mycket värd,
  och inte medelvärdet av alla timmar.
- **En cell** är ett facks mätningar från en del av dygnet: ett löpande
  medelvärde där varje mätning väger sina timmar, och spridningen.
- **Glömskan:** `fade` halverar vikten en gång per halveringstid. Medelvärdet
  och spridningen står kvar. Gamla mätningar räknas mindre, men de betyder
  inte något annat.
- **Domen:**
  - **Rött:** under 3 timmar, hörs inte alls.
  - **Grönt:** minst ett dygn, spridning högst 1,5 K och minst tre delar av
    dygnet, hörs fullt.
  - **Gult:** allt däremellan. Hörs mer ju fler timmar och delar det har,
    men högst hälften så mycket som ett grönt.

`need` är tilloppet över rumsmålet, i grader. Med 21 inne och 46 i tilloppet
är det 25.

## Testerna väntar sig

Skalet ligger i `src/main/java/se/sodalen/heating/curve/`. `Status`, `Rules`
och `Bin` har ingen logik och är färdiga. I `Cell` och `PointTable` fyller du
i metoderna och lägger till de fält du behöver.

```java
package se.sodalen.heating.curve;

public enum Status { RED, YELLOW, GREEN }

public record Rules(double redHours, double greenHours, double greenSpread,
                    int greenSectors, double sectorHours)
    public Rules()                       // 3 h, 24 h, 1,5 K, 3 delar, 1 h per del

public record Bin(double outdoor, double need, double hours, double spread, int sectors,
                  double wind, double age, Status status, double weight)

public final class Cell {
    public double weight()               // timmar mätning, med glömskan
    public double mean()                 // tillopp över rumsmålet, K
    public double m2()                   // vägd summa av kvadrerade avvikelser
    public double outdoor()              // var i facket mätningarna låg
    public double wind()
    public double age()                  // timmar sedan senaste mätningen
    public void add(double need, double outdoor, double wind, double hours)
    public void fade(double factor, double hours)
}

public final class PointTable {
    public static final double BIN_WIDTH     // 2.0
    public static final int SECTORS          // 4
    public static final double SECTOR_HOURS  // 6.0

    public static int binIndex(double outdoor)
    public void add(double outdoor, double need, Double wind, Double hour, double hours)
    public void fade(double hours, double halfLifeHours)
    public void clear()
    public List<Bin> bins()              // med new Rules()
    public List<Bin> bins(Rules rules)
    public String keyAt(double outdoor, Double hour)   // "fack:del", t.ex. "-2:3"
    public double hoursAt(double outdoor, Double hour)
}
```

Där Python och Java skiljer sig:

- `"red"`, `"yellow"` och `"green"` i Python är `Status` här.
- `Rules()` med standardvärden blir en extra konstruktor. `bins(rules=Rules())`
  blir två metoder med samma namn.
- `bin_index` står ensam i Pythonfilen och blir en `static`-metod i
  `PointTable`.
- `_sector` blir en `private` hjälpmetod som du skriver själv.
- `add(outdoor=..., need=..., ...)` med namn i Python tar argumenten i samma
  ordning här.

`as_dict` och `from_dict`, alltså att spara tabellen, kommer i steg 11
tillsammans med resten av det lärda tillståndet.

## Ordning

1. `CellTest.Adding`: `add` och värdena den ger
2. `CellTest.Fading`: `fade`
3. `PointTableTest.TheBins`: `binIndex`, `keyAt` och dygnets delar
4. `PointTableTest.Measuring`: `add`, `hoursAt`, `clear` och ett första
   `bins`
5. `PointTableTest.ThePartsOfTheDay`: `bins` med flera delar av dygnet
6. `PointTableTest.TheVerdict`: domen
7. `PointTableTest.Fading`: `fade`
8. `SameAsPython` i båda: 400 celler, 1500 tider och 600 tabeller

## Klurigt

- **`%` på negativa tal:** `-1 % 24` är 23 i Python men -1 i Java. Tiden kan
  vara negativ eller över 24, och testerna provar båda.
  `((hour % 24.0) + 24.0) % 24.0` ger samma som Python. `Math.floorMod`
  finns bara för heltal.
- **`//` och `math.floor`** är `Math.floor`. Klockan 6 är del 1, och -0,5
  grader ligger i fack -1: `(int) (-0.5 / 2.0)` blir 0, men
  `(int) Math.floor(-0.5 / 2.0)` blir -1.
- **`sectors / SECTORS`:** i Python 3 ger `/` alltid decimaler, men i Java
  blir `int / int` ett heltal, så 3 / 4 blir 0. Gör den ena till `double`:
  `(double) sectors / SECTORS`.
- **En nyckel av två tal:** Pythons `cells` har nycklar som `(fack, del)`. I
  Java kan en liten `record` vara nyckel i en `Map`, eftersom en record får
  `equals` och `hashCode` av sig själv. Ett `int[]` duger inte som nyckel:
  två arrayer med samma tal räknas ändå som olika.
- **`sorted(by_bin)`:** facken ska komma med det kallaste först. En `TreeMap`
  håller nycklarna sorterade, en `HashMap` gör det inte.
- **`counted or cells`:** en tom lista räknas som falsk i Python, så `or` ger
  `cells` när ingen del av dygnet har en timme. I Java blir det
  `counted.isEmpty() ? cells : counted`.
- **`wind or 0.0`:** `None` blir 0, och sedan gör `max(0.0, ...)` att även en
  negativ vind blir 0.
- **`math.isfinite`** är `Double.isFinite`. Det är falskt både för NaN och för
  oändligheten.
- **`0.5 ** x`** är `Math.pow(0.5, x)`.
- **`sum(...)` och `min(...)` över cellerna:** en loop som summerar, eller
  `cells.stream().mapToDouble(Cell::weight).sum()`.
- **Vakten i `Cell.add`** gäller summan `weight + hours`, inte bara `hours`.
  Skillnaden syns först i `SameAsPython`.
- **Pythonfallen är följder:** varje rad i facit är en följd av anrop, som
  `add,...|fade,...|clear`, som testet spelar upp mot en ny cell eller tabell.
  Felet säger vad som skiljer, till exempel `bins[2].need` eller `keyAt[4]`.
