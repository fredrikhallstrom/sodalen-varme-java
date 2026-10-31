package se.sodalen.heating.burner;

// En brännares inställningar: dicten burner_settings ger, med fält. controlPath är null tills något sparats.
public record BurnerSettings(double startLead, double offDelay, double burnoutSeconds, double sweepLeadSeconds,
                             String callForHeat, double alarmBelow, String controlPath) {
}
