package se.sodalen.heating.house;

// Vad modellen tror händer om värmen förblir av.
//   hours        timmar tills huset når golvet, eller null om det håller hela horisonten
//   holds        true när svaret är "det håller", inte "det tar slut vid t"
//   uncertainty  hur långt modellen sträcker sig, som standardavvikelse i K/h; null när den inte vet
//   known        false när anpassningen inte har nog för att säga något alls
//   turnsAt      timmar tills huset slutar svalna av sig självt, ännu över golvet; null om det inte
//                hinner hända före golvet eller inom horisonten
public record Outlook(Double hours, boolean holds, Double uncertainty, boolean known, Double turnsAt) {
}
