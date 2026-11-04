package se.sodalen.heating.house;

import java.util.List;

// Pythons tupel ur fuse_indoor: den sammanvägda inomhustemperaturen, null när ingen givare
// bidrog, och en FusedSensor per avläsning i den ordning avläsningarna kom.
public record IndoorFusion(Double temperature, List<FusedSensor> details) {
}
