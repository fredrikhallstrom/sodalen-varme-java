# Sodalen värme i Java

Värmekärnan från Sodalens värmereglering (`heating/` i sodalen-router-system),
skriven om i Java 25, ett steg i taget. Pythonversionen styr huset i dag. Den
här ska till slut räkna exakt likadant och köras bredvid i **skuggläge**: den
får samma mätvärden var 30:e sekund, räknar ut vad den *skulle* ha gjort och
jämförs med Pythonversionen, utan att styra något.

## Arbetsgången

- **Du skriver all kod** under `src/main/java/se/sodalen/heating/`.
  Varje del har sitt eget paket (`helper`, `pid`, `curve` ...). Testet ligger
  i samma paket som klassen det provar, och stegets `STEG.md` säger vilket.
- **Testerna skrivs åt dig**, ett steg i taget, under `steg/NN-namn/java/`.
  De är översatta från Pythonversionens tester. Där sådana saknas är de
  skrivna utifrån koden, och alla är kontrollerade mot en referenslösning.
- **Rött, grönt, refaktorera.** Testet säger vad som ska hända, du skriver
  minsta koden som gör det grönt, sedan gör du koden fin.
- **Ändra inte testerna.** Tycker du att ett test är fel, säg till. Det kan
  vara det.

## Kör

```
./gradlew utanPython    # allt utom SameAsPython: börja här
./gradlew test          # allt, med de tusentals Pythonfallen
```

I IntelliJ: öppna mappen som Gradle-projekt. `steg/*/java` blir testmappar
av sig själva, och du kan köra ett test eller en `@Nested`-grupp i taget.
Körkonfigurationen **Tester utan SameAsPython** (i listan uppe till höger)
kör `utanPython`.

`SameAsPython` jämför med Pythonversionen i tusentals fall och är märkt
`@Tag("python")`. De egna testerna säger vad som är fel ett fall i taget, så
ta dem först och Pythonfallen när de är gröna.

Första gången blir det **kompileringsfel**. Det är det första röda: testerna
frågar efter klasser som inte finns än. Skapa dem (Alt+Enter på det röda
namnet i IntelliJ) och gör dem gröna en grupp i taget.

## Stegen

Testerna låses upp med `steg` i `gradle.properties`. När ett steg är grönt
höjer du siffran, och då kommer nästa stegs tester med. Varje stegmapp har:

- `STEG.md`: vad delen gör i huset, vilka klasser testerna väntar sig, och
  Java-tips.
- `java/`: testerna.
- `resources/`: facit, alltså tusentals fall uträknade av Pythonversionen.
  `SameAsPython`-testerna läser dem, och de är grönast sist.
- Pythonfilen som steget översätter. Försök gärna först utifrån testerna och
  läs originalet när du kört fast eller vill jämföra.

| Steg | Del | Python | Läge |
|---|---|---|---|
| 1 | Tider i ord: "45 min", "1,9 veckor" | `durations.py` | tester klara |
| 2 | PID, den inre loopen: tilloppsfel in, ventilläge ut | `pid.py` | tester klara |
| 3 | Värmekurvan och EVR-skalan | `curve.py` | tester klara |
| 4 | Ventilkartan: vilket läge ger vilket tillopp | `valvemap.py` | |
| 5 | Kurvtabellen: lärda punkter per väder och tid på dygnet | `points.py` | |
| 6 | Skattarna: medelvärden och regression | `estimators.py` | |
| 7 | Husmodellen: hur fort huset svalnar, och när vädret vänder | `thermal.py` | |
| 8 | Prognosen: flera väderkällor vägda ihop | `forecast.py` | |
| 9 | Kurvanpassningen | `curvefit.py` | |
| 10 | Brännaren: olja, pellets och reläerna | `burner.py` | |
| 11 | Modellen: inställningar, lärt tillstånd, in- och utdata | `model.py` | |
| 12 | Regulatorn, i flera delsteg | `controller.py` | |
| 13 | Skuggläge: tjänst på sodalenservern som jämför med HA live | | |

Nästa stegs tester skrivs när du närmar dig det, så de följer Pythonversionen
som den ser ut då.

## Facit

`verktyg/facit.py` räknar fram facit ur Pythonkoden och kopierar in
Pythonfilerna:

```
python3 verktyg/facit.py <sökväg till heating/ i sodalen-router-system>
```

Fallen slumpas med ett fast frö, så samma körning ger samma filer.

## Från Python till Java

| Python | Java |
|---|---|
| `max(a, b)`, `min(a, b)` | `Math.max(a, b)`, `Math.min(a, b)` |
| `x ** y` | `Math.pow(x, y)` |
| `abs(x)` | `Math.abs(x)` |
| `x // y`, `math.floor(x)` | `Math.floor(x / y)`, `Math.floor(x)` (inte `(int)`) |
| `CONSTANT = 0.95` överst i filen | `private static final double CONSTANT = 0.95;` överst i klassen |
| `if x <= 0.0: return y` | `if (x <= 0.0) { return y; }` |
| `for i in range(1, n):` | `for (int i = 1; i < n; i++) {` |
| `None`, `x is not None` | `null`, `x != null` (med `Double`, inte `double`) |
| `(a, b, c)` som inte ändras | `List.of(a, b, c)` |

Ge mellanresultaten samma namn som i Python (`span`, `share`, `excess`), så
går det att jämföra rad för rad.

## Java 25 som passar här

- **`record`** för data som inte ändras: `PidGains`, `PidResult`. En record kan
  ha en extra konstruktor med standardvärden (`PidGains(kp, ti, td)`).
- **Verktygsklasser** med bara `static`-metoder: `final class` och en
  `private`-konstruktor, så att ingen kan skapa en.
- **`List.of(...)`** ger en lista som inte går att ändra. Det finns ett test
  som provar.
- **`Double` mot `double`**: där Python har `float | None` tar testerna
  `Double`, så `null` betyder "inget värde". Annars `double`.
- **`Locale.ROOT`** när du formaterar tal. Annars bestämmer datorns språk om
  det blir punkt eller komma. Det finns ett test för det också.
- **`Math.floor` mot `(int)`**: `(int) -0.5` blir 0 men `Math.floor(-0.5)` blir
  -1. Pythons `//` och `math.floor` är `Math.floor`.
- **Pythons `round()`** avrundar 1,25 till 1,2 ("bankers rounding"). Det gör
  `Math.rint` också, men tiderna vill ha 1,3.
