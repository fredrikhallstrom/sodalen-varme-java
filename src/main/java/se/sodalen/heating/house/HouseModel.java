package se.sodalen.heating.house;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import se.sodalen.heating.estimate.MultiRegression;
import se.sodalen.heating.estimate.Prior;

// Byggnaden, anpassad efter sitt eget beteende: hur fort den svalnar när ingen värmer den, hur

// mycket vinden och solen gör, och hur säker den är på det. Tre anpassningar: driften (nio

// regressorer), balansen över hela dygn (sju) och nattens balans (sex).
public final class HouseModel {

    private final MultiRegression fit;
    private final MultiRegression steadyFit;
    private final MultiRegression nightFit;
    private double secondsSinceSample = 0.0;
    private Double lastIndoor;
    private double[] window;
    private List<double[]> day;
    private double[] dayTotals;
    private double[] stretch;
    private double observedHours;
    private double sinceSteady;
    private boolean lossOnFloor;

    public HouseModel() {
        this.fit = Thermal.houseFit(Thermal.DEFAULT_HORIZON_SECONDS);
        this.steadyFit = Thermal.steadyFit(Thermal.DEFAULT_HORIZON_SECONDS);
        this.nightFit = Thermal.nightFit(Thermal.DEFAULT_HORIZON_SECONDS);
        this.window = new double[7];
        this.day = new ArrayList<>();
        this.dayTotals = new double[7];
        this.stretch = new double[7];
    }                   // houseFit, steadyFit och nightFit med DEFAULT_HORIZON_SECONDS; allt annat tomt

    public MultiRegression fit() {
        return fit;
    }                   // driften: K/h ur de nio regressorerna

    public MultiRegression steady() {
        return steadyFit;
    }                   // balansen: samma prov, lösta för skillnaden inne-ute

    public MultiRegression night() {
        return nightFit;
    }                   // nätterna

    public double[] window() {
        return window;
    }                   // intervallets sju summor: sekunder, och delta, sol, skillnad, tre vindtermer gånger sekunder

    public List<double[]> day() {
        return day;
    }                   // det senaste dygnets intervall, åtta tal vart: de sju summorna och inne vid starten

    public double[] dayTotals() {
        return dayTotals;
    }                   // dygnets summor, hållna löpande

    public double sinceSteady() {
        return sinceSteady;
    }                   // sekunder sedan balansen senast fick dygnets medel

    public double[] stretch() {
        return stretch;
    }                   // natten som pågår: sex summor och inne när den började

    public Double lastIndoor() {
        return lastIndoor;
    }                   // innetemperaturen vid senaste provet, för lutningen

    public double secondsSinceSample() {
        return secondsSinceSample;
    }

    public double observedHours() {
        return observedHours;
    }                   // klocktimmar som de tagna proven täcker

    public void retune(double horizonSeconds) {
        fit.setHorizon(horizonSeconds);
        steady().setHorizon(horizonSeconds);
        night().setHorizon(horizonSeconds);
    }                   // alla tre anpassningarnas horisont

    public void setDesign(double designSpan, double designDelta, double sampleSeconds) {
        fit.configure(Thermal.housePriors(designSpan, designDelta, sampleSeconds), Thermal.HOUSE_FLOORS);
    }                   // kretsens designpunkt och provintervall, som priorerna beror på

    public boolean lossOnFloor() {
        double[] beta = fit.coefficients();
        return beta != null && beta[Thermal.LOSS] <= Thermal.LOSS_FLOOR + 1e-9;
    }                   // om väggförlusten ligger på sitt golv: en sådan anpassning litar man inte på

    public boolean observe(Double indoor, Conditions conditions, double delta, double dt, double sampleSeconds) {
        return observe(indoor, conditions, delta, dt, sampleSeconds, null, null);
    }                   // utan radiatorer och natt

    public boolean observe(Double indoor, Conditions conditions, double delta, double dt, double sampleSeconds,
                           Double radiators, Boolean night) {

        secondsSinceSample += Math.max(0.0, dt);

        if (indoor == null || conditions == null) {
            lastIndoor = null;
            secondsSinceSample = 0.0;
            window = new double[7];
            day = new ArrayList<double[]>();
            dayTotals = new double[7];
            stretch = new double[7];
            return false;
        }

        double span = Math.max(0.0, dt);
        double difference = indoor - conditions.outdoor();
        double speed = Math.max(0.0, conditions.wind() == null ? 0.0 : conditions.wind());
        double angle = Math.toRadians(conditions.bearing() == null ? 0.0 : conditions.bearing());
        double heat = radiators == null ? Math.max(0.0, delta) : Math.max(0.0, radiators);

        double[] terms = {
                1.0,
                heat,
                conditions.sun(),
                difference,
                speed * difference,
                speed * Math.cos(angle) * difference,
                speed * Math.sin(angle) * difference
        };
        for (int index = 0; index < terms.length; index++) {
            window[index] += terms[index] * span;
        }

        addToStretch(
                night != null && night,
                indoor,
                span,
                heat,
                difference,
                speed,
                angle);


        if (secondsSinceSample < sampleSeconds) {
            if (lastIndoor == null) {
                lastIndoor = indoor;
            }
            return false;
        }

        double elapsed = secondsSinceSample;
        Double previous = lastIndoor;
        lastIndoor = indoor;
        secondsSinceSample = 0.0;

        if (previous == null || elapsed <= 0.0) {
            return false;
        }

        double slope = (indoor - previous) * 3600 / elapsed;
        double[] row = Thermal.regressors(
                previous,
                conditions.outdoor(),
                conditions.wind(),
                conditions.bearing(),
                conditions.sun(),
                delta,
                conditions.hour()
        );

        fit.add(row, slope, elapsed);
        addToDay(previous, indoor, elapsed);
        observedHours += elapsed / 3600;
        return true;
    }                   // erbjud modellen en cykel; true när den tog ett prov

