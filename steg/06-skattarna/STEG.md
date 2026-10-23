# Steg 6: Skattarna

> **Originalet:** [`estimators.py`](estimators.py) i den här mappen är Pythonkoden du
> översätter. Varje funktion där blir en metod här, oftast rad för rad.

Här finns ingen värme alls. Det här är räknandet som resten av regleringen
står på: ett medelvärde som följer sina prov, en andel av tiden, en rät linje
och samma sak i flera variabler. Samma kod skulle fungera för ett batteri
eller en vattentank. Husmodellen i steg 7 och prognosen i steg 8 bygger på
den.

Allt här glömmer med tiden, och det som avgör hur mycket är **den tid som
faktiskt gick**, `dt`. Home Assistant är inget realtidssystem. En cykel kan
komma sent, och efter en omstart kan det ha gått en timme. Då ska filtret
räkna med en timme och inte låtsas att det bara var trettio sekunder.

- **`Ewma`:** ett glidande medelvärde. Efter en tidskonstant har det gått
  63 % av vägen mot ett nytt värde, efter tre 95 %. Från trettio
  tidskonstanter tar det provet helt.
- **`DutyWatch`:** hur stor del av tiden något höll, till exempel hur mycket
  av senaste dygnet brännaren gick. Bara tid som gick att avgöra räknas.
  `weight` säger hur mycket det var, så att 100 % av tio minuter går att
  skilja från 100 % av tre dygn.
- **`Regression`:** den räta linjen y = a + b·x genom prov som bleknar, i
  fem löpande summor utan någon lista. Kurvan lär sig sin förskjutning med
  den, och ventilen sin förstärkning. Ligger alla prov på samma x finns
  lutningen inte i datan. Då säger `fit` det, i stället för att dela med
  noll.
- **`MultiRegression`:** samma sak i flera variabler, med matriser.
  Husmodellen lär sig huset med den: hur fort det svalnar, hur mycket vinden
  och solen lägger till. Fyra saker gör den mer än en vanlig anpassning:
  - **Ridge:** en miljondel av vikten läggs på diagonalen, så att två
    kolumner som rör sig tillsammans inte får lösningen att explodera.
  - **Förkunskap (`Prior`):** det fysiken redan vet om en koefficient drar
    skattningen dit där datan inte säger något, och märks mindre ju mer data
    som kommer.
  - **Golv:** en koefficient vars tecken är känt får aldrig hamna under sitt
    golv. Alla kombinationer av vilka golv som håller provas, och den bästa
    som håller alla vinner.
  - **Osäkerheten:** `variance(x)` är liten där anpassningen har data och
    växer ju längre ut den ombeds gissa. Det är därför modellen blir mindre
    säker på en kuling i minus tjugo när den bara sett milt och stilla väder.
    Förkunskap gör skattningen bättre, men aldrig modellen säkrare än datan
    tillåter.

## Testerna väntar sig

Skalet ligger i `src/main/java/se/sodalen/heating/estimate/`. `Fit` och
`Prior` har ingen logik och är färdiga. I de andra klasserna fyller du i
metoderna och lägger till de fält du behöver.

```java
package se.sodalen.heating.estimate;

public final class Ewma {
    public Ewma(double seconds)
    public Ewma(double seconds, Double value)
    public double seconds()
    public Double value()                     // null tills första provet
    public Double update(Double sample, double dt)
}

public final class DutyWatch {
    public DutyWatch(double seconds)
    public double seconds()
    public Double share()                     // 0-1, null tills något avgjorts
    public double weight()                    // sekunder, med glömskan
    public double hours()
    public void update(Boolean held, double dt)
}

public record Fit(double intercept, double slope, boolean identifiable)

public final class Regression {
    public Regression(double horizon)
    public double horizon(), weight(), sumX(), sumY(), sumXx(), sumXy()
    public void add(double x, double y, double dt)
    public void fade(double dt)
    public double meanX(), meanY(), varianceX()
    public Fit fit(double minVariance)
}

public record Prior(double expected, double strength)

public final class MultiRegression {
    public static final double RIDGE          // 1e-6

    public MultiRegression(int size, double horizon)              // med RIDGE
    public MultiRegression(int size, double horizon, double ridge)
    public static double[] solveSymmetric(double[][] matrix, double[] rhs)

    public int size()
    public double horizon(), ridge(), weight(), yy(), residual(), residualData()
    public double[][] xx()
    public double[] xy()
    public void configure(Map<Integer, Prior> priors, Map<Integer, Double> floors)
    public void add(double[] x, double y, double dt)
    public void rescale(int index, double factor)
    public double[] coefficients()            // null med för lite data
    public Double predict(double[] x)
    public double sigma()
    public double inSampleSigma()
    public Double reach(double[] x)
    public Double variance(double[] x)
    public Double uncertainty(double[] x)
}
```

Där Python och Java skiljer sig:

- `None` blir `null`, därför `Double` och `Boolean` där Python har
  `float | None` och `bool | None`. Där värdet aldrig kan saknas är det
  `double`.
- `fit` returnerar en tupel `(intercept, slope, identifiable)` i Python och
  en `Fit` här.
