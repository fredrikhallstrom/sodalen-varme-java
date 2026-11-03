package se.sodalen.heating.house;

// Allt en installatör kan vilja vrida på, med standardvärden för ett svenskt radiatorsystem ritat
// för 55/45 vid -18 ute. Publika, icke-final fält i Pythons ordning med Pythons standardvärden;
// tuningFromDict kopierar dem fält för fält. Vad varje tal betyder står utförligt i model.py.
public class Tuning {

    // --- Värmekurvan (framkoppling från utetemperaturen) ---
    public double roomTarget = 21.0;
    public double designOutdoor = -18.0;
    public double curveSlope = 0.9;
    public double curveCurvature = 0.8;                     // 0,8 sedan 2026-09-29 (var 1,1, böjd åt fel håll); svänger kring dimensioneringspunkten
    public double flowMin = 20.0;
    public double flowMax = 65.0;
    public double outdoorFilterSeconds = 3.0 * 3600.0;      // ett hus svarar på vädret över timmar: tre för ett lätt hus

    // --- Inomhusåterkopplingen ---
    public double indoorFilterSeconds = 20.0 * 60.0;
    public double indoorSlowFilterSeconds = 2.0 * 3600.0;
    public double roomGain = 3.0;                           // K tillopp per K inomhusfel
    public double roomErrorLimit = 8.0;
    public double warmStopMargin = 1.0;                     // över mål + detta stannar värmen helt
    public double warmStopHysteresis = 0.3;
    public double solarClamp = 1.5;                         // hur långt solgivaren får läsa över de andra

    // --- Överstyrningskontakten (vedspis, nattsänkning, vädring) ---
    public double overrideLowering = 5.0;
    public double learnAfterOverrideSeconds = 2.0 * 3600.0;  // inlärningen fryst så länge efter kontakten
    public double learnTargetWaitMaxSeconds = 6.0 * 3600.0;
    public double learnTargetWarming = 0.1;
    public double learnSunlight = 0.047;                    // kW/m2 på väggarna; över detta lär kurvan inte utan soltermen
    public double learnAfterSunSeconds = 2.0 * 3600.0;
    public double learnAfterSunWarm = 0.3;
    public double learnAfterSunMaxSeconds = 6.0 * 3600.0;
    public boolean learnSunTerm = true;                     // solens del av felet lärs (sunterm.py)
    public double learnSunError = 0.1;
    public double sunHeatSeconds = 3.0 * 3600.0;
    public double sunTermHorizonSeconds = 21.0 * 24.0 * 3600.0;
    public double learnAfterFailsafeWarm = 0.3;
    public double learnAfterFailsafeMaxSeconds = 24.0 * 3600.0;

    // --- Deltat tillopp/retur ---
    public double deltaDesign = 10.0;                       // väntat delta vid dimensioneringspunkten
    public double deltaGain = 1.2;
    public double deltaLowerLimit = 6.0;
    public double deltaRaiseLimit = 2.0;
    public double deltaColdGuard = 0.3;                     // deltat sänker aldrig tilloppet när huset är så här kallt
    public double deltaExpectedHorizonSeconds = 3.0 * 24.0 * 3600.0;
    public double deltaScaleMin = 0.2;                      // vad en krets kan vara: en vecka med stängda ventiler är ingen skala
    public double deltaScaleMax = 3.0;
    public boolean deltaEnabled = true;
    public double deltaMinDemand = 0.15;
    public double deltaMinOvertemp = 10.0;                  // under så mycket tillopp över rummet mäter deltat mest shunten själv
    public double deltaFade = 5.0;
    public double deltaSettleBand = 1.0;
    public double deltaSettleSeconds = 300.0;
    public double deltaFilterSeconds = 900.0;
    public double deltaHoldMaxSeconds = 1800.0;
    public double deltaWarmupSeconds = 2700.0;              // döms inte alls så länge efter ett stopp: rören är kalla
    public double starvedMargin = 0.3;
    public double starvedHorizonSeconds = 3.0 * 24.0 * 3600.0;
    public double starvedShare = 0.5;
    public double starvedMinHours = 6.0;

