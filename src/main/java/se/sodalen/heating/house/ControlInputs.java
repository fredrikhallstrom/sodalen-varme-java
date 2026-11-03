package se.sodalen.heating.house;

import java.util.ArrayList;
import java.util.List;

// En cykels mätningar. Publika fält i Pythons ordning med Pythons standardvärden; bara dt måste
// anges. Home Assistant-sidan fyller i en sådan här, tar en ControlOutput ut och sparar LearnedState.
public class ControlInputs {

    public double dt;
    public Double flow = null;
    public Double ret = null;
    public Double outdoor = null;
    public List<SensorReading> indoor = new ArrayList<>();
    public boolean overrideActive = false;
    public Double position = null;              // ventilläget Shellyn rapporterar, om den vet ett
    public boolean targetChanged = false;
    public boolean heatingEnabled = true;
    public boolean hotWater = false;            // pannan gör hushållets varmvatten: går före varje skäl att stanna
    public Double wind = null;
    public Double windBearing = null;
    public Double sunElevation = null;
    public Double cloud = null;
    public Double sunlight = null;              // solen på väggarna nu, kW/m2; null utan väggar i konfigurationen
    public Double hour = null;                  // lokal timme 0-24: hushållet är en värmekälla med dygnsrytm
    public List<Forecast> forecast = new ArrayList<>();
    public Double boiler = null;                // pannans egen temperatur, för ventilkartan
    public boolean boilerFailing = false;       // pannvakten säger att pannan sviktar: ventilens förstärkning lärs inte
    public boolean sweeping = false;            // brännaren hålls av för sotaren: går före varmvattnet också
    public String emergency = Model.EMERGENCY_OFF;
    public Double sinceFailsafe = null;         // sekunder sedan Shellyns vakthund senast lämnade tillbaka ventilen

    public ControlInputs(double dt) {
        this.dt = dt;
    }                   // Pythons enda fält utan standardvärde
}
