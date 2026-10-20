# Steg 1: Tider i ord

Räknare som kan dra iväg, som timmar inlärning eller timmar i en tabellruta,
visas i nästa större enhet när de växer: minuter, timmar, dagar, veckor,
månader, år. 311 timmar läser ingen, men "1,9 veckor" säger något. Under tio
visas en decimal, från tio hela tal. Samma regel finns i appen (Swift) och i
kurvkortet (JavaScript).

## Testerna väntar sig

```java
package se.sodalen.heating.helper;

public final class Durations {
    public static String durationWords(Double hours)          // null -> "–"
    public static String durationWordsSeconds(Double seconds)
}
```

## Ordning

1. `TheUnit`, en enhet i taget
2. `TheEdges`: null, negativt, avrundning som hoppar upp en enhet, halvor uppåt
3. `WhateverTheMachinesLanguage`
4. `SameAsPython`: 2 000 fall

## Klurigt

- **Avrundningen:** halvor uppåt, som en människa avrundar. Pythons `round()`
  och Javas `Math.rint` avrundar 1,25 till 1,2.
- **Flyttal:** 1,25 lagras som 1,2499999…, så Pythonkoden lägger på en liten
  knuff (`1e-9`).
- **Byte av enhet efter avrundning:** 59,8 minuter avrundas till 60 och ska då
  sägas som "1 h".
- **Singular:** bara exakt 1 ger "1 dag", "1 vecka" och "1 månad".
- **Datorns språk:** `String.format` skriver med datorns språk. På engelska
  blir decimaltecknet en punkt, i alla enheter.