    // --- Tilloppsgivaren borta ---
    public double failsafePosition = 100.0;                 // utan tilloppsgivare: full värme, ett hus överlever för varmt
    public double resumePositionMax = 50.0;                 // längst den lärda förstärkningen får öppna efter ett stopp
    public double resumeHoldSeconds = 180.0;
    public double valveFeedforwardGain = 1.0;               // ventilkartan som framkoppling; 0 är av
    public double valveFeedforwardStep = 5.0;
    public double failsafeAfterSeconds = 600.0;

    // --- Nödkörning (ControlInputs.emergency) ---
    public double emergencyPosition = 50.0;                 // ventilen på fast läge, behöver ingen givare
    public double emergencyFlow = 40.0;                     // tilloppet på fast temperatur, behåller inre slingan
    public double reseedAfterSeconds = 300.0;               // hur länge tilloppsgivaren får vara borta innan integralen kastas

    // --- Gamla givarvärden ---
    public double sensorMaxAgeSeconds = 1800.0;             // en radiogivare som tystnat ser ut som en som fungerar

    // --- Förutseende avstängning ---
    public boolean coastEnabled = true;
    public double coastHorizonHours = 12.0;
    public double coastConfidence = 2.0;                    // standardavvikelser av modellens osäkerhet i reserv
    public double coastLearningBand = 1.0;                  // utforskningsbudget medan modellen lär, inte komforttolerans
    public double coastComfortBand = 1.0;                   // hur långt under mål familjen tål under en avstängning
    public boolean coastBridgeOnly = true;                  // bara när huset slutar svalna av sig självt före golvet (Fredrik 2026-10-10)
    public double coastLearningHoldSeconds = 2.0 * 3600.0;  // inlärningsspärren: timmar i cellen, inte stillhet i rad; 0 är av
    public double coastLearningHoldMaxSeconds = 6.0 * 3600.0;
    public double coastLearningCellHours = 6.0;
    public double coastRecoveryGain = 2.0;
    public double coastUnknownReach = 1.0;                  // dimensionslöst, se MultiRegression.reach
    public double coastMinHours = 48.0;                     // två dygn innan modellen får stoppa något
    public double shuntLeadSeconds = 600.0;                 // från öppnad shunt till värme i rummen
    public double coastHysteresisSeconds = 900.0;           // extra hållning innan en avstängning startas

    // --- Att lära huset ---
    public double thermalSampleSeconds = 600.0;             // husmodellen mäter på sin egen klocka
    public double thermalHorizonSeconds = 21.0 * 24.0 * 3600.0;
    public double forecastHorizonSeconds = 14.0 * 24.0 * 3600.0;

    // --- Sommarstoppet ---
    public double summerOutdoor = 17.0;
    public double summerHysteresis = 1.5;
    public double summerFilterSeconds = 24.0 * 3600.0;      // ett dygns medel säger "det här vädret", inte "den här eftermiddagen"

    // --- Värmekällan ---
    public boolean heatSourceOffInSummer = true;
    public boolean heatSourceOffWhenIdle = false;           // av: varmstoppet är några timmar, och brännaren ska inte korttidsköras
    public boolean heatSourceOffWhenDisabled = true;
    public double heatSourceOffDelay = 1800.0;              // ett av-beslut måste hålla så länge innan det verkställs
    public double boilerMax = 95.0;                         // pannans egen vakt: här tappar brännaren strömmen
    public double boilerMaxRelease = 85.0;

    // --- Inre slingan: tillopp -> ventilläge ---
    public double cycleSeconds = 30.0;
    public double pidKp = 3.5;
    public double pidTi = 150.0;
    public double pidTd = 20.0;
    public double pidDeadband = 0.2;
    public double minPositionStep = 1.5;
    public double positionDriftLimit = 5.0;                 // hur långt ventilen får stå från det den sändes, och hur länge
    public double positionDriftSeconds = 300.0;
    public double gainReference = 0.4;                      // förstärkningen standard-Kp stämdes för, K tillopp per % slag
    public double gainScaleMin = 0.4;
    public double gainScaleMax = 2.5;
    public boolean valveMapGain = true;                     // förstärkningen från ventilkartan där ventilen står
    public double kpRiseSeconds = 900.0;                    // Kp mjukare genast, djärvare bara över så lång tid; 0 följer kartan cykel för cykel
    public double gainHorizonSeconds = 12.0 * 3600.0;
    public double gainMinVariance = 9.0;

