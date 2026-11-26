# Steg 15: Läget och värmekällan

> **Originalet:** [`controller.py`](controller.py) i den här mappen, raderna
> `_mode` till och med `_burner_for_heating` (885–1084). Det här är de två
> beslut som styr allt annat i cykeln: vilket stopp som gäller, och om
> brännaren på pannan ska gå.

`step()` anropar det så här (raderna 327, 347–350):

```python
mode = self._mode(inputs, outdoor_summer, indoor)
...
out.boiler_overheated = self._boiler_overheated(inputs.boiler)
heat_source = self._heat_source(mode, dt, inputs.hot_water, out, inputs.sweeping)
inner_mode = self._inner_mode(inputs, mode, emergency)
cap = self._cold_boiler_cap(inputs) if inner_mode == MODE_HEATING else None
```

Fem delar:

- **`mode`:** vilket stopp som gäller, om något. Avstängt nollar båda
  stoppen och ger `off`. Sommarstoppet läser dygnssnittet, inte kurvans
  tretimmarssnitt: att stänga av värmen är ett beslut om årstiden, och en
  varm eftermiddag är inte en sådan. Det slår till vid `summerOutdoor` och
  släpper först `summerHysteresis` under. Varmstoppet slår till när inne
  är över mål plus `warmStopMargin` och släpper `warmStopHysteresis`
  under det, och utan innegivare släpper det direkt: ett varmstopp på en
  gammal mätning skulle hålla huset kallt. Utan tilloppsgivare är det
  `hold` tills `failsafeAfterSeconds` gått, sedan `failsafe`. Annars
  `heating`.
- **`coldBoilerCap`:** pannskyddet. En panna som shunten
  dragit ner under brännarens larmgräns är regleringens eget verk, och
  vakten kan inte skilja det från en brännare som slocknat. Första starten
  efter ett sparläge är där det händer: slingan kall, börvärdet högt och
  en pelletsbrännare som slocknade i stoppet minuter från all värme. Så
  öppningen följer pannan: golvet `coldBoilerFloor` från
  `coldBoilerClearance` över gränsen och nedåt, fri `coldBoilerBand`
  längre upp, en linje emellan. En linje och inte en brytare, av samma
  skäl som deltats vikt.
- **`boilerOverheated`:** för het för brännaren. Slår till vid
  `boilerMax`, släpper först vid `boilerMaxRelease`, och hålls genom en
  saknad mätning: en givare som faller bort bevisar inte att pannan
  svalnat.
- **`heatSource`:** om brännaren ska gå. Bara långa stopp stänger av den,
  och först när stoppet hållit `heatSourceOffDelay`. Klockan räknar tid
  stoppad, inte tid i just det här stoppet, så ett varmt hus som blir en
  mild period stänger av utan att börja om. En het panna går före allt,
  sotningen före allt under den, varmvattnet inklusive: en brännare som
  tänder för en dusch medan sotaren jobbar är det enda detta aldrig får
  göra. Varmvattnet går före varje stopp, och säger vems fel det är:
  `burnerForHotWater` är sant bara när värmen ensam hade sagt av.
- **`burnerForHeating`:** vad radiatorerna vill, varmvattnet oräknat. I
  sparläget är svaret prognosens, `!coastBurner`, utan klocka: beslutet
  krävde redan att huset håller längre än tändning plus fördröjning är
  värd. Ett varmstopp med `coastBurner` är samma kunskap och stänger också
  utan klocka. Annars får läget stängas av om inställningen för det säger
  så, och då först efter `heatSourceOffDelay`.

## Testerna väntar sig

Skalet ligger sist i `HeatingController`. Inga nya poster och inga nya
fält: allt tillstånd finns redan i `LearnedState`.

```java
public class HeatingController {
    String mode(ControlInputs inputs, double outdoorSummer, Double indoor)            // _mode
    Double coldBoilerCap(ControlInputs inputs)                                        // _cold_boiler_cap
    boolean boilerOverheated(Double boiler)                                           // _boiler_overheated
    boolean heatSource(String mode, double dt, boolean hotWater, ControlOutput out)   // _heat_source, sweeping false
    boolean heatSource(String mode, double dt, boolean hotWater, ControlOutput out, boolean sweeping)
    boolean burnerForHeating(String mode, ControlOutput out, boolean stopped)         // _burner_for_heating
}
```

