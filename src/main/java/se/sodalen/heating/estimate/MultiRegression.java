package se.sodalen.heating.estimate;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static se.sodalen.heating.helper.Parsing.*;

// Minsta kvadrat i flera variabler, med glömska, förkunskap, golv och en ärlig uppfattning om hur säker den är.

public final class MultiRegression {
    public static final double RIDGE = 1e-6;     // läggs på diagonalen, gånger vikten
    private final int size;
    private final double ridge;
    private double[][] xX;
    private double[] xY;
    private double horizon;
    private double residual = 0.0;
    private double residualData = 0.0;
    private Double yY = 0.0;
    private Map<Integer, Prior> priors = new HashMap<>();
    private Map<Integer, Double> floors = new HashMap<>();
    private double[] beta;
    private boolean betaReady = false;
    private double weight = 0.0;

    public MultiRegression(int size, double horizon) {
        this.size = size;
        xY = new double[size];
        xX = new double[size][size];
        this.horizon = horizon;
        this.ridge = RIDGE;
    }                   // med RIDGE

    public MultiRegression(int size, double horizon, double ridge) {
        this.size = size;
        xY = new double[size];
        xX = new double[size][size];
        this.horizon = horizon;
        this.ridge = ridge;
    }

    public static MultiRegression fromDict(Map<String, ?> data, int size, double horizon) {
        data = asMapping(data);

        int storedSize;
        try {
            storedSize = data.get("size") == null || !(data.get("size") instanceof Number n) ? 0 : n.intValue();
            if (data.get("size") instanceof String s) {
                storedSize = Integer.parseInt(s.strip());
            }
        } catch (NumberFormatException e) {
            storedSize = 0;
        }

        if (!(data.get("xx") instanceof List<?> rows) || rows.size() != size) {
            return new MultiRegression(size, horizon);
        }
        double[][] xX = new double[size][size];

        for (int r = 0; r < size; r++) {
            if (!(rows.get(r) instanceof List<?> row) || row.size() != size) {
                return new MultiRegression(size, horizon);
            }
            for (int c = 0; c < size; c++) {
                if (!(row.get(c) instanceof Number n)) {
                    return new MultiRegression(size, horizon);
                }
                xX[r][c] = n.doubleValue();
            }
        }

        if (!(data.get("xy") instanceof List<?> xyList) || xyList.size() != size)
            return new MultiRegression(size, horizon);


        double[] xY = new double[size];
        for (int i = 0; i < size; i++) {
            if (!(xyList.get(i) instanceof Number n)) {
                return new MultiRegression(size, horizon);   // null eller text i listan
            }
            xY[i] = n.doubleValue();
        }

        if (storedSize != size)
            return new MultiRegression(size, horizon);


        MultiRegression fit = new MultiRegression(size, horizon);
        fit.weight = asFloat(data.get("weight"), 0.0);
        fit.xX = xX;
        fit.xY = xY;
        fit.residual = asFloat(data.get("residual"), 0.0);
        fit.residualData = asFloat(data.get("residual_data"), asFloat(data.get("residual"), 0.0));
        fit.yY = asOptionalFloat(data.get("yy"));
        return fit;
    }

    public static double[] solveSymmetric(double[][] matrix, double[] rhs) {
        int size = rhs.length;
        double[][] work = new double[size][size + 1];

        for (int column = 0; column < size; column++) {
            work[column][size] = rhs[column];
            System.arraycopy(matrix[column], 0, work[column], 0, size);
        }

        for (int column = 0; column < size; column++) {
            int pivotRow = column;
            for (int r = column + 1; r < size; r++) {
                if (Math.abs(work[r][column]) > Math.abs(work[pivotRow][column])) {
                    pivotRow = r;
                }
            }

            if (Math.abs(work[pivotRow][column]) < 1e-12) {
                return null;
            }

            double[] t = work[column];
            work[column] = work[pivotRow];
            work[pivotRow] = t;

            double pivot = work[column][column];

            for (int r = column; r < size + 1; r++) {
                work[column][r] /= pivot;
            }

            for (int row = 0; row < size; row++) {
                if (row == column) {
                    continue;
                }

                double factor = work[row][column];

                if (factor == 0.0) {
                    continue;
                }

                for (int c = column; c < size + 1; c++) {
                    work[row][c] -= factor * work[column][c];
                }

            }
        }
        double[] result = new double[size];
        for (int i = 0; i < size; i++) {
            result[i] = work[i][size];
        }


        return result;
    }                   // Gauss-Jordan med pivot; null när systemet är singulärt

    public void setHorizon(double seconds) {
        this.horizon = seconds;
    }

    public int size() {
        return size;
    }

    public double horizon() {
        return horizon;
    }

    public double ridge() {
        return ridge;
    }

