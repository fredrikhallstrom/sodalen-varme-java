package se.sodalen.heating.forecast;

import java.util.List;

import se.sodalen.heating.house.Forecast;

// Det forecast.py säger om den hämtade prognosen, alltså steg 7:s lista av luckor.
public final class Forecasts {

    // Så långt fram den första prognostimmen får ligga och ändå få stå in för nu.
    public static final double STAND_IN_HOURS = 1.5;

    private Forecasts() {
    }

    public static Double outdoorFromForecast(List<Forecast> forecast) {
        return null;
    }                   // prognosens temperatur för nu, i stället för en utegivare som saknas; null utan svar
}
