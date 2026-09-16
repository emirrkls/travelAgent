package com.emirrkls.phokarta.backend.domain.model;

import java.util.EnumMap;
import java.util.Map;

/** Stable, nonlocalized wire and persistence codes for Experience taxonomy v1. */
public final class ExperienceTaxonomy {
    public static final int VERSION = 1;

    public enum OverallFeelingCode {
        BAYILDIM(10.0),
        GUZELDI(8.0),
        EH_ISTE(6.0),
        BEKLENTIMI_KARSILAMADI(4.0),
        BIR_DAHA_TERCIH_ETMEM(2.0);

        private final double compatibilityScore;

        OverallFeelingCode(double compatibilityScore) {
            this.compatibilityScore = compatibilityScore;
        }

        public double compatibilityScore() { return compatibilityScore; }

        public static OverallFeelingCode fromLegacyRating(double rating) {
            if (rating >= 9.0) return BAYILDIM;
            if (rating >= 7.0) return GUZELDI;
            if (rating >= 5.0) return EH_ISTE;
            if (rating >= 3.0) return BEKLENTIMI_KARSILAMADI;
            return BIR_DAHA_TERCIH_ETMEM;
        }
    }

    public enum FeelingSource { EXPLICIT, DERIVED_LEGACY }

    public enum DimensionStateCode {
        VERY_GOOD(10), GOOD(8), MEDIUM(6), WEAK(4), VERY_WEAK(2);

        private final int compatibilityScore;

        DimensionStateCode(int compatibilityScore) {
            this.compatibilityScore = compatibilityScore;
        }

        public int compatibilityScore() { return compatibilityScore; }
    }

    public enum ExperienceFamily {
        FOOD_AND_DRINK,
        SCENERY_AND_MOMENT,
        SEA_AND_WATER,
        NATURE_AND_OUTDOOR,
        TRAVEL_AND_DISCOVERY,
        CULTURE_AND_LOCAL_LIFE,
        ENTERTAINMENT_AND_NIGHTLIFE,
        ACTIVITY_AND_ADVENTURE,
        REST_AND_WELLNESS,
        ACCOMMODATION
    }

    public enum PrimaryExperienceCode {
        KAHVALTI, OGUN_YEMEK, KAHVE, TATLI, SOKAK_LEZZETI, YEREL_LEZZET,
        GUN_BATIMI, GUN_DOGUMU, MANZARA, GECE_MANZARASI, FOTOGRAF_NOKTASI,
        DENIZ_YUZME, PLAJ, TEKNE, DALIS_SNORKEL, SU_AKTIVITESI,
        DOGA_YURUYUSU, PIKNIK, KAMP, ORMAN, GOL_SELALE, SEYIR_NOKTASI,
        SOKAK_KESFI, MAHALLE_SEHIR_GEZISI, SAHIL_YURUYUSU, GIZLI_KOSE, ROTA_GEZI,
        MUZE, TARIHI_YER, MIMARI, YEREL_PAZAR, YEREL_YASAM, SERGI_SANAT,
        CANLI_MUZIK, BAR_PUB, GECE_HAYATI, KONSER_GOSTERI, SOSYAL_ETKINLIK,
        BISIKLET, TIRMANIS, KAYAK, SU_SPORU, WORKSHOP, ACIK_HAVA_AKTIVITESI,
        SAKIN_ZAMAN, SPA_HAMAM, TERMAL, YOGA_MEDITASYON, DINLENME,
        OTEL, BUTIK_OTEL, HOSTEL, KAMP_KONAKLAMASI, KIRALIK_EV_BUNGALOV,
        OTHER
    }

    public enum CompanionCode { ALONE, PARTNER, FRIENDS, FAMILY, CHILDREN }
    public enum TimeOfDayCode { MORNING, DAYTIME, EVENING, NIGHT }
    public enum VibeCode { CALM, LIVELY, ROMANTIC, SOCIAL, INTIMATE, LOCAL_AUTHENTIC, SCENIC, ADVENTUROUS }
    public enum PracticalSignalCode {
        ACCESSIBLE_WITHOUT_CAR,
        CAR_RECOMMENDED,
        PARKING_DIFFICULT,
        RESERVATION_RECOMMENDED,
        NO_RESERVATION_NEEDED,
        WEEKENDS_CROWDED,
        MAY_BE_CROWDED,
        CALMER_IN_MORNING,
        IDEAL_FOR_SUNSET,
        SUITABLE_WITH_CHILDREN,
        PET_FRIENDLY,
        WALKING_REQUIRED,
        ARRIVE_EARLY,
        CASH_MAY_BE_NEEDED,
        FREE,
        QUIET_AREA_AVAILABLE
    }
    public enum TitleSource { GENERATED, CUSTOM }