    private void addToStretch(boolean night, double indoor, double span, double heat,
                              double difference, double speed, double angle) {

        if (night && stretch[0] <= 0.0) {
            stretch = new double[7];
            stretch[6] = indoor;
        }

        if (night) {
            double[] terms = {
                    1.0,
                    heat,
                    difference,
                    speed * difference,
                    speed * Math.cos(angle) * difference,
                    speed * Math.sin(angle) * difference,
            };

            for (int i = 0; i < terms.length; i++) {
                stretch[i] += terms[i] * span;
            }
        }

        if (stretch[0] > 0.0 && (!night || stretch[0] >= Thermal.NIGHT_MAX_SECONDS)) {
            double[] sums = Arrays.copyOf(stretch, 7);
            stretch = new double[7];
            if (sums[0] >= Thermal.NIGHT_MIN_SECONDS) {
                double seconds = sums[0];
                double[] mean = new double[5];
                for (int i = 0; i < 5; i++) {
                    mean[i] = sums[i + 1] / seconds;
                }
                night().add(Thermal.nightRegressors(
                                mean[0],
                                (indoor - sums[6]) * 3600 / seconds,
                                new double[]{mean[2], mean[3], mean[4]}),
                        mean[1],
                        seconds
                );
            }

            if (night) {
                stretch = new double[7];
                stretch[6] = indoor;
            }
        }
    }


    private void addToDay(double start, double end, double elapsed) {
        double[] sums = Arrays.copyOf(window, 8);
        window = new double[7];
        if (sums[0] <= 0.0) {
            return;
        }

        sums[7] = start;
        day.add(sums);

        double[] totals = new double[dayTotals.length];
        for (int i = 0; i < dayTotals.length; i++) {
            totals[i] = dayTotals[i] + sums[i];
        }

        while (!day.isEmpty() && (totals[0] - day.getFirst()[0]) >= Thermal.DAY_SECONDS) {
            double[] gone = day.removeFirst();

            for (int i = 0; i < totals.length; i++) {
                totals[i] -= gone[i];
            }
        }
        dayTotals = totals;

        if (totals[0] < Thermal.DAY_SECONDS) {
            return;
        }

        sinceSteady += elapsed;
        if (sinceSteady < Thermal.STEADY_EVERY_SECONDS) {
            return;
        }

        double weight = sinceSteady;
        sinceSteady = 0.0;

        double total = totals[0];

        double[] mean = new double[6];
        for (int i = 1; i < 7; i++) {
            mean[i - 1] = totals[i] / total;
        }

        double slope = (end - day.getFirst()[7]) * 3600.0 / total;

        steady().add(
                Thermal.steadyRegressors(
                        mean[1],
                        mean[0],
                        slope,
                        new double[]{mean[3], mean[4], mean[5]}),
                mean[2],
                weight
        );
    }

    public Double drift(double indoor, Conditions conditions) {
        return fit.predict(row(indoor, conditions));
    }                   // K/h med radiatorerna av

    public Double driftUncertainty(double indoor, Conditions conditions) {
        return fit.uncertainty(row(indoor, conditions));
    }

    private double[] row(double indoor, Conditions conditions) {
        return Thermal.regressors(indoor, conditions.outdoor(), conditions.wind(), conditions.bearing(),
                conditions.sun(), 0.0, conditions.hour());
    }

    public Double reach(double indoor, Conditions conditions) {
        return fit.reach(row(indoor, conditions));
    }                   // hur långt utanför sin data frågan är

