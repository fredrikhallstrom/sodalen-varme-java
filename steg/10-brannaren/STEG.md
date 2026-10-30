# Steg 10: Brännaren

> **Originalet:** [`burner.py`](burner.py) i den här mappen är Pythonkoden du
> översätter. Varje funktion där blir en metod här, oftast rad för rad.

Brännaren är fastskruvad på pannan och byts fysiskt: pellets den här vintern,
olja en annan. Samma två reläer tjänar den som sitter där: **styrkabeln** som
ber brännaren elda, och **huvudströmmen** som bryter den helt. Regulatorn säger
bara om pannan ska vara varm. Allt om *vilken* brännare som sitter på väggen
och *vilket* relä som bär beslutet bor här, fritt från Home Assistant, för
ett relä åt fel håll kostar åt båda hållen: ett hus som aldrig blir varmt,
eller en brännare som aldrig stannar.

Tre saker att få exakt rätt:

- **Inställningarna följer brännaren.** Starttid, stoppfördröjning,
  utbrinning, tiden före sotaren, åt vilket håll styrkabeln kallar och
  pannlarmets gräns byts allihop när brännaren byts. `burner_settings` ger
  brännarens standardvärden med det sparade ovanpå, och ett trasigt sparat
  värde faller tillbaka till standard.
- **Vilka reläer som bär ordern.** Den valda vägen, normalt. En sotning tar
  huvudströmmen också, var den än finns. En överhettad panna tar varje relä
  som finns, oavsett väg.
- **Stoppet går genom styrkabeln först.** Med båda reläerna väntar
  huvudströmmen ut utbrinningen, för att dra strömmen på en brännare som
  fortfarande brinner är en annan och värre sak än att be den sluta. `None`
  i planen betyder *rör inte reläet*, vilket inte är samma sak som `False`.

## Testerna väntar sig

Skalet ligger i `src/main/java/se/sodalen/heating/burner/`, ett nytt paket.
Tre records och klassen `Burner` med konstanterna och de fyra fria
funktionerna.

```java
package se.sodalen.heating.burner;

public final class Burner {
    konstanterna BURNER_PELLETS, BURNER_OIL, BURNERS, BURNER_NAMES, PATH_CONTROL, PATH_POWER, PATH_BOTH,
                 CONTROL_PATHS, CALL_NAMES, CONTROL_PATH_NAMES, BURNER_DEFAULTS

    public static BurnerSettings burnerSettings(String burner)                       // stored=None
    public static BurnerSettings burnerSettings(String burner, Map<String, ?> stored)
    public static Relays relaysInUse(String controlPath, boolean hasControl, boolean hasPower)   // sweeping=False, overheated=False
    public static Relays relaysInUse(String controlPath, boolean hasControl, boolean hasPower,
                                     boolean sweeping, boolean overheated)
    public static boolean relayCallsForHeat(String callForHeat, boolean call)
    public static HeatSourcePlan planHeatSource(boolean wanted, double secondsStopped, boolean hasControl,
                                                boolean hasPower, double burnoutSeconds)      // at_once=False
    public static HeatSourcePlan planHeatSource(boolean wanted, double secondsStopped, boolean hasControl,
                                                boolean hasPower, double burnoutSeconds, boolean atOnce)
}

public record BurnerSettings(double startLead, double offDelay, double burnoutSeconds, double sweepLeadSeconds,
                             String callForHeat, double alarmBelow, String controlPath)
public record Relays(boolean control, boolean power)
public record HeatSourcePlan(Boolean control, Boolean power, boolean burningOut)
```

Där Python och Java skiljer sig:

- **Dicten från `burner_settings`** är recorden `BurnerSettings` med sju
  fält: de sex nycklarna i `BURNER_DEFAULTS` i samma ordning, och sist
  `controlPath`, som är `null` när inget `control_path` sparats (det finns
  inte bland standardvärdena). En okänd sparad nyckel har ingen plats i
  recorden och faller bort; ingenting i Pythonkärnan läser en sådan.
- **`stored`** är en `Map<String, ?>` med värden som kan vara `String`,
  `Number`, `Boolean` eller `null`. Pythons `None` för hela dicten är en
  `null`-karta.
- **Tupeln från `relays_in_use`** är recorden `Relays`.
- **`HeatSourcePlan`** har `Boolean` för de två reläerna, som kan vara `null`,
  och `boolean` för `burningOut`. Pythons nyckelordsargument med
  standardvärden (`control=None, power=None, burning_out=False`) skriver du
  ut alla tre vid varje `new HeatSourcePlan(...)`.
- **Nyckelordsargumenten med standardvärden** (`stored=None`,
  `sweeping=False`, `overheated=False`, `at_once=False`) är de korta
  överlagringarna, som anropar de långa.
