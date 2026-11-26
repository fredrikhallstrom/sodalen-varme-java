# Steg 14: Deltat och radiatorvakten

> **Originalet:** [`controller.py`](controller.py) i den här mappen, raderna
> `_delta` till och med `_delta_weight` (614–884), utom `_shadow_valve_map`
> och `_learning_hold` som hör till steg 17 och 16. Det här är cykelns
> andra fas: när kurvan sagt sitt frågar regulatorn radiatorerna hur mycket
> värme de faktiskt tar.

`step()` anropar det så här (raderna 307–311 och 329):

```python
delta, delta_expected, delta_trim = self._delta(
    inputs, indoor, demand, out, dt,
    base_flow=curve_flow + room_trim + override_trim,
    room=indoor_slow,
)
...
self._watch_radiators(mode, indoor, delta, delta_expected, dt, out, inputs.sweeping)
```

Fyra delar:

- **Deltat:** `Q = flöde × delta`, så med en pump som går jämnt är
  skillnaden tillopp minus retur ett direkt mått på levererad effekt. När
  något annat värmer huset, kaminen, solen, en full ugn, stänger
  termostatventilerna, vattnet går förbi radiatorerna och deltat faller
  långt innan innegivarna rört sig. Att sänka tilloppet då är både
  billigare och skönare än att vänta på att rummet skjuter över. Det
  väntade deltat är `deltaDesign` gånger behovet, skalat med `deltaScale`,
  ett veckolångt snitt av vad den här pumpen och de här radiatorerna ger
  mot dimensioneringen.
- **Tystnaden:** termen säger ifrån med `deltaQuiet` när den inte kan
  döma: ingen givare (`sensor`), avstängd (`off`), nästan inget behov
  (`demand`, deltat är brus ovanpå en nästan stängd ventil) och ljummet
  tillopp (`lukewarm`, under `deltaMinOvertemp` över rummet mäter deltat
  mest shunten själv). Varje gång glöms det den höll, `forgetDelta`.
- **Dömt bara i stillhet:** `deltaTrim` räknar bara när tilloppet står
  stilla på sitt börvärde, inom `deltaSettleBand` i `deltaSettleSeconds`.
  Medan ventilen är på väg är deltat ventilens verk, inte radiatorernas,
  och den senaste korrigeringen hålls, i högst `deltaHoldMaxSeconds`.
  Sedan släpper den och börjar om när det står stilla igen. Efter ett
  stopp döms inget alls i `deltaWarmupSeconds`: kalla rör är inte husets
  behov. Vikten `deltaWeight` är en ramp, inte en brytare: ett hus som låg
  precis på brytaren vippade den varje cykel i september och ventilen gick
  118 % av sin väg i timmen.
- **Radiatorvakten:** ett lågt delta betyder inget fel i sig, det är vad
  deltatermen är till för. Ett lågt delta medan huset är *kallare* än
  målet är något annat: rummen vill ha värme, ventilerna borde stå vidöppna
  och ändå kommer vattnet tillbaka nästan lika varmt. En kortsluten
  radiator, luft, en ventil som sitter fast. Dömt bara där deltat är fullt
  betrott och slingan reglerar, och rapporterat först när det hållit en god
  del av de senaste dygnen. Varningen går upp vid `starvedShare` och ned
  först under `STARVED_RELEASE` av den, så att den inte fladdrar.

## Testerna väntar sig

Skalet ligger i `src/main/java/se/sodalen/heating/house/`: ett nytt record
för Pythons tupel och metoderna sist i `HeatingController`.

```java
package se.sodalen.heating.house;

public record Delta(Double delta, Double expected, double trim)   // Pythons (delta, expected, trim)

public class HeatingController {
    ControlOutput out = null;                                         // Pythons _out, se nedan

    Delta delta(ControlInputs inputs, Double indoor, double demand, ControlOutput out)   // _delta med standardvärdena
    Delta delta(ControlInputs inputs, Double indoor, double demand, ControlOutput out,
                double dt, Double baseFlow, Double room)
    void said(String key, boolean learning, String why, Double value)                  // _said
    void forgetDelta()                                                                 // _forget_delta
    double deltaTrim(ControlInputs inputs, double delta, double expected, double weight,
                     double dt, ControlOutput out)                                     // _delta_trim
    void watchRadiators(String mode, Double indoor, Double delta, Double expected,
                        double dt, ControlOutput out)                                  // _watch_radiators, sweeping false
    void watchRadiators(String mode, Double indoor, Double delta, Double expected,
                        double dt, ControlOutput out, boolean sweeping)
    double deltaWeight(double supply, Double room)                                     // _delta_weight
}
```

