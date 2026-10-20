# Steg 2: PID, den inre loopen

Tilloppets fel in, ventilläge ut. Var 30:e sekund jämför regulatorn tilloppet
med börvärdet och säger vilket läge shuntventilen ska stå i. Svaret är ett
**läge** ("62 %"), inte en knuff ("2 % mer"). Det tål då ett missat varv,
eftersom samma fråga ger samma svar.

Fyra delar:

- **P**: felet gånger `kp`.
- **I**: integralen samlar upp felet över tid (`kp / ti * fel * dt`). Den
  flyttar ventilen sakta dit där felet blir noll.
- **D**: räknas på mätvärdets ändring, inte felets. När börvärdet hoppar
  (sparläge, nattsänkning) ska ventilen inte få en spark.
- **Dödband**: ett litet fel krymps med bandets bredd i stället för att
  nollas, så att svaret blir mjukt.

**Anti-windup** behövs när ventilen står i ändläget (0 eller 100 %). Då
flyttas integralen dit den hade behövt vara, i stället för att fortsätta
växa. Annars dröjer det länge innan ventilen släpper från ändläget när felet
byter tecken.

## Testerna väntar sig

```java
package se.sodalen.heating.pid;

public record PidGains(double kp, double ti, double td, double deadband, double outMin, double outMax)
    // plus en konstruktor PidGains(kp, ti, td): dödband 0, gränser 0-100

public record PidResult(double output, double integral, double error,
                        double proportional, double derivative, boolean saturated)

public final class Pid {
    public static PidResult step(double setpoint, double measurement, Double lastMeasurement,
                                 double integral, double dt, PidGains gains)
    public static double bumplessIntegral(double position, double proportional, double derivative)
    public static double bumplessIntegral(double position)
}
```

`lastMeasurement` är `null` första varvet. Då finns ingen derivata.
