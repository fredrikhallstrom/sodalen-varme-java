package se.sodalen.heating.curve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Kurvtabellen: vad huset behövde, per två grader ute och sex timmar av dygnet.

public final class PointTable {
    public static final double BIN_WIDTH = 2.0;
    public static final int SECTORS = 4;
    public static final double SECTOR_HOURS = 24.0 / SECTORS;

    private record Key(int index, int sector) {
    }

    Map<Key, Cell> cells = new HashMap<>();

    public static int binIndex(double outdoor) {
        return (int) Math.floor(outdoor / BIN_WIDTH);
    }

    private int sector(Double hour) {
        if (hour == null) {
            return 0;
        }

        return (int) Math.floor(((hour % 24) + 24) % 24 / SECTOR_HOURS);
    }

    private Map<Status, Double> judge(Double hours, Double spread, int sectors, Rules rules) {
        if (hours < rules.redHours()) {
            return new HashMap<Status, Double>() {{
                put(Status.RED, 0.0);
            }};
        }
        ;

        if (hours >= rules.greenHours() && spread <= rules.greenSpread() && sectors >= rules.greenSectors()) {
            return new HashMap<Status, Double>() {{
                put(Status.GREEN, 1.0);
            }};
        }

        double coverage = (double) sectors / SECTORS;
        return new HashMap<Status, Double>() {{
            put(Status.YELLOW, 0.5 * Math.min(1.0, hours / rules.greenHours()) * coverage);
        }};
    }

    public void add(double outdoor, double need, Double wind, Double hour, double hours) {

        if (hours <= 0.0 || !Double.isFinite(outdoor) || !Double.isFinite(need)) {
            return;
        }

        Key key = new Key(binIndex(outdoor), sector(hour));
        Cell cell = cells.get(key);
        if (cell == null) {
            cell = new Cell();
            cells.put(key, cell);
        }
        cell.add(need, outdoor, Math.max(0.0, wind == null ? 0.0 : wind), hours);
    }

    public void fade(double hours, double halfLifeHours) {
        if (hours <= 0.0) {
            return;
        }

        double factor = halfLifeHours > 0.0 ? Math.pow(0.5, (hours / halfLifeHours)) : 1.0;

        for (Cell cell : cells.values()) {
            cell.fade(factor, hours);
        }
    }

    public void clear() {
        cells.clear();
    }

    public List<Bin> bins() {
        return bins(new Rules());
    }                   // med new Rules()

    public List<Bin> bins(Rules rules) {
        Map<Integer, List<Cell>> byBin = new TreeMap<>();
        for (Map.Entry<Key, Cell> binCell : cells.entrySet()) {
            if (binCell.getValue().weight() > 0.0) {
                byBin.computeIfAbsent(binCell.getKey().index, k -> new ArrayList<>()).add(binCell.getValue());
            }
        }

        List<Bin> out = new ArrayList<>();
        for (List<Cell> binCells : byBin.values()) {
            double hours = binCells.stream()
                    .mapToDouble(Cell::weight)
                    .sum();
            List<Cell> counted = binCells.stream()
                    .filter(cell -> cell.weight() >= rules.sectorHours())
                    .toList();
            List<Cell> parts = !counted.isEmpty() ? counted : binCells;

            double need = parts.stream()
                    .mapToDouble(Cell::mean)
                    .sum() / parts.size();

            double spread = hours > 0.0 ? Math.sqrt(Math.max(0.0, binCells.stream()
                    .mapToDouble(Cell::m2)
                    .sum() / hours
            ))
                    : 0.0;

            int sectors = counted.size();
            Map<Status, Double> means = judge(hours, spread, sectors, rules);

            out.add(new Bin(
                    calculateAverageOutdoor(binCells, hours),
                    need,
                    hours,
                    spread,
                    sectors,
                    calculateAverageWind(binCells, hours),
                    minAge(binCells),
                    means.keySet().stream().findFirst().get(),
                    means.values().stream().findFirst().get()

            ));
        }

        return out;
    }

    private double calculateAverageOutdoor(List<Cell> binCells, double hours) {
        double cellSum = 0;
        for (Cell cell : binCells) {
            cellSum += cell.outdoor() * cell.weight();
        }
        cellSum /= hours;

        return cellSum;
    }

    private static double minAge(List<Cell> binCells) {
        double age = Double.MAX_VALUE;
        for (Cell cell : binCells) {
            if (cell.age() < age) {
                age = cell.age();
            }
        }
        return age;
    }

    private static double calculateAverageWind(List<Cell> binCells, double hours) {
        double calculatedWind = 0.0;
        for (Cell cell : binCells) {
            calculatedWind += cell.wind() * cell.weight();
        }
        calculatedWind /= hours;
        return calculatedWind;
    }

    public String keyAt(double outdoor, Double hour) {
        return String.format("%d:%d", binIndex(outdoor), sector(hour));
    }                   // "fack:del", t.ex. "-2:3"

    public double hoursAt(double outdoor, Double hour) {
        Cell cell = cells.get((new Key(binIndex(outdoor), sector(hour))));
        return cell != null ? cell.weight() : 0.0;
    }


}
