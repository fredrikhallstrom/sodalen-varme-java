package se.sodalen.heating.estimate;

import java.util.LinkedHashMap;
import java.util.Map;

import static se.sodalen.heating.helper.Parsing.*;

// Ett medelvärde som följer sina prov med en tidskonstant, efter den tid som faktiskt gick.
public final class Ewma {
    double seconds;
    Double value;

    public Ewma(double seconds) {
        this.seconds = seconds;
    }                   // inget värde än

    public Ewma(double seconds, Double value) {
        this.seconds = seconds;
        this.value = value;
    }                   // börjar på value, som kan vara null

    public double seconds() {
        return seconds;
    }                   // tidskonstanten

    public Double value() {
        if (value == null)
            return null;
        return value;
    }                   // null tills första provet

    public Map<String, Double> asDict() {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("seconds", seconds);
        result.put("value", value);
        return result;
    }

    public static Ewma fromDict(Map<String, ?> data, double seconds) {
        data = asMapping(data);
        Ewma ewma = new Ewma(seconds);
                ewma.value = asOptionalFloat(data.get("value"));

        return ewma;
    }

    public Double update(Double sample, double dt) {
        if (sample == null)
            return value;
        if (value == null || seconds <= 0.0 || dt <= 0.0) {
            value = sample;
            return value;
        }

        double ratio = dt / seconds;
        double alpha = ratio < 30 ? 1.0 - Math.pow(Math.E, -ratio) : 1.0;
        value += alpha * (sample - value);
        return value;
    }// det nya värdet; null som prov lämnar det som det var

    public void setSeconds(double seconds) {
        this.seconds = seconds;
    }
}

