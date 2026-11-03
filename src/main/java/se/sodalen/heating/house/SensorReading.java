package se.sodalen.heating.house;

// En inomhusgivare som regleringen ser den. temperature null = svarar inte. offset är den fasta
// kalibreringen (det enda självinlärningen inte kan hitta själv), stratification dras av per K av
// (tillopp - rumsmål). maxAge null = kretsens sensor_max_age_seconds gäller. active false = läses och
// visas men vägs inte in. featured = visas på egen rad.
public record SensorReading(String key, Double temperature, String role, double weight, double offset,
                            double stratification, Double maxAge, boolean active, boolean featured) {

    // Pythons standardvärden, som tre kortare konstruktorer du skriver själv:
    //   SensorReading(String key, Double temperature)                        // role ROLE_ROOM, weight 1.0, offset 0.0,
    //                                                                        // stratification 0.0, maxAge null, active true, featured false
    //   SensorReading(String key, Double temperature, String role, double weight)   // resten standard
    //   SensorReading(String key, Double temperature, String role, double weight, double offset, double stratification)

    SensorReading(String key, Double temperature) {
        this(key, temperature, Model.ROLE_ROOM, 1.0, 0.0, 0.0, null, true, false);
    }

    SensorReading(String key, Double temperature, String role, double weight) {
        this(key, temperature, role, weight, 0.0, 0.0, null, true, false);
    }

    SensorReading(String key, Double temperature, String role, double weight, double offset, double stratification) {
        this(key, temperature, role, weight, offset, stratification, null, true, false);
    }

    public boolean available() {
        return false;
    }                   // temperaturen finns (inte null)
}