    public Balance balance(double indoor) {

        double[] drift = fit.coefficients();

        Map<Integer, Prior> priors = new HashMap<>();

        if (drift != null && drift[Thermal.LOSS] > 0.0) {
            priors.put(Thermal.STEADY_SUN, new Prior(
                    Math.max(0.0, drift[Thermal.SUN_INDEX]) / drift[Thermal.LOSS],
                    Thermal.STEADY_SUN_STRENGTH
            ));
        }

        steady().configure(priors, Thermal.STEADY_FLOORS);
        double[] beta = steady().coefficients();
        if (beta == null) {
            return null;
        }

        double[] at = new double[]{1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
        Double reach = steady().reach(at);
        Double sigma = steady().inSampleSigma();

        if (reach == null || !Double.isFinite(sigma)) {
            return null;
        }

        double leverage = Math.max(0.0, Math.pow(reach + 1.0, 2) - 1);
        return new Balance(indoor - beta[Thermal.STEADY_GAINS], sigma * Math.sqrt(leverage));
    }                   // utetemperaturen där huset håller sig självt; null när anpassningen inte kan säga

    public Balance nightBalance(double indoor) {
        double[] steady = steadyFit.coefficients();

        Map<Integer, Prior> priors = new HashMap<>();

        if (steady != null) {
            int[][] pairs = {
                    {Thermal.NIGHT_RADIATORS, Thermal.STEADY_RADIATORS},
                    {Thermal.NIGHT_PACE, Thermal.STEADY_PACE},
                    {Thermal.NIGHT_WIND, Thermal.STEADY_WIND},
                    {Thermal.NIGHT_WIND + 1, Thermal.STEADY_WIND + 1},
                    {Thermal.NIGHT_WIND + 2, Thermal.STEADY_WIND + 2}
            };

            for (int[] pair : pairs) {
                int index = pair[0];
                int at = pair[1];
                priors.put(index, new Prior(steady[at], Thermal.NIGHT_PRIOR_SAMPLES * Math.pow(Thermal.NIGHT_SPREAD.get(index), 2)));
            }
        }
        night().configure(priors, Thermal.NIGHT_FLOORS);
        double[] beta = night().coefficients();

        if (beta == null || night().weight() < Thermal.NIGHT_MIN_NIGHTS) {
            return null;
        }

        Double reach = night().reach(new double[] {1.0, 0.0, 0.0, 0.0, 0.0, 0.0});
        double sigma = night().inSampleSigma();

        if (reach == null || !Double.isFinite(sigma)) {
            return null;
        }

        double leverage = Math.max(0.0, Math.pow((reach + 1.0), 2) - 1);
        return new Balance(indoor - beta[Thermal.NIGHT_GAINS], sigma * Math.sqrt(leverage));

    }                   // nattens balans; null tills nätterna kan säga

    public Boolean coolsWhenLeftAlone() {
        for (double wind = 0.0; wind <= 10.0; wind += 5.0) {
            for (int bearing = 0; bearing <= 360; bearing += 45) {
                Double drift = drift(
                        21.0,
                        new Conditions(-5.0,
                                wind,
                                (double) bearing,
                                0.0,
                                null)
                );

                if (drift == null) {
                    return null;
                }

                if (drift >= 0.0) {
                    return false;
                }

                if (wind == 0.0) {
                    break;
                }
            }
        }

        return true;
    }                   // det enda en husmodell måste tro: kallt, mörkt och stilla svalnar huset

    public Outlook outlook(double indoor, double floor, List<Forecast> weather, double confidence) {
        return outlook(indoor, floor, weather, confidence, Thermal.STEP_HOURS);
    }                   // med STEP_HOURS

    public Outlook outlook(double indoor, double floor, List<Forecast> weather, double confidence, double stepHours) {

        if (weather == null || weather.isEmpty()) {
            return new Outlook(null, false, null, false, null);
        }

        double temperature = indoor;
        double margin = 0.0;
        double worstUncertainty = 0.0;
        double horizon = weather.getLast().hours();
        double elapsed = 0.0;

        Double startHour = weather.getFirst().conditions().hour() == null ? null : (weather.getFirst().conditions().hour() - ((weather.getFirst().hours() % 24.0 + 24) % 24));

        Double turnsAt = null;
        while (elapsed < horizon) {
            Conditions conditions = Thermal.weatherAt(weather, elapsed);
            if (startHour != null) {
                conditions = conditions.withHour(((startHour + elapsed) % 24 + 24) % 24);
            }

            Double slope = drift(temperature, conditions);
            Double spread = driftUncertainty(temperature, conditions);
            if (slope == null || spread == null) {
                return new Outlook(null, false, null, false, null);
            }

            worstUncertainty = Math.max(worstUncertainty, spread);

            if (turnsAt == null && slope >= 0.0) {
                turnsAt = elapsed;
            }

            double before = temperature - margin;
            temperature += slope * stepHours;
            margin += spread * stepHours * confidence;
            double after = temperature - margin;

            if (after <= floor) {
                double fall = before - after;
                double share = fall <= 0.0 ? 1.0 : Math.clamp((before - floor) / fall, 0.0, 1.0);

                return new Outlook(
                        elapsed + stepHours * share,
                        false,
                        worstUncertainty,
                        true,
                        turnsAt
                );
            }
            elapsed += stepHours;
        }

        return new Outlook(
                horizon,
                true,
                worstUncertainty,
                true,
                turnsAt
        );
    }                   // hur länge huset håller sig över golvet med värmen av
}
