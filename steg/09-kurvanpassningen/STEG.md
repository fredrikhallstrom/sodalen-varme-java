# Steg 9: Kurvanpassningen

> **Originalet:** [`curvefit.py`](curvefit.py) i den här mappen är Pythonkoden du
> översätter. Varje funktion där blir en metod här, oftast rad för rad.

Kurvtabellen från steg 5 har prickarna: vad huset behövde i tillopp vid varje
utetemperatur. Det här steget lägger kurvan genom dem. Balansen, där kurvan
börjar, vet husmodellen bättre än prickarna, så med den given är lutningen
det enda tal som prickarna får bestämma, och för en given böj är det vanlig
viktad minsta kvadrat: varje prick säger hur mycket över målet huset behövde,
och kurvan säger lutningen gånger en känd form. Installatörens lutning är en
prior värd en fjärdedels betrodd prick.

Två spärrar, båda från vad en anpassning på bara mild väderlek skulle göra:

- **Lutningen får bara flytta så långt prickarna når.** Med betrodda prickar
  ner till T får den flytta `MAX_NUMBER_SHIFT` EVR-kurvor gånger hur långt T
  ligger mot dimensionerande kyla. Utan en betrodd prick: ingenstans.
- **Böjen söks först när betrodda prickar når ordentligt in i kylan**, och
  får flytta kurvans mitt högst `bendLimit` grader från fysikens kurva.

Och Fredriks regel: betrodda prickar räknas bara som en **obruten kedja**,
högst `maxGreenGap` grader från den ena till nästa. En ensam grön prick på
minus tio med ingenting mellan den och plus fyra får vänta tills facken
emellan fyllts. Med en kedja om två eller fler får anpassningen styra
(`steerable`). Prickar långt från kurvan, en tänd kamin eller ett öppet
fönster, kastas och anpassningen görs om.

## Testerna väntar sig

Skalet ligger i `src/main/java/se/sodalen/heating/curve/`, bredvid din
`Curve` från steg 3 och `PointTable`, `Bin` och `Status` från steg 5. `CurveFit`
är en record med en metod, och `Fitting` har konstanterna och de två fria
funktionerna.

```java
package se.sodalen.heating.curve;

public record CurveFit(double slope, double balance, double curvature, Double coldestGreen, int used,
                       List<Double> rejected, List<Double> chain) {
    public CurveFit(double slope, double balance, double curvature, Double coldestGreen, int used,
                    List<Double> rejected)                        // Pythons chain=(): tom lista
    public boolean steerable()                                    // len(chain) >= MIN_CHAIN
}

public final class Fitting {
    konstanterna MAX_NUMBER_SHIFT, OUTLIER, BEND_COLD, BEND_GRID, BEND_PRIOR, MIN_CHAIN, DEFAULT_PRIOR_WEIGHT

    public static List<Bin> greenChain(List<Bin> bins, double maxGap)
    public static CurveFit fitCurve(List<Bin> bins, double room, double designOutdoor, double balance,
                                    double slopePrior, double curvature, double bendLimit)
    public static CurveFit fitCurve(List<Bin> bins, double room, double designOutdoor, double balance,
                                    double slopePrior, double curvature, double bendLimit,
                                    double priorWeight, Double maxGreenGap)
}
```

Där Python och Java skiljer sig:

- **Nyckelordsargumenten med standardvärden** (`prior_weight=0.25`,
  `max_green_gap=None`) är den korta `fitCurve`, som anropar den långa med
  `DEFAULT_PRIOR_WEIGHT` och `null`.
- **`_Shape`, `_Prior` och `_bend`** är privata inuti `Fitting`: två
  `private static` nästlade klasser och en privat metod. Testerna rör dem
  aldrig, så du formar dem som du vill, men Pythons uppdelning är bra.
- **Tuplerna `rejected` och `chain`** är `List<Double>`. `coldest_green`
  som kan vara `None` är `Double`.
- **`curve.supply`, `evr_number`, `evr_slope`, `middle_deviation`** är dina
  `Curve.supply`, `evrNumber`, `evrSlope`, `middleDeviation`, med samma
  argument i samma ordning. **`BIN_WIDTH`, `GREEN`, `bin_index`** är
  `PointTable.BIN_WIDTH`, `Status.GREEN`, `PointTable.binIndex`.
