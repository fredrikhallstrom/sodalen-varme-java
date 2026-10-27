package se.sodalen.heating.house;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import se.sodalen.heating.estimate.MultiRegression;
import se.sodalen.heating.estimate.Prior;

// Fysiken i thermal.py: konstanterna och funktionerna som står utanför klassen HouseModel.
//
//     dT/dt = q + s*I + h*delta - (g0 + gv*v + gb*v*cos(dir) + gc*v*sin(dir)) * (T_in - T_out)
//
// Nio parametrar, linjära allihop, så en MultiRegression lär sig hela huset.
public final class Thermal {

    // 1, -dT, -v*dT, -v*cos*dT, -v*sin*dT, sol, delta, cos(dygn), sin(dygn)
    public static final int REGRESSORS = 9;
    public static final int LOSS = 1;             // koefficienten för -(inne - ute), K/h per K
    public static final int WIND = 2;             // för -vind * (inne - ute)
    public static final int WIND_COS = 3;         // samma, gånger cos(riktning)
    public static final int WIND_SIN = 4;         // samma, gånger sin(riktning)
    public static final int SUN_INDEX = 5;        // solens plats bland regressorerna
    public static final int RADIATORS = 6;        // för deltat tillopp/retur

    // Det anpassningen får veta i stället för att lära sig: en tidskonstant kring sextio timmar och
    // radiatorer som precis håller huset vid kretsens designpunkt, var sak värd ett halvt dygn data.
    public static final double TIME_CONSTANT_HOURS = 60.0;
    public static final double PRIOR_HOURS = 12.0;
    public static final double LOSS_SPREAD = 5.0;         // K av skillnaden inne-ute
    public static final double RADIATOR_SPREAD = 3.0;     // K av deltat tillopp/retur
    public static final double WIND_SPREAD = 30.0;        // m/s gånger K
    public static final double WIND_SHARE_PER_MS = 0.05;  // vinden lägger ~5 % på förlusten per m/s
    public static final double LOSS_FLOOR = 1.0 / 500.0;  // ett hus som förlorar mindre är inget hus
    public static final Map<Integer, Double> HOUSE_FLOORS = Map.of(LOSS, LOSS_FLOOR, WIND, 0.0, RADIATORS, 0.0);

    // Balansen: samma fysik löst för skillnaden inne-ute, över hela dygn (HouseModel.balance).
    public static final int STEADY_REGRESSORS = 7;
    public static final int STEADY_GAINS = 0;      // q/g0: K av skillnad som husets egen värme håller
    public static final int STEADY_RADIATORS = 1;  // h/g0: K per K delta
    public static final int STEADY_SUN = 2;        // s/g0: K per enhet sol
    public static final int STEADY_PACE = 3;       // 1/g0: timmar, gånger husets svalnande (-dT/dt)
    public static final int STEADY_WIND = 4;       // gv/g0, och riktningens två efter den
    public static final Map<Integer, Double> STEADY_FLOORS = Map.of(
            STEADY_GAINS, 0.0, STEADY_RADIATORS, 0.0, STEADY_SUN, 0.0, STEADY_PACE, 0.0, STEADY_WIND, 0.0);
    public static final double DAY_SECONDS = 24.0 * 3600.0;
    public static final double STEADY_EVERY_SECONDS = 3.0 * 3600.0;   // ett prov var tredje timme

    // Nattens balans: ett prov per natt, minst sex timmar, kapat vid tolv (HouseModel.nightBalance).
    public static final int NIGHT_REGRESSORS = 6;
    public static final int NIGHT_GAINS = 0;
    public static final int NIGHT_RADIATORS = 1;
    public static final int NIGHT_PACE = 2;
    public static final int NIGHT_WIND = 3;
    public static final Map<Integer, Double> NIGHT_FLOORS = Map.of(
            NIGHT_GAINS, 0.0, NIGHT_RADIATORS, 0.0, NIGHT_PACE, 0.0, NIGHT_WIND, 0.0);
    public static final double NIGHT_MIN_SECONDS = 6.0 * 3600.0;
    public static final int NIGHT_MIN_NIGHTS = NIGHT_REGRESSORS + 3;   // nätter innan den svarar
    public static final double NIGHT_MAX_SECONDS = 12.0 * 3600.0;
    public static final double NIGHT_PRIOR_SAMPLES = 2.0;
    public static final Map<Integer, Double> NIGHT_SPREAD = Map.of(
            NIGHT_RADIATORS, 10.0, NIGHT_PACE, 0.05, NIGHT_WIND, 30.0, NIGHT_WIND + 1, 30.0, NIGHT_WIND + 2, 30.0);

    // Solen är ljuset på fönstren i kW/m2 (sunlight.py); så här mycket är en enhet av det gamla
    // solindexet på Sodalens fönster, och solprioriteten till balansen räknas i den enheten.
    public static final double SUNLIGHT_PER_INDEX = 0.94;
    public static final double STEADY_SUN_STRENGTH = 24.0 * Math.pow(0.1 * SUNLIGHT_PER_INDEX, 2);
    public static final int WINDOW_SUN = 2;        // solens plats i intervallets och dygnets summor

