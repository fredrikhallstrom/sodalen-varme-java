# Steg 16: Utrullningen

> **Originalet:** [`controller.py`](controller.py) i den här mappen, raderna
> `_learning_hold` (839–867) och `_observe_house` till och med `_distrust`
> (1088–1294). Det här är sparläget: att stänga av värmen innan huset ber
> om det, för att prognosen och husmodellen säger att det håller ändå.

`step()` anropar det så här (raderna 317 och 327–328):

```python
self._observe_house(inputs, indoor, delta, dt)
...
mode = self._mode(inputs, outdoor_summer, indoor)
mode = self._coast(inputs, indoor, mode, out)
```

Sex delar:

- **`conditions`:** vädret just nu som husmodellen vill ha det: ute, vind,
  vindriktning, solen på väggarna från `sunlightNow` och klockan. Utan
  utegivare finns inget väder, och svaret är null.
- **`night`:** natt för husmodellens nattbalans. Timmen ligger i fönstret
  `nightStartHour`–`nightEndHour`, solen är nere och huset har hunnit
  släppa solens värme (`secondsSinceSun` minst `learnAfterSunSeconds`).
  Utan klocka eller sol är det inte natt: det man inte vet lär man inte
  av.
- **`observeHouse`:** låter husmodellen se cykeln. Varje cykel, inte bara
  de med värmen av: tillopp minus retur är ett direkt mått på levererad
  värme, så det går in som regressor och januari blir data i stället för
  en blind fläck. Först utlåtandet, `LEARN_HOUSE` med `no_indoor` eller
  `no_outdoor` när något saknas och annars antalet sedda timmar som värde,
  sedan `house.observe` med deltat (null är noll), radiatorernas överskott
  och natten.
- **`distrust`:** varför husmodellen inte får bestämma än, eller `""` när
  den får. Först timmarna mot `coastMinHours`, sedan om modellen alls kan
  svara på om huset svalnar lämnat ensamt, sist om den tror att det inte
  gör det eller har förlusten på sitt golv. Policy, hållen här och inte i
  modellen: modellen säger vad den sett, regulatorn avgör om det räcker.
- **`learningHold`:** inlärningsspärren. Ett sparläge som får börja väntar
  tills kurvtabellens cell för det här vädret och den här dygnsdelen fått
  `coastLearningHoldSeconds` till av mätningar, men aldrig längre än
  `coastLearningHoldMaxSeconds` sammanlagt, och inte alls om cellen redan
  har `coastLearningCellHours`. Det som räknas är vad väntan själv förde in
  i cellen: en ny cell räknar från de timmar den redan hade.
- **`coast`:** ska huset lämnas att rulla på det det har. En prognos läst
  på två horisonter: shunten får stänga så snart huset håller längre än
  det tar att få värmen tillbaka i rummen, brännaren måste dessutom hinna
  tända och vara värd att stoppa. Allt räknas ut i varje läge, för hur
  länge huset håller är husets egenskap och inte regulatorns, men bara
  från `heating` blir svaret `coast`. En avstängning börjar bara från mål
  och fortsätter sedan ner till golvet; starten frågar om leaden plus
  `coastHysteresisSeconds`, fortsättningen bara om leaden.

## Testerna väntar sig

Skalet ligger sist i `HeatingController`. Inga nya poster och inga nya
fält: allt tillstånd finns redan i `LearnedState` och `ControlOutput`.

```java
public class HeatingController {
    Conditions conditions(ControlInputs inputs)                                      // _conditions, null utan ute
    boolean night(ControlInputs inputs)                                              // _night
    void observeHouse(ControlInputs inputs, Double indoor, Double delta, double dt)  // _observe_house
    String distrust()                                                                // _distrust
    boolean learningHold(ControlInputs inputs, ControlOutput out)                    // _learning_hold
    String coast(ControlInputs inputs, Double indoor, String mode, ControlOutput out) // _coast
}
```

Där Python och Java skiljer sig:

- **`f"{hours:.0f} av {needed:.0f} h data"`:** Pythons `.0f` avrundar
  halvor till jämnt, så 12,5 blir `12` och 13,5 blir `14`. Javas
  `String.format("%.0f")` avrundar halvor uppåt och ger `13`. Rätt är
  `(long) Math.rint(hours)` och `%d`, eller `Math.rint` följt av
  `String.valueOf` på ett `long`.
- **`inputs.hour % 24.0`:** Pythons `%` ger alltid något mellan 0 och 24,
  också för negativa tal. Javas `%` behåller tecknet. Ta resten och lägg
  på 24 om den är negativ. Inte `((hour % 24) + 24) % 24`: de två extra
  flyttalsstegen flyttar 23,52 till 23,519999999999996 och ett fönster
  som slutar 23,52 svarar fel.
- **`not inputs.forecast`:** en tom lista är falsk i Python. I Java är
  det `inputs.forecast.isEmpty()`, och en prognos som är null räknas
  också som ingen prognos (testet `noForecastAtAllIsNoneEither`).
- **`mode != MODE_HEATING`** är `!MODE_HEATING.equals(mode)`: lägena ur
  facitfilerna är nya strängar, inte konstanterna, så `!=` svarar fel
  där fast det råkar gå i handtesterna.
