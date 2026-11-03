package se.sodalen.heating.house;

import se.sodalen.heating.estimate.MultiRegression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static se.sodalen.heating.helper.Parsing.asFloat;
import static se.sodalen.heating.helper.Parsing.asMapping;

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

    public static SunTerm fromDict(Map<String, ?> data, double horizon) {

        data = asMapping(data);

        SunTerm term = new SunTerm();

        term.fit =  MultiRegression.fromDict(asMapping(data.get("fit")), SIZE, horizon);
        if (data.get("hour") instanceof List<?> cells && cells.size() == 4 && cells.stream().allMatch(o -> finite(o) != null)) {
            term.hour = cells.stream().mapToDouble(o -> asFloat(o, 0.0)).toArray();
        }

        if (term.fit.weight() > 0.0) {
            term.hours = (int) asFloat(data.get("hours"), 0.0);
            term.sunnyHours = (int) asFloat(data.get("sunny_hours"), 0.0);
        }

        return term;
    }                   // fit via MultiRegression.fromDict(…, SIZE, horizon); hour bara som fyra ändliga tal; timmarna bara om fit.weight() > 0

    private static Double finite(Object value) {
        double result;
            result = asFloat(value, Double.NaN);

        return Double.isFinite(result) ? result : null;
    }

    private boolean ready() {
        return hours >= MIN_HOURS && sunnyHours >= MIN_SUNNY_HOURS && fit.coefficients() != null;
    }

    public void observe(Double demand, Double light, Double error, Double dt) {

        demand = finite(demand);
        light = finite(light);
        error = finite(error);
        dt = finite(dt);

        if (demand == null || light == null || error == null || dt == null || dt <= 0.0) {
            return;
        }

        double[] hour = this.hour;

        hour[0] += dt;
        hour[1] += demand * dt;
        hour[2] += Math.max(0.0, light) * dt;
        hour[3] += error * dt;
        if (hour[0] < HOUR_SECONDS) {
            return;
        }

        double seconds = hour[0];
        demand = hour[1] / seconds;
        light = hour[2] / seconds;
        error = hour[3] / seconds;

        fit.add(new double[]{1.0, demand, light, light * demand}, error, seconds);
        this.hour = new double[]{0.0, 0.0, 0.0, 0.0};
        hours += 1;

        if (light > SUNNY)
            sunnyHours += 1;

    }                   // en cykel som säger något om kurvan sånär som på solen; null/NaN/oändligt/dt <= 0 → inget

    public Double perKw(double demand) {

        if (!ready()) {
            return null;
        }

        double[] beta = fit.coefficients();

        return beta[LIGHT] + beta[LIGHT_DEMAND] * demand;
    }                   // K inomhusfel per kW/m2 hållet ljus vid detta behov; null tills den svarar (_ready)

    public Double spread(double demand) {
        if (!ready()) {
            return null;
        }

        Double whole = fit.variance(new double[]{0.0, 0.0, 1.0, demand});
        double sigma = fit.sigma();

        if (whole == null || !Double.isFinite(sigma)) {
            return null;
        }

        return Math.sqrt(Math.max(whole - sigma * sigma, 0.0));
    }                   // standardfelet i perKw: roten ur variansen vid (0, 0, 1, demand) minus sigma i kvadrat

    public Double share(double demand) {

        Double worth = perKw(demand);
        Double spread = spread(demand);

        if (worth == null || spread == null || worth == 0.0) {
            return null;
        }

        return spread / Math.abs(worth);
    }                   // osäkerheten som andel av solens värde; null tills den svarar eller när värdet är noll

    public boolean known(double demand, double light, double limit) {

        Double spread = spread(demand);

        if (spread == null) {
            return false;
        }

        return light <= 0.0 || light * spread <= limit;
    }                   // aldrig innan termen svarat; sedan light <= 0 eller light * spread <= limit

    public double correction(double demand, double light) {

        Double worth = perKw(demand);

        return worth == null || light <= 0.0 ? 0.0 : worth * light;
    }                   // K av inomhusfelet som är solens: worth * light; noll tills den svarar och utan ljus

    public Map<String, Object> asDict() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fit", fit.asDict());
        result.put("hour", Arrays.stream(hour).boxed().collect(Collectors.toList()));
        result.put("hours", hours);
        result.put("sunny_hours", sunnyHours);

        return result;
    }                   // {"fit": fit.asDict(), "hour": kopia som List<Double>, "hours", "sunny_hours"}
}