    public double weight() {
        return weight;
    }

    public double[][] xx() {
        return xX;
    }

    public double[] xy() {
        return xY;
    }

    public double yy() {
        return yY;
    }                   // summan av y i kvadrat

    public double residual() {
        return residual;
    }                   // kvadrerade fel i gissningarna före varje prov

    public double residualData() {
        return residualData;
    }                   // samma, mot anpassningen av bara data

    public void configure(Map<Integer, Prior> priors, Map<Integer, Double> floors) {
        this.priors = Map.copyOf(priors);
        this.floors = Map.copyOf(floors);
        betaReady = false;
    }                   // index -> förkunskap, index -> golv

    private double decay(double dt) {
        if (horizon <= 0.0 || dt <= 0.0) {
            return 1.0;
        }
        double ratio = dt / horizon;
        return ratio < 30 ? Math.pow(Math.E, -ratio) : 0.0;
    }

    public void add(double[] x, double y, double dt) {
        if (x.length != size) {
            return;
        }

        double decay = decay(dt);
        Double predicted = predict(x);
        if (predicted != null) {
            residual = residual * decay + Math.pow(y - predicted, 2);
        } else {
            residual *= decay;
        }

        double[] fromData = !priors.isEmpty() || !floors.isEmpty() ? dataCoefficients() : null;
        if (fromData == null && (priors.isEmpty() && floors.isEmpty())) {
            residualData = residual;
        } else if (fromData != null) {
            double guess = 0.0;
            for (int i = 0; i < fromData.length; i++) {
                guess += fromData[i] * x[i];
            }
            residualData = residualData * decay + Math.pow(y - guess, 2);
        } else {
            residualData *= decay;
        }

        betaReady = false;

        if (yY != null) {
            yY = yY * decay + y * y;
        }

        weight = weight * decay + 1.0;
        for (int row = 0; row < size; row++) {
            xY[row] = xY[row] * decay + x[row] * y;
            for (int col = 0; col < size; col++) {
                xX[row][col] = xX[row][col] * decay + x[row] * x[col];
            }
        }
    }                   // fel storlek på x: provet släpps

    private double[][] regularized() {
        double[][] result = new double[size][xX[0].length];

        for (int row = 0; row < size; row++) {
            for (int col = 0; col < xX[row].length; col++) {
                double value = xX[row][col];
                result[row][col] = value + (row == col ? ridge * Math.max(1.0, weight) : 0.0);
            }
        }

        return result;
    }

    private double[] dataCoefficients() {
        if (weight < size + 1) {
            return null;
        }

        return solveSymmetric(regularized(), xY);
    }

    public void rescale(int index, double factor) {

        if (index < 0 || index >= size) {
            throw new IllegalArgumentException(String.format("no regressor in %d in %d", index, size));
        }

        if (!(Double.isFinite(factor) && factor > 0.0)) {
            throw new IllegalArgumentException(String.format("a unit is a positive number. not %f", factor));
        }

        for (int other = 0; other < size; other++) {
            xX[index][other] *= factor;
            xX[other][index] *= factor;
        }
        xY[index] *= factor;
        betaReady = false;

    }                   // regressor i en enhet factor gånger större; IllegalArgumentException vid fel

    public double[] coefficients() {

        if (betaReady) {
            return beta;
        }
        beta = solve();
        betaReady = true;
        return beta;
    }                   // null med för lite data; sparas tills något ändras

    private double[] solve() {
        if (weight < size + 1) {
            return null;
        }
        double[][] matrix = regularized();
        double[] rhs = xY.clone();

        for (Map.Entry<Integer, Prior> entry : priors.entrySet()) {
            int index = entry.getKey();
            Prior prior = entry.getValue();
            matrix[index][index] += prior.strength();
            rhs[index] += prior.strength() * prior.expected();
        }

        double[] plain = solveSymmetric(matrix, rhs);

        boolean checkPlainAgainstFloor = true;
        if (plain != null) {
            for (Map.Entry<Integer, Double> entry : floors.entrySet()) {
                if (plain[entry.getKey()] < entry.getValue() - 1e-12) {
                    checkPlainAgainstFloor = false;
                    break;
                }
            }
        }

        if (plain != null && checkPlainAgainstFloor) {
            return plain;
        }

        List<Integer> bounded = floors.keySet().stream().sorted().toList();
        double[] best = null;
        Double bestCost = null;

        for (int mask = 0; mask < (1 << bounded.size()); mask++) {
            Map<Integer, Double> pinned = new HashMap<>();

            for (int bit = 0; bit < bounded.size(); bit++) {
                if (((mask >> bit & 1) == 1)) {
                    int index = bounded.get(bit);
                    pinned.put(index, floors.get(index));
                }
            }
            double[] beta = solvePinned(matrix, rhs, pinned);
            if (beta == null) {
                continue;
            }

            boolean underAFloor = false;
            for (int index : bounded) {
                if (!pinned.containsKey(index) && beta[index] < floors.get(index) - 1e-12) {
                    underAFloor = true;
                    break;
                }
            }


            if (underAFloor) {
                continue;
            }

            double cost = 0.0;
            for (int r = 0; r < size; r++) {
                for (int c = 0; c < size; c++) {
                    cost += beta[r] * matrix[r][c] * beta[c];
                }
                cost -= 2.0 * beta[r] * rhs[r];
            }

            if (bestCost == null || cost < bestCost) {
                best = beta;
                bestCost = cost;
            }
        }
        return best;
    }