- **`BURNER_NAMES`, `CALL_NAMES`, `CONTROL_PATH_NAMES`** är `Map<String,
  String>`, `BURNERS` och `CONTROL_PATHS` är `List<String>`, och
  `BURNER_DEFAULTS` är `Map<String, BurnerSettings>`. Alla står färdiga i
  skalet, oföränderliga och i Pythons ordning.

## Ordning

1. `BurnerTest.TheConstants`: konstanterna och båda profilerna.
2. `BurnerTest.TheSettings` och `TheBurnersTimes`: `burnerSettings`.
3. `BurnerTest.TheSupplyInASweep`: `relaysInUse`.
4. `BurnerTest.TheInvertedCable`: `relayCallsForHeat`.
5. `BurnerTest.TheRelays`: `planHeatSource`.
6. `BurnerTest.FromTheCode`: kanterna, skrivna ur Pythonkoden.
7. `SameAsPython`: fyra facitfiler uträknade av Pythonversionen.

## Klurigt

- **`Map.of(...).get(null)` kastar `NullPointerException`**, och
  `getOrDefault(null, ...)` och `List.of(...).contains(null)` likaså. Pythons
  `BURNER_DEFAULTS.get(burner, BURNER_DEFAULTS[BURNER_PELLETS])` ger bara
  standardvärdet när `burner` är `None`. Därför är kartorna i skalet byggda
  som `LinkedHashMap` och låsta med `Collections.unmodifiableMap`: de håller
  Pythons ordning och tål `getOrDefault(null, ...)`. Bygger du en egen
  `List.of(PATH_CONTROL, PATH_BOTH)` för att jämföra vägen, kastar
  `contains(null)`; jämför med `equals` i stället (nästa punkt om vägen).
- **`for key, value in (stored or {}).items()`:** en `null`-karta är en tom.
  Loopa över `stored.entrySet()`. Pythons kedja av `if key in (...)` är en
  `switch` på nyckeln i Java: `case "start_lead" -> ...` för var och en av de
  fem talen, `case "call_for_heat", "control_path" -> ...` för texterna, och
  `default` för en okänd nyckel, som hoppas över.
- **`str(value)`** är `String.valueOf(value)`. **`float(value)`** är tre fall:
  ett `Number` ger `doubleValue()`, ett `Boolean` ger 1.0 eller 0.0 (Pythons
  `float(True)`), en `String` går genom `Double.parseDouble` i en `try` som
  fångar `NumberFormatException`, och `null` hoppas över. Pythons `except
  (TypeError, ValueError): continue` betyder att fältet behåller sitt värde.
- **En record går inte att ändra fält för fält.** Börja med sju lokala
  variabler satta från standardprofilen, skriv över dem i loopen, och gör
  `new BurnerSettings(...)` av dem sist.
- **`control_path in (PATH_CONTROL, PATH_BOTH)`** är
  `PATH_CONTROL.equals(controlPath) || PATH_BOTH.equals(controlPath)`, med
  konstanten först så att en `null` inte kastar. `==` på strängar jämför
  referenser, inte innehåll.
- **`str(call_for_heat).lower() == "on"`** är `"on".equalsIgnoreCase(callForHeat)`,
  som tål `null` (Pythons `str(None)` är `"None"`, som inte är `"on"`). Sedan
  jämförs den booleanen med `call`: `(... ) == call`.
- **`True if has_control else None`** är `hasControl ? Boolean.TRUE : null`.
  Skriv `Boolean.TRUE` och `Boolean.FALSE`, inte `true` och `false`, så syns
  det att fältet har tre värden.
- **Ordningen på if-satserna i `plan_heat_source` spelar roll:** `wanted`
  först, sedan `at_once`, sedan utan styrkabel, sedan utan huvudström, sedan
  utbrinningen med `>=` (en stoppad tid *lika med* utbrinningen bryter), och
  sist väntan med `burningOut` sant.
- **`None` är `null`, inte `false`.** Pythontesternas `assertIs(out.power,
  False)` är `assertEquals(Boolean.FALSE, plan.power())` och
  `assertIsNone` är `assertNull`. Ett relä som ska lämnas i fred får `null`.
- **Facit:** fyra filer. `burner_settings.csv` har det sparade som
  `nyckel=typ:värde` med `|` emellan (`s` text, `f` flyttal, `i` heltal, `b`
  boolean, `n` None), `{}` för en tom dict och tom cell för None;
  `relays_in_use.csv` och `relay_calls.csv` är alla kombinationer;
  `plan_heat_source.csv` har tomma celler där reläet lämnas i fred. Felet
  säger vilket fält som skiljer.