    // --- Självinlärningen ---
    public boolean learnEnabled = true;
    public double learnHorizonSeconds = 3.0 * 24.0 * 3600.0;  // lutningens minne, i inlärda timmar
    public double learnLevelHorizonSeconds = 12.0 * 3600.0;  // nivåns minne, i klockans timmar
    public double learnOffsetRate = 0.08;                   // per timme, inte per cykel; 0,08 sedan 2026-10-25 (var 0,15)
    public double learnSlopeRate = 0.02;                    // lutningen är en egenskap hos huset och lärs över dagar
    public double learnSaturatedSeconds = 3600.0;           // så länge mot gränsen innan lutningen flyttas på det ensamt
    public double learnWarmTrapSeconds = 6.0 * 3600.0;      // nettotid i varmstoppet innan det räknas som en fälla
    public double learnWarmTrapDecay = 0.25;
    public double learnSaturatedSlopeRate = 0.15;
    public double learnSlopePull = 0.0005;                  // per timme, drar lutningen tillbaka mot installatörens
    public double balanceShiftMin = -14.0;                  // hur långt balansen får gå från rumsmålet, i grader ute; ner är gratisvärme
    public double balanceShiftMax = 3.0;
    public double learnBalanceScale = 3.0;                  // hur en korrigering delas mellan balans och lutning
    public double learnSlopeScale = 0.05;
    public double slopeMin = 0.2;
    public double slopeMax = 2.0;
    public double learnMinVariance = 0.01;                  // lutningen syns först när vädret varierat så här mycket
    public double learnMaxFlowError = 3.0;                  // inlärningen kräver att inre slingan levererar
    public double learnPositionMargin = 3.0;
    public double learnMinDemand = 0.15;
    public double learnSettleSeconds = 3600.0;

    // --- Kurvtabellen (points.py, curvefit.py) ---
    public boolean tableLearning = true;                    // om tabellen får styra kurvan; av är nödstoppet
    public double tableMaxGreenGap = 2.0;                   // grader ute mellan två betrodda prickar i en kedja
    public double curveBendLimit = 2.0;                     // hur långt böjen får flytta kurvans mitt, i grader tillopp
    public double tableHalfLifeDays = 60.0;                 // hur länge en mätning behåller halva sin röst
    public double tableSteadySeconds = 3600.0;              // så länge stilla innan en mätning tas
    public double tableIndoorBand = 0.5;
    public double tableIndoorTrend = 0.15;
    public double tableOutdoorTrend = 2.0;                  // 2,0 sedan 2026-10-08 (Fredrik)
    public double tableRedHours = 3.0;                      // när en ruta räknas, se points.Rules
    public double tableGreenHours = 24.0;
    public double tableGreenSpread = 1.5;
    public double tableNumberPerDay = 0.1;                  // hur fort kurvan får följa anpassningen
    public double tableBalancePerDay = 0.5;
    public double tableBendPerDay = 0.01;
    public double tableBalanceTrust = 2.0;                  // husmodellens balans styr under detta standardfel, i K
    public boolean tableBalanceFromHouse = false;           // av sedan 2026-10-14: dagens balans är hushållets dag utjämnad
    public double nightStartHour = 22.0;                    // natten husmodellens nattbalans tas från: fasta timmar
    public double nightEndHour = 6.0;
    public double tableFitSeconds = 3600.0;

    public double clampFlow(double flow) {
        return 0.0;
    }                   // min(flowMax, max(flowMin, flow)); inte Math.clamp, som kastar när flowMin > flowMax

    public double designSpan() {
        return 0.0;
    }                   // dimensionerande (rumsmål - ute), aldrig under 1
}