    private static final Map<PrimaryExperienceCode, ExperienceFamily> FAMILIES = families();

    public static ExperienceFamily familyOf(PrimaryExperienceCode code) {
        return FAMILIES.get(code);
    }

    private static Map<PrimaryExperienceCode, ExperienceFamily> families() {
        EnumMap<PrimaryExperienceCode, ExperienceFamily> values = new EnumMap<>(PrimaryExperienceCode.class);
        assign(values, ExperienceFamily.FOOD_AND_DRINK,
                PrimaryExperienceCode.KAHVALTI, PrimaryExperienceCode.OGUN_YEMEK,
                PrimaryExperienceCode.KAHVE, PrimaryExperienceCode.TATLI,
                PrimaryExperienceCode.SOKAK_LEZZETI, PrimaryExperienceCode.YEREL_LEZZET);
        assign(values, ExperienceFamily.SCENERY_AND_MOMENT,
                PrimaryExperienceCode.GUN_BATIMI, PrimaryExperienceCode.GUN_DOGUMU,
                PrimaryExperienceCode.MANZARA, PrimaryExperienceCode.GECE_MANZARASI,
                PrimaryExperienceCode.FOTOGRAF_NOKTASI);
        assign(values, ExperienceFamily.SEA_AND_WATER,
                PrimaryExperienceCode.DENIZ_YUZME, PrimaryExperienceCode.PLAJ,
                PrimaryExperienceCode.TEKNE, PrimaryExperienceCode.DALIS_SNORKEL,
                PrimaryExperienceCode.SU_AKTIVITESI);
        assign(values, ExperienceFamily.NATURE_AND_OUTDOOR,
                PrimaryExperienceCode.DOGA_YURUYUSU, PrimaryExperienceCode.PIKNIK,
                PrimaryExperienceCode.KAMP, PrimaryExperienceCode.ORMAN,
                PrimaryExperienceCode.GOL_SELALE, PrimaryExperienceCode.SEYIR_NOKTASI);
        assign(values, ExperienceFamily.TRAVEL_AND_DISCOVERY,
                PrimaryExperienceCode.SOKAK_KESFI, PrimaryExperienceCode.MAHALLE_SEHIR_GEZISI,
                PrimaryExperienceCode.SAHIL_YURUYUSU, PrimaryExperienceCode.GIZLI_KOSE,
                PrimaryExperienceCode.ROTA_GEZI);
        assign(values, ExperienceFamily.CULTURE_AND_LOCAL_LIFE,
                PrimaryExperienceCode.MUZE, PrimaryExperienceCode.TARIHI_YER,
                PrimaryExperienceCode.MIMARI, PrimaryExperienceCode.YEREL_PAZAR,
                PrimaryExperienceCode.YEREL_YASAM, PrimaryExperienceCode.SERGI_SANAT);
        assign(values, ExperienceFamily.ENTERTAINMENT_AND_NIGHTLIFE,
                PrimaryExperienceCode.CANLI_MUZIK, PrimaryExperienceCode.BAR_PUB,
                PrimaryExperienceCode.GECE_HAYATI, PrimaryExperienceCode.KONSER_GOSTERI,
                PrimaryExperienceCode.SOSYAL_ETKINLIK);
        assign(values, ExperienceFamily.ACTIVITY_AND_ADVENTURE,
                PrimaryExperienceCode.BISIKLET, PrimaryExperienceCode.TIRMANIS,
                PrimaryExperienceCode.KAYAK, PrimaryExperienceCode.SU_SPORU,
                PrimaryExperienceCode.WORKSHOP, PrimaryExperienceCode.ACIK_HAVA_AKTIVITESI);
        assign(values, ExperienceFamily.REST_AND_WELLNESS,
                PrimaryExperienceCode.SAKIN_ZAMAN, PrimaryExperienceCode.SPA_HAMAM,
                PrimaryExperienceCode.TERMAL, PrimaryExperienceCode.YOGA_MEDITASYON,
                PrimaryExperienceCode.DINLENME);
        assign(values, ExperienceFamily.ACCOMMODATION,
                PrimaryExperienceCode.OTEL, PrimaryExperienceCode.BUTIK_OTEL,
                PrimaryExperienceCode.HOSTEL, PrimaryExperienceCode.KAMP_KONAKLAMASI,
                PrimaryExperienceCode.KIRALIK_EV_BUNGALOV);
        return Map.copyOf(values);
    }

    private static void assign(EnumMap<PrimaryExperienceCode, ExperienceFamily> values,
                               ExperienceFamily family, PrimaryExperienceCode... codes) {
        for (PrimaryExperienceCode code : codes) values.put(code, family);
    }

    private ExperienceTaxonomy() {}
}