- **`float("inf")`** är `Double.POSITIVE_INFINITY`.
- **`(delta or 0.0)`** är `delta == null ? 0.0 : delta`.
- **`getattr(self.house, "observed_hours", None)`** är bara
  `house.observedHours()`: Java-modellen har alltid metoden.
- **`state.learning_hold_cell != cell`** med strängar är
  `!cell.equals(state.learningHoldCell)`. Cellen från `keyAt` är aldrig
  null, fältet kan vara det, så konstanten först.
- **`self.house.cools_when_left_alone()`** svarar `True`, `False` eller
  `None`. Javas `coolsWhenLeftAlone()` ger `Boolean`, så `cools is None`
  är `cools == null` och `not cools` är `!cools` först därefter.
- **`radiator_excess`** är `Thermal.radiatorExcess`, **`sunlight_now`**
  är `sunlightNow` och **`self.burner_lead_seconds()`** är
  `burnerLeadSeconds()`; allt finns sedan steg 12.
- **`state.outdoor.value`** är `state.outdoor.value()`.
- **Listuttrycket `weather`:** prognosluckor med `hours <=
  coastHorizonHours`, i samma ordning. En loop eller en `stream().filter`
  med `toList()`; `weather.get(0).conditions()` är Pythons
  `weather[0][1]`.

## Ordning

1. `conditions` (`RolloutTest.TheConditions`): fem rader.
2. `night` (`TheNight`): fönstret, solen, väntan.
3. `observeHouse` (`TheHouseWatcher`): bygger på de två.
4. `distrust` (`TheDistrust`): tre frågor till modellen och en text.
5. `learningHold` (`TheLearningHold`): kurvtabellen och klockan.
6. `coast` (`TheCoast`): den långa, och den sista; anropar `distrust` och
   `learningHold`.
7. `SameAsPython`: sex facitfiler, 2 384 fall. De lärda husen i
   receptfilen `coast_houses.csv` byggs en gång och delas av fallen.

## Klurigt

- **`_night` med `start == end`:** `start < end` är falskt, så den andra
  formen gäller: `hour >= start or hour < end` är alltid sant. Hela
  dygnet är natt. Med `start < end` är kanterna `start` inne och `end`
  ute.
- **`_observe_house` säger `no_indoor` före `no_outdoor`** när båda
  saknas, och ropar `observe` ändå: modellen själv räknar luckan.
- **`_distrust` i ordning:** timmarna först, också när modellen aldrig
  skulle kunna svara. `cools` None betyder `för lite data`, False eller
  förlusten på golvet `för lite varierad data ...`. Golvet frågas bara när
  `cools` är sant.
- **`_learning_hold` ändrar tillståndet i två steg** och det syns utifrån:
  cellen och basen sätts så snart cellen är ny, också när `left <= 0`
  sedan ger `false`. Väntan räknas bara när den faktiskt håller tillbaka,
  och då med `max(0, dt)`; samtidigt sätts `out.coastLearningHold` till
  det som är kvar och flaggan `learning_hold` läggs till.
- **`_coast` sätter `coasting = False` på fyra ställen men inte på alla:**
  avstängt, inget inne eller ingen prognos nollar; luckor bara bortom
  horisonten nollar *inte* utan lämnar både tillstånd och `out` orörda.
  Misstro nollar och sätter ändå `coastAllowance` och `coastMargin`, så
  kortet kan visa budgeten. Okänd utsikt nollar. Annat läge än `heating`
  nollar, men först efter att alla `out`-fält räknats.
- **Budgeten:** `reach` null ger hela `coastLearningBand`; annars andelen
  `min(1, max(0, reach) / coastUnknownReach)` av bandet, och ingenting
  alls när `coastUnknownReach` är noll eller negativt (`> 0.0` i Python). Marginalen är den större av
  `coastComfortBand` och budgeten.
- **`held`:** oändligt när utsikten håller hela horisonten, annars
  `hours`, och null-hours är 0.
- **Trösklarna:** shunten vill ha `shuntLeadSeconds`, plus
  `coastHysteresisSeconds` när ingen avstängning pågår. Brännaren vill ha
  `burnerLeadSeconds() + heatSourceOffDelay`, plus samma hysteres på
  samma villkor. Båda delas med 3600 och jämförs strikt: `held >`.
- **`bridges`:** vädret vänder (`turnsAt` inte null) eller
  `coastBridgeOnly` av. Det gäller shunten; brännarens `coastBurner` är
  bara hållningen.
- **Starten frågar spärren bara när den behövs:** `may` är sant om en
  avstängning pågår, annars om inne är minst mål *och inte* (shunten får
  *och* spärren håller). Pythons `and` kortsluter, så `_learning_hold`
  anropas bara när `coast_shunt` är sant; Javas `&&` gör detsamma, bara
  ordningen behålls. `coasting` blir `coastShunt && may`.
- **Flaggan `coast` och svaret `MODE_COAST`** kommer bara när `coasting`
  blev sant. Annars kommer `mode` tillbaka som det kom.