- `priors` är `{index: (expected, strength)}` i Python och en
  `Map<Integer, Prior>` här.
- `ridge=1e-6` som standardvärde blir konstanten `RIDGE` och en extra
  konstruktor.
- Pythons `@property` (`sigma`, `hours`, `mean_x` och de andra) blir vanliga
  metoder: `sigma()`, `hours()`, `meanX()`.
- `solve_symmetric` står ensam i Pythonfilen och blir en `static`-metod i
  `MultiRegression`.
- `ValueError` blir `IllegalArgumentException`.
- `_regularised`, `_data_coefficients`, `_solve` och `_solve_pinned` blir
  `private` hjälpmetoder som du skriver själv. Pythons `_beta` och
  `_beta_ready` blir två fält.
- `yy` kan vara `None` i Python, men bara när tillståndet sparades innan det
  fanns. Det kommer med `as_dict` och `from_dict` i steg 11, och här är det
  en `double`.

## Ordning

1. `EwmaTest`: hela, det är den minsta klassen
2. `DutyWatchTest`
3. `RegressionTest.Adding`, `Fading`, `TheMeans` och `Fitting`
4. `MultiRegressionTest.Solving`: `solveSymmetric` först, resten bygger på den
5. `MultiRegressionTest.Fitting`: `add`, `coefficients` och `predict`, utan
   förkunskap och golv
6. `MultiRegressionTest.HowSureItIs` och `TheSpreadInSample`: `sigma`,
   `inSampleSigma`, `reach`, `variance` och `uncertainty`
7. `MultiRegressionTest.WhatItIsTold`: `configure`, förkunskap och golv
8. `MultiRegressionTest.AnotherUnit`: `rescale`
9. `SameAsPython` i alla fyra: 500 filter, 400 andelar, 500 linjer, 500
   ekvationssystem och 400 anpassningar

## Klurigt

- **`2.718281828459045 ** -ratio`** är `Math.pow(Math.E, -ratio)`. Python
  skriver ut talet för att slippa importera `math` för ett enda anrop.
  `Math.exp(-ratio)` ger samma sak, ibland med en annan sista decimal, och det
  klarar testerna.
- **Gränsen vid 30:** `ratio < 30.0`, inte `<=`. Från trettio tidskonstanter
  är faktorn exakt 1 eller 0, och testerna provar precis trettio.
- **Ordningen i `DutyWatch.update`:** vikten bleknar *före* kollen av
  `held == null`. Tid som inte gick att avgöra räknas inte, men den går
  ändå.
- **`max(range(column, size), key=lambda r: abs(work[r][column]))`** väljer
  den rad där talet i kolumnen är störst, och den *första* om två är lika
  stora. En loop med `>` gör samma sak, men `>=` väljer den sista.
- **`work = [list(row) + [rhs[i]] ...]`** gör en kopia. Ändrar du i
  `matrix` direkt ändrar du anroparens matris. Ett test kollar att den är
  orörd efteråt.
- **Byta två rader** i en `double[][]` går med en tillfällig variabel, och
  det är referenserna som byts:
  `double[] t = work[a]; work[a] = work[b]; work[b] = t;`.
- **`dict(priors)` i `configure`** är också en kopia. Sparar du kartan du
  fick ändras anpassningen när anroparen ändrar sin karta. `Map.copyOf` ger
  en kopia som inte går att ändra.
- **Det sparade svaret:** `coefficients()` räknar bara om när något ändrats:
  `add`, `configure` och `rescale` gör det. Glömmer du någon av dem i Java
  gäller det gamla svaret för länge. Python sätter `_beta_ready = False` på
  alla tre ställena.
- **`sorted(self.floors)` och `mask >> bit & 1`:** bit nummer `bit` i
  `mask` säger om golv nummer `bit`, i sorterad ordning, är fastlåst. Med
  två golv blir det fyra kombinationer, 0 till 3. `1 << n` är 2 upphöjt till
  n, och i Java betyder `>>` och `&` samma sak. Men `&` binder svagare än
  `==` i Java, så det behövs parenteser: `((mask >> bit) & 1) == 1`.
- **`float("inf")`** är `Double.POSITIVE_INFINITY`, och `** 0.5` är
  `Math.sqrt`.
- **`zip(beta, x)` och `sum(...)`** blir en loop med ett index.
- **`if len(x) != self.size: return`:** ett prov av fel storlek släpps tyst.
  Ett undantag här skulle stoppa reglercykeln, och med den hjärtslagen som
  Shellyn vaktar.
- **`not (math.isfinite(factor) and factor > 0.0)`:** `NaN > 0.0` är falskt
  både i Python och Java, men oändligheten är större än noll. Därför kollar
  Python `isfinite` också.
- **Facit är följder,** som i steg 5. Varje rad är en följd av anrop, till
  exempel `add,y,dt,x0,x1|coef|rescale,1,2.0`, som testet spelar upp mot en
  ny anpassning. `coef` frågar bara efter koefficienterna, så att ett svar som
  sparats för länge syns. Felet säger vad som skiljer, till exempel
  `reach[2]` eller `xx[1][0]`.