Där Python och Java skiljer sig:

- **`mode in MODES_STOPPED`** är `MODES_STOPPED.contains(mode)`.
  Mängden har `off`, `summer`, `warm` och `coast`.
- **`mode == MODE_WARM`** med ett `mode` som kan vara null är
  `MODE_WARM.equals(mode)`.
- **`float(inputs.boiler)`** är bara `inputs.boiler`: en `Double` packas
  upp av sig själv i ett uttryck med `double`.
- **`_clamp`** är `clamp` i `HeatingController`, inte `Math.clamp`.
- **Pythons valfria `sweeping`** blir överlagringen med fyra argument som
  anropar den med fem och `false`.
- **Svaret `float | None`** i `coldBoilerCap` är `Double`, och `return
  None` är `return null`.

## Ordning

1. `boilerOverheated` (`ModeAndHeatSourceTest.TheOverheat`): fem rader kod.
2. `coldBoilerCap` (`TheColdBoiler`).
3. `mode` (`TheMode`).
4. `burnerForHeating` (`TheBurnerForHeating`).
5. `heatSource` (`TheHeatSource`): bygger på den förra.
6. `SameAsPython`: fem facitfiler, 2 500 fall.

## Klurigt

- **Sommarens två trösklar:** in vid `outdoor_summer >= summer_outdoor`,
  ut vid `outdoor_summer < summer_outdoor - summer_hysteresis`. Mellan dem
  står stoppet kvar som det var. Lika med tröskeln är in, lika med den
  nedre är inte ut.
- **Varmstoppets trösklar är spegelvända:** in vid `indoor > room_target +
  warm_stop_margin`, strikt, så exakt på marginalen är inte varmt; ut vid
  `indoor < ... - warm_stop_hysteresis`, också strikt.
- **Ordningen i `_mode`:** sommaren prövas och uppdateras alltid före
  varmstoppet, och svaret `summer` kommer före varmstoppet prövas. Ett
  varmstopp som stod kvar står alltså kvar i sommaren, oprövat.
- **`indoor is None` nollar `warm_stopped`** utan att pröva något, och
  `heating_enabled` falskt nollar båda. Det är det enda stället där
  `summer_stopped` nollas utan temperatur.
- **`seconds_without_flow >= failsafe_after_seconds`:** klockan räknas i
  `timers` i steg 13, här läses den bara. Lika med är `failsafe`.
- **`cold_boiler_cap`:** `over` är `boiler - boiler_limit -
  cold_boiler_clearance`, och `over >= cold_boiler_band` är null, inte
  100. Golvet klipps till 0–100 före linjen, och `over / cold_boiler_band`
  klipps till 0–1, så ett `over` under noll ger golvet.
- **`_boiler_overheated`** med `boiler` null ändrar inget och svarar det
  gamla. Mellan `boiler_max_release` och `boiler_max` ändras inget heller.
- **`seconds_stopped` räknas före allt annat** i `_heat_source`, också
  när pannan är het eller det sotas. Och den räknas för alla fyra
  stoppade lägen, `coast` med.
- **Flaggorna:** `boiler_overheat` och `sweeping` avbryter, så ingen
  annan flagga följer dem. `hot_water` svarar direkt, så `coast_burner`
  sätts i `_heat_source` bara när det inte är varmvatten. De två kan
  aldrig stå tillsammans.
- **`burner_for_hot_water`** är `not _burner_for_heating(...)`: anropet
  görs för svaret, och `_burner_for_heating` sätter inga flaggor själv.
- **`_burner_for_heating` litar på `stopped`** som den får det, inte på
  läget. `step()` räknar fram det ur `MODES_STOPPED`, men testerna skickar
  det fritt, och `hold`/`failsafe` med `stopped` sant är ändå sant: de
  står inte i listan över lägen som får stänga av.
- **Exakt på fördröjningen är av:** `seconds_stopped <
  heat_source_off_delay` är strikt.