- **`BEND_GRID`** står färdig i skalet: en oföränderlig `List<Double>` med
  71 värden, 0,50 till 1,20, samma tal som Pythons `round(0.5 + 0.01 * step, 2)`.

## Ordning

1. `CurveFitTest.TheRecord`: recorden, `steerable` och konstanterna.
2. `CurveFitTest.TheChain`: `greenChain`.
3. `CurveFitTest.TheFit`: `fitCurve` utan böj, alltså `Shape`, `Prior.solve`
   och avvikarna. Testerna fyller en `PointTable` från en känd kurva
   (`Dots` i testmappen, översatt från Pythontesterna) och ser om
   anpassningen hittar tillbaka till den.
4. `CurveFitTest.TheBend`: `Prior.cost` och `bend`.
5. `CurveFitTest.FromTheCode`: kanterna, skrivna ur Pythonkoden.
6. `SameAsPython`: 400 anpassningar uträknade av Pythonversionen.

## Klurigt

- **`sorted(greens, key=lambda item: -bin_index(item.outdoor))`** är en
  **stabil** sortering på det negerade facknumret: två gröna i samma fack
  behåller sin ordning. Javas `List.sort` och `stream().sorted` är också
  stabila, så sortera med `Comparator.comparingInt` på minus facknumret.
  Sortera inte stigande och vänd sedan: då byter de som ligger lika plats.
- **`int(round(max_gap / BIN_WIDTH))`:** Pythons `round` avrundar halvor till
  jämnt, `round(2.5)` är 2 och `round(3.5)` är 4. Javas `Math.round` ger 3
  och 4. Använd `Math.rint`, som gör som Python.
- **`runs[-1][-1]`** är sista facket i sista raden: `runs.getLast().getLast()`.
- **`len(run) >= len(best)`** med `>=`: vid lika lång rad vinner den senare,
  som är den kallare. Med `>` vinner fel rad.
- **`any(item is c for c in chain)`** jämför identitet, inte värde. I Java
  är det `c == item` på referenserna. `chain.contains(item)` använder
  recordens `equals` och ger samma svar så länge två fack inte är exakt lika,
  men `==` är det Python gör.
- **`item not in outliers`** jämför däremot värde, för `Bin` är en vanlig
  dataclass. Där är `outliers.contains(item)` rätt.
- **`min(greens) if greens else None`:** tom lista ger `null`, inte ett
  undantag.
- **`reach`** räknas bara när `coldest` finns och `balance > designOutdoor`;
  annars 0,0. Klämd till 0 till 1.
- **`(self.shape.balance + self.shape.design) / 2.0`** är referenspunkten
  för priorns styrka, och `strength = weight * reference * reference`.
- **Summorna i `solve` och `cost`** går i fackens ordning. Håll samma ordning
  i Java, så blir talen bit för bit desamma.
- **`top / bottom if bottom > 0.0 else self.slope`** och sedan
  `min(self.high, max(self.low, slope))`: först kvoten, sedan klämningen.
- **Avvikarna** döms mot den **första** anpassningen med `> OUTLIER`,
  strängt, och lösningen görs om **en** gång utan dem. `used` räknas efteråt,
  `rejected` är avvikarnas utetemperaturer i fackens ordning.
- **Böjen** söks bara när `bendLimit > 0.0`, `coldest` finns och
  `coldest <= BEND_COLD`. Bästa start är referensböjen med sin lösta lutning
  och dess kostnad. I rutnätet hoppas böjar över när `abs(moved) > limit`,
  och en ny bäst tas bara på `cost < bestCost`, strängt.
- **`(curvature - reference) ** 2`** är `d * d`.
- **`sorted(greens, reverse=True)`** i resultatet: kedjan varm ände först,
  alltså fallande.
- **Facit:** `curve_fit.csv` har facken som `outdoor,need,hours,spread,
  sectors,wind,age,weight,status` med `|` emellan, anpassningens ingångar,
  svaren, och sist ett eget `greenChain` på samma fack med `chainGap`. Felet
  säger vilket tal som skiljer, till exempel `chain[2]` eller `slope`.