    public static final double DEFAULT_HORIZON_SECONDS = 21.0 * 24.0 * 3600.0;   // tre veckor
    public static final Map<Integer, Prior> DEFAULT_PRIORS = housePriors(39.0, 10.0, 1800.0);   // 21 mot -18, delta 10
    public static final double STEP_HOURS = 0.25;  // outlook går en kvart i taget

    private Thermal() {
    }

    public static double solarIndex(Double elevation, Double cloud) {

        if (elevation == null || elevation <= 0.0) {
            return 0.0;
        }
        double through = 1.0;
        if (cloud != null) {
            through = Math.clamp(1.0 - cloud / 100, 0.0, 1.0);
            through = 0.15 + 0.85 * through;
        }

        return Math.sin(Math.toRadians(Math.min(90.0, elevation))) * through;
    }                   // solens höjd gånger hur mycket som kommer igenom molnen; ljuset på plan mark

    public static double[] regressors(double indoor, double outdoor, Double wind, Double bearing,
                                      double sun, double delta, Double hour) {

        double difference = indoor - outdoor;
        double speed = Math.max(0.0, wind == null ? 0.0 : wind);
        double angle = Math.toRadians(bearing == null ? 0.0 : bearing);

        Double daily = hour != null ? 2.0 * Math.PI * (hour / 24) : null;


        return new double[]{
                1.0,
                -difference,
                -speed * difference,
                -speed * Math.cos(angle) * difference,
                -speed * Math.sin(angle) * difference,
                sun,
                Math.max(0.0, delta),
                daily == null ? 0.0 : Math.cos(daily),
                daily == null ? 0.0 : Math.sin(daily)
        };
    }                   // en rad i anpassningen, de nio kolumnerna

    public static Map<Integer, Prior> housePriors(double designSpan, double designDelta, double sampleSeconds) {

        double samples = PRIOR_HOURS * 3600 / Math.max(1.0, sampleSeconds);
        double loss = 1.0 / TIME_CONSTANT_HOURS;
        double windStrength = samples * Math.pow(WIND_SPREAD, 2);
        return new HashMap<Integer, Prior>(Map.of(
                LOSS, new Prior(loss, samples * Math.pow(LOSS_SPREAD, 2)),
                WIND, new Prior(loss * WIND_SHARE_PER_MS, windStrength),
                WIND_COS, new Prior(0.0, windStrength),
                WIND_SIN, new Prior(0.0, windStrength),
                RADIATORS, new Prior(loss * designSpan / Math.max(1.0, designDelta),
                        samples * Math.pow(RADIATOR_SPREAD, 2))
        ));
    }                   // fysiken anpassningen får veta, för den här kretsens designpunkt

    public static double[] steadyRegressors(double sun, double delta, double slope, double[] wind) {
        return new double[]{
                1.0,
                Math.max(0.0, delta),
                sun,
                -slope,
                -wind[0],
                -wind[1],
                -wind[2]

        };
    }                   // balansens rad: 1, delta, sol, -lutning, -vindens tre

    public static Double radiatorExcess(Double flow, Double ret, Double indoor) {

        if ( flow == null || ret == null || indoor == null) {
            return null;
        }

        return Math.pow(Math.max(0.0, 0.5 * (flow + ret) - indoor), 1.3);
    }                   // radiatorernas medelvatten över rummet upphöjt till 1,3; null utan avläsning

    public static double[] nightRegressors(double heat, double slope, double[] wind) {
        return new double[] {
                1.0,
                Math.max(0.0, heat),
                -slope,
                -wind[0],
                -wind[1],
                -wind[2]
        };
    }                   // nattens rad: 1, värme, -lutning, -vindens tre

    private static MultiRegression createFit(int regressorType,
                                      double horizonSeconds,
                                      Map<Integer, Prior> priors,
                                      Map<Integer, Double> floors) {

        MultiRegression fitAnswer = new MultiRegression(regressorType, horizonSeconds);
        fitAnswer.configure(priors, floors);
        return fitAnswer;
    }

    public static MultiRegression houseFit(double horizonSeconds) {

        return createFit(REGRESSORS, horizonSeconds, DEFAULT_PRIORS, HOUSE_FLOORS);
    }                   // ny anpassning med REGRESSORS, DEFAULT_PRIORS och HOUSE_FLOORS

    public static MultiRegression steadyFit(double horizonSeconds) {

        return createFit(STEADY_REGRESSORS, horizonSeconds, Map.of(), STEADY_FLOORS);
    }                   // STEADY_REGRESSORS, inga priorer, STEADY_FLOORS

    public static MultiRegression nightFit(double horizonSeconds) {
        return createFit(NIGHT_REGRESSORS, horizonSeconds, Map.of(), NIGHT_FLOORS);
    }                   // NIGHT_REGRESSORS, inga priorer, NIGHT_FLOORS

    public static Conditions weatherAt(List<Forecast> weather, double hours) {
        Conditions chosen = weather.getFirst().conditions();
        for (Forecast forecast : weather) {
            if (forecast.hours() > hours) {
                break;
            }
            chosen = forecast.conditions();

        }
        return chosen;
    }                   // prognosen närmast så här många timmar fram; den sista hålls kvar förbi slutet
}
