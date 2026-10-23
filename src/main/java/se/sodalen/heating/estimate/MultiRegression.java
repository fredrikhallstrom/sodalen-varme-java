package se.sodalen.heating.estimate;

import org.w3c.dom.ranges.Range;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Minsta kvadrat i flera variabler, med glömska, förkunskap, golv och en ärlig uppfattning om hur säker den är.
public final class MultiRegression {
    private int size;
    private double horizon;
    private double ridge;
    private double residualData = 0.0;
    private Double yY = 0.0;
    private List<List<Double>> xX = new ArrayList<>(new ArrayList<>());
    private List<Double> xY = new ArrayList<>();
    private Map<Integer, Double> priors = new HashMap<>();
    private Map<Integer, Double> floors = new HashMap<>();

    public static final double RIDGE = 1e-6;     // läggs på diagonalen, gånger vikten
    private double weight = 0.0;

    public MultiRegression(int size, double horizon) {
        this.size = size;
        this.horizon = horizon;
        this.ridge = RIDGE;
    }                   // med RIDGE

    public MultiRegression(int size, double horizon, double ridge) {
        this.size = size;
        this.horizon = horizon;
        this.ridge = ridge;
    }

    public static double[] solveSymmetric(double[][] matrix, double[] rhs) {
        int size = rhs.length;
        double[][] work = new double[size][size + 1];

        for (int column = 0; column < size; column++) {
            work[column][size] = rhs[column];
            for (int row = 0; row < size; row++) {
                work[column][row] = matrix[column][row];
            }
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
    }                   // Gauss-Jordan med pivotering; null när systemet är singulärt

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
        return null;
    }                   // summan av x x', size gånger size

    public double[] xy() {
        return null;
    }                   // summan av x y

    public double yy() {
        return 0;
    }                   // summan av y i kvadrat

    public double residual() {
        return 0;
    }                   // kvadrerade fel i gissningarna före varje prov

    public double residualData() {
        return 0;
    }                   // samma, mot anpassningen av bara datan

    public void configure(Map<Integer, Prior> priors, Map<Integer, Double> floors) {
    }                   // index -> förkunskap, index -> golv

    public void add(double[] x, double y, double dt) {
    }                   // fel storlek på x: provet släpps

    public void rescale(int index, double factor) {
    }                   // regressorn i en enhet factor gånger större; IllegalArgumentException vid fel

    public double[] coefficients() {
        return null;
    }                   // null med för lite data; sparas tills något ändras

    public Double predict(double[] x) {
        return null;
    }

    public double sigma() {
        return 0;
    }                   // typiskt fel; oändligt utan frihetsgrader

    public double inSampleSigma() {
        return 0;
    }                   // felet mot anpassningen som den står

    public Double reach(double[] x) {
        return null;
    }                   // hur långt utanför sin data den ombeds gå

    public Double variance(double[] x) {
        return null;
    }                   // osäkerheten i en gissning vid x, i y i kvadrat

    public Double uncertainty(double[] x) {
        return null;
    }                   // samma som standardavvikelse
}