    private double[] solvePinned(double[][] matrix, double[] rhs, Map<Integer, Double> pinned) {
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (!pinned.containsKey(i)) {
                free.add(i);
            }
        }

        double[][] reduced = new double[free.size()][free.size()];
        double[] reducedRhs = new double[free.size()];

        for (int r = 0; r < free.size(); r++) {
            int row = free.get(r);
            for (int c = 0; c < free.size(); c++) {
                reduced[r][c] = (matrix[row][free.get(c)]);
            }

            reducedRhs[r] = rhs[row];

            for (Map.Entry<Integer, Double> entry : pinned.entrySet()) {
                reducedRhs[r] -= (matrix[row][entry.getKey()] * entry.getValue());
            }
        }

        double[] solved = !free.isEmpty() ? solveSymmetric(reduced, reducedRhs) : new double[0];

        if (solved == null) {
            return null;
        }

        double[] beta = new double[size];

        for (int i = 0; i < free.size(); i++) {
            beta[free.get(i)] = solved[i];
        }

        for (Map.Entry<Integer, Double> entry : pinned.entrySet()) {
            beta[entry.getKey()] = entry.getValue();
        }
        return beta;
    }

    public Double predict(double[] x) {

        if (x.length != size) {
            return null;
        }

        double[] beta = coefficients();
        if (beta == null) {
            return null;
        }

        double result = 0.0;
        for (int b = 0; b < beta.length; b++) {
            result += beta[b] * x[b];
        }

        return result;
    }

    public double sigma() {

        double degrees = weight - size;
        if (degrees <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }

        double worst = Math.max(residual, residualData);
        return Math.pow(Math.max(0.0, worst) / degrees, 0.5);
    }                   // typiskt fel; oändligt utan frihetsgrader

    public double inSampleSigma() {

        if (yY == null) {
            return sigma();
        }

        double degrees = weight - size;
        double[] beta = coefficients();

        if (degrees <= 0.0 || beta == null) {
            return Double.POSITIVE_INFINITY;
        }

        double linear = 0.0;
        for (int b = 0; b < beta.length; b++) {
            linear += beta[b] * xY[b];
        }

        double quadratic = 0.0;
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                quadratic += beta[r] * xX[r][c] * beta[c];
            }
        }

        double squares = yY - 2.0 * linear + quadratic;

        return Math.pow(Math.max(0.0, squares) / degrees, 0.5);
    }                   // felet mot anpassningen som den står

    public Double reach(double[] x) {

        Double leverage = getLeverage(x);
        if (leverage == null) return null;

        return Math.pow(1.0 + leverage, 0.5) - 1.0;
    }                   // hur långt utanför sin data den ombeds gå

    public Double variance(double[] x) {

        Double leverage = getLeverage(x);
        if (leverage == null) return null;

        double sigma = sigma();
        if (sigma == Double.POSITIVE_INFINITY) {
            return null;
        }

        return sigma * sigma * (1.0 + leverage);
    }                   // osäkerheten i en gissning vid x, i y i kvadrat

    private Double getLeverage(double[] x) {
        if (x.length != size || weight < size + 1) {
            return null;
        }

        double[] solved = solveSymmetric(regularized(), x);

        if (solved == null) {
            return null;
        }

        double leverageInit = 0.0;
        for (int xi = 0; xi < x.length; xi++) {
            leverageInit += x[xi] * solved[xi];
        }
        return Math.max(0.0, leverageInit);
    }

    public Double uncertainty(double[] x) {
        Double variance = variance(x);
        return variance == null ? null : Math.pow(variance, 0.5);
    }                   // samma som standardavvikelse

    public Map<String, Object> asDict() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("size", size);
        result.put("horizon", horizon);
        result.put("weight", weight);
        result.put("xx", Arrays.stream(xX).map(row -> Arrays.stream(row).boxed().toList()).toList());
        result.put("xy", Arrays.stream(xY).boxed().toList());
        result.put("residual", residual);
        result.put("residual_data", residualData);
        result.put("yy", yY);
        return result;
    }
}
