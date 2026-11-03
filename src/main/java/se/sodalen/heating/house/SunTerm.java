package se.sodalen.heating.house;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import se.sodalen.heating.estimate.MultiRegression;

// Solens del av inomhusfelet, lärd (2026-10-18): error = a + b demand + (c + d demand) light, på
// timmedel. Kurvan lär sig av felet minus den här termen där den är känd nog, i solen också.
public final class SunTerm {

    // Regressorerna: 1, demand, light, light x demand.
    public static final int SIZE = 4;
    public static final int LIGHT = 2;
    public static final int LIGHT_DEMAND = 3;
    public static final double HOUR_SECONDS = 3600.0;
    public static final double HORIZON_SECONDS = 21.0 * 24.0 * 3600.0;
    // Två dygn av timmar innan den svarar, och några av dem med solen uppe: en anpassning som
    // aldrig sett solen vet inget om den, hur säker resten än är.
    public static final int MIN_HOURS = 48;
    public static final int MIN_SUNNY_HOURS = 6;
    public static final double SUNNY = 0.02;             // kW/m2 i en timmes medelljus

    public MultiRegression fit = new MultiRegression(SIZE, HORIZON_SECONDS);
    // Sekunder, och sekunder gånger demand, light och error, för timmen som pågår.
    public double[] hour = {0.0, 0.0, 0.0, 0.0};
    public int hours = 0;
    public int sunnyHours = 0;

    public SunTerm() {
    }

    public void observe(Double demand, Double light, Double error, Double dt) {
    }                   // en cykel som säger något om kurvan sånär som på solen; null/NaN/oändligt/dt <= 0 → inget

    public Double perKw(double demand) {
        return null;
    }                   // K inomhusfel per kW/m2 hållet ljus vid detta behov; null tills den svarar (_ready)

    public Double spread(double demand) {
        return null;
    }                   // standardfelet i perKw: roten ur variansen vid (0, 0, 1, demand) minus sigma i kvadrat

    public Double share(double demand) {
        return null;
    }                   // osäkerheten som andel av solens värde; null tills den svarar eller när värdet är noll

    public boolean known(double demand, double light, double limit) {
        return false;
    }                   // aldrig innan termen svarat; sedan light <= 0 eller light * spread <= limit

    public double correction(double demand, double light) {
        return 0.0;
    }                   // K av inomhusfelet som är solens: worth * light; noll tills den svarar och utan ljus

    public Map<String, Object> asDict() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fit", fit.asDict());
        result.put("hour", List.copyOf(List.of(hour)));
        result.put("hours", hours);
        result.put("sunny_hours", sunnyHours);

        return result;
    }                   // {"fit": fit.asDict(), "hour": kopia som List<Double>, "hours", "sunny_hours"}

    public static SunTerm fromDict(Map<String, ?> data, double horizon) {
        return null;
    }                   // fit via MultiRegression.fromDict(…, SIZE, horizon); hour bara som fyra ändliga tal; timmarna bara om fit.weight() > 0
}
