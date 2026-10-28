package se.sodalen.heating.forecast;

// En källas uppfattning om vädret just nu. null där Python har None: en källa som inte sagt
// något om temperaturen den här cykeln betygsätts inte.
public record Reading(String name, Double temperature, Double wind, Double bearing, Double cloud) {

    public Reading(String name) {
        this(name, null, null, null, null);
    }                   // allt annat null

    public Reading(String name, Double temperature) {
        this(name, temperature, null, null, null);
    }                   // vind, riktning och moln null
}