Innan du börjar: Python sätter `self._out = out` först i `step()` och
`_said` läser det med `getattr(self, "_out", None)`. Lägg till fältet
`ControlOutput out = null;` bland de andra paketprivata fälten (`starved`,
`burnerLead`). `said` skriver i `out.learning` bara när `out` inte är null,
och testerna sätter `controller.out` själva.

Där Python och Java skiljer sig:

- **Tupeln** `return delta, expected, trim` blir `new Delta(delta,
  expected, trim)`. De två första är `Double` och kan vara null, den
  tredje är `double`.
- **`out.learning[key] = (learning, why, value)`** är
  `out.learning.put(key, new LearnerStatus(learning, why, value))`.
- **`state.delta_filter.value = None`** går inte att skriva rakt av:
  `Ewma.value` är paketprivat i `estimate`. Byt ut filtret i stället:
  `state.deltaFilter = new Ewma(state.deltaFilter.seconds(), null)`.
  Tidskonstanten behålls, värdet försvinner.
- **`_clamp`** är `clamp` i `HeatingController` från steg 12, inte
  `Math.clamp`.
- **`(watch.share or 0.0)`** är `share == null ? 0.0 : share`;
  `DutyWatch.share()` är `Double`.
- **`mode == MODE_HEATING`** med ett `mode` som kan vara null är
  `MODE_HEATING.equals(mode)`.
- **Pythons valfria argument** blir överlagringarna: `delta` med fyra
  argument är `dt` 0.0 och null för `baseFlow` och `room`;
  `watchRadiators` med sex är `sweeping` false.

## Ordning

1. `deltaWeight` (`DeltaAndRadiatorsTest.TheWeight`): tre rader.
2. `forgetDelta` och `said` (`WhatItSays`).
3. `deltaTrim` (`TheTrim`): uppvärmningen, stillheten, hållningen,
   släppet och själva korrigeringen. Längst, men varje gren är några rader.
4. `delta` (`WhenItIsQuiet`, `WhatTheWeightReads`, `TheExpectedDelta`,
   `TheRoomHasTheLastWord`).
5. `watchRadiators` (`TheRadiatorWatch`).
6. `SameAsPython`: fyra facitfiler, 2 300 fall.

## Klurigt

- **`if room is None: room = indoor`** sker *före* vikten, och
  `delta_readable_from` använder sedan `room_target` bara om `room`
  fortfarande är None, alltså om både `room` och `indoor` saknades.
- **Vikten dömer `base_flow`, inte det mätta tilloppet,** när `base_flow`
  ges: `step()` skickar börvärdet kurvan är på väg att justera, för
  tilloppet svarar på korrigeringen inom minuter och en grind på det
  stänger en slinga genom grinden själv.
- **`expected = max(1.0, deltaDesign · min(1.2, demand) · scale)`** med
  `scale` 1.0 när `deltaScale.value()` är null.
- **Inneslingan har sista ordet åt båda håll,** men bara ett håll får
  flagga: `trim < 0` i ett kallt hus ger `delta_held_cold`, `trim > 0` i
  ett varmt hus nollas tyst. Indoor null rör inget.
- **Uppvärmningen** nollar filtret, det hållna och `secondsSettled`, men
  inte `secondsUnsettled`. `deltaWarmingLeft` är vad som är kvar av
  `deltaWarmupSeconds`.
- **Stillheten** kräver ett `lastFlowSetpoint`: utan börvärde är
  `secondsSettled` noll varje cykel.
- **Släppet:** `secondsUnsettled >= deltaHoldMaxSeconds` tömmer filtret
  och det hållna och svarar 0.0 utan flagga och utan `deltaRead`. Under
  gränsen svaras det hållna med `delta_held_moving` och `deltaRead` är
  filtrets gamla värde, som kan vara null.
- **Korrigeringen** är `weight · clamp(−deltaGain · (expected − read),
  −deltaLowerLimit, deltaRaiseLimit)`: ett delta under det väntade sänker,
  över höjer, och gränserna är olika åt de två hållen.
- **`judged`** kräver allt på en gång: värmeläge, ingen sotning,
  `secondsSinceSweep >= learnAfterOverrideSeconds`, `out.deltaWeight >=
  1.0` (det är utdatans vikt, satt av `delta` tidigare i cykeln) och
  `indoor`, `delta`, `expected` alla med värde. Annars
  `watch.update(null, dt)`: vakten räknar tiden men dömer inte.
- **`held`** är två villkor: `expected − delta >= starvedMargin ·
  expected` och `indoor < roomTarget − deltaColdGuard`.
- **Hysteresen:** `line = starvedShare · (STARVED_RELEASE om starved
  annars 1.0)`, och det nya `starved` kräver både `watch.hours() >=
  starvedMinHours` och andelen på eller över linjen. `out.radiatorsStarved`
  speglar fältet, flaggan `radiators_starved` bara när det är sant.
