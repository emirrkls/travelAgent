package com.emirrkls.phokarta.ui.localization

import com.emirrkls.phokarta.core.model.CompanionCode
import com.emirrkls.phokarta.core.model.DimensionStateCode
import com.emirrkls.phokarta.core.model.ExperienceClassification
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PracticalSignalCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.TimeOfDayCode
import com.emirrkls.phokarta.core.model.VibeCode
import java.util.Locale

enum class DisplayLanguage { EN, TR }

data class ComposerDisclosureState(
    val storyExpanded: Boolean = false,
    val titleExpanded: Boolean = false,
) {
    fun toggleStory() = copy(storyExpanded = !storyExpanded)
    fun toggleTitle() = copy(titleExpanded = !titleExpanded)
}

fun displayLanguage(locale: Locale): DisplayLanguage =
    if (locale.language.equals("tr", ignoreCase = true)) DisplayLanguage.TR else DisplayLanguage.EN

/** One presentation boundary for every stable V2 wire code. */
object ExperienceLabels {
    fun dimensionChoices(language: DisplayLanguage): List<Pair<DimensionStateCode?, String>> =
        listOf(null to if (language == DisplayLanguage.TR) "Seç" else "Select") +
            DimensionStateCode.entries.filterNot { it == DimensionStateCode.UNKNOWN }
                .map { it to dimensionState(it, language) }

    fun primary(code: PrimaryExperienceCode, language: DisplayLanguage): String? {
        if (code == PrimaryExperienceCode.UNKNOWN_LEGACY) return null
        val en = mapOf(
            PrimaryExperienceCode.KAHVALTI to "Breakfast", PrimaryExperienceCode.OGUN_YEMEK to "Meal",
            PrimaryExperienceCode.KAHVE to "Coffee", PrimaryExperienceCode.TATLI to "Dessert",
            PrimaryExperienceCode.SOKAK_LEZZETI to "Street food", PrimaryExperienceCode.YEREL_LEZZET to "Local food",
            PrimaryExperienceCode.GUN_BATIMI to "Sunset", PrimaryExperienceCode.GUN_DOGUMU to "Sunrise",
            PrimaryExperienceCode.MANZARA to "Scenery", PrimaryExperienceCode.GECE_MANZARASI to "Night view",
            PrimaryExperienceCode.FOTOGRAF_NOKTASI to "Photo spot", PrimaryExperienceCode.DENIZ_YUZME to "Sea / swimming",
            PrimaryExperienceCode.PLAJ to "Beach", PrimaryExperienceCode.TEKNE to "Boat trip",
            PrimaryExperienceCode.DALIS_SNORKEL to "Diving / snorkeling", PrimaryExperienceCode.SU_AKTIVITESI to "Water activity",
            PrimaryExperienceCode.DOGA_YURUYUSU to "Nature walk", PrimaryExperienceCode.PIKNIK to "Picnic",
            PrimaryExperienceCode.KAMP to "Camping", PrimaryExperienceCode.ORMAN to "Forest",
            PrimaryExperienceCode.GOL_SELALE to "Lake / waterfall", PrimaryExperienceCode.SEYIR_NOKTASI to "Viewpoint",
            PrimaryExperienceCode.SOKAK_KESFI to "Street discovery", PrimaryExperienceCode.MAHALLE_SEHIR_GEZISI to "City walk",
            PrimaryExperienceCode.SAHIL_YURUYUSU to "Coastal walk", PrimaryExperienceCode.GIZLI_KOSE to "Hidden corner",
            PrimaryExperienceCode.ROTA_GEZI to "Route / trip", PrimaryExperienceCode.MUZE to "Museum",
            PrimaryExperienceCode.TARIHI_YER to "Historic place", PrimaryExperienceCode.MIMARI to "Architecture",
            PrimaryExperienceCode.YEREL_PAZAR to "Local market", PrimaryExperienceCode.YEREL_YASAM to "Local life",
            PrimaryExperienceCode.SERGI_SANAT to "Exhibition / art", PrimaryExperienceCode.CANLI_MUZIK to "Live music",
            PrimaryExperienceCode.BAR_PUB to "Bar / pub", PrimaryExperienceCode.GECE_HAYATI to "Nightlife",
            PrimaryExperienceCode.KONSER_GOSTERI to "Concert / show", PrimaryExperienceCode.SOSYAL_ETKINLIK to "Social event",
            PrimaryExperienceCode.BISIKLET to "Cycling", PrimaryExperienceCode.TIRMANIS to "Climbing",
            PrimaryExperienceCode.KAYAK to "Skiing", PrimaryExperienceCode.SU_SPORU to "Water sport",
            PrimaryExperienceCode.WORKSHOP to "Workshop", PrimaryExperienceCode.ACIK_HAVA_AKTIVITESI to "Outdoor activity",
            PrimaryExperienceCode.SAKIN_ZAMAN to "Quiet time", PrimaryExperienceCode.SPA_HAMAM to "Spa / hammam",
            PrimaryExperienceCode.TERMAL to "Thermal spa", PrimaryExperienceCode.YOGA_MEDITASYON to "Yoga / meditation",
            PrimaryExperienceCode.DINLENME to "Rest", PrimaryExperienceCode.OTEL to "Hotel",
            PrimaryExperienceCode.BUTIK_OTEL to "Boutique hotel", PrimaryExperienceCode.HOSTEL to "Hostel",
            PrimaryExperienceCode.KAMP_KONAKLAMASI to "Campsite stay", PrimaryExperienceCode.KIRALIK_EV_BUNGALOV to "Rental / bungalow",
            PrimaryExperienceCode.OTHER to "Other experience", PrimaryExperienceCode.UNKNOWN to "Experience",
        )
        val tr = mapOf(
            PrimaryExperienceCode.KAHVALTI to "Kahvaltı", PrimaryExperienceCode.OGUN_YEMEK to "Öğün / Yemek",
            PrimaryExperienceCode.KAHVE to "Kahve", PrimaryExperienceCode.TATLI to "Tatlı",
            PrimaryExperienceCode.SOKAK_LEZZETI to "Sokak lezzeti", PrimaryExperienceCode.YEREL_LEZZET to "Yerel lezzet",
            PrimaryExperienceCode.GUN_BATIMI to "Gün batımı", PrimaryExperienceCode.GUN_DOGUMU to "Gün doğumu",
            PrimaryExperienceCode.MANZARA to "Manzara", PrimaryExperienceCode.GECE_MANZARASI to "Gece manzarası",
            PrimaryExperienceCode.FOTOGRAF_NOKTASI to "Fotoğraf noktası", PrimaryExperienceCode.DENIZ_YUZME to "Deniz / Yüzme",
            PrimaryExperienceCode.PLAJ to "Plaj", PrimaryExperienceCode.TEKNE to "Tekne",
            PrimaryExperienceCode.DALIS_SNORKEL to "Dalış / Şnorkel", PrimaryExperienceCode.SU_AKTIVITESI to "Su aktivitesi",
            PrimaryExperienceCode.DOGA_YURUYUSU to "Doğa yürüyüşü", PrimaryExperienceCode.PIKNIK to "Piknik",
            PrimaryExperienceCode.KAMP to "Kamp", PrimaryExperienceCode.ORMAN to "Orman",
            PrimaryExperienceCode.GOL_SELALE to "Göl / Şelale", PrimaryExperienceCode.SEYIR_NOKTASI to "Seyir noktası",
            PrimaryExperienceCode.SOKAK_KESFI to "Sokak keşfi", PrimaryExperienceCode.MAHALLE_SEHIR_GEZISI to "Mahalle / Şehir gezisi",
            PrimaryExperienceCode.SAHIL_YURUYUSU to "Sahil yürüyüşü", PrimaryExperienceCode.GIZLI_KOSE to "Gizli köşe",
            PrimaryExperienceCode.ROTA_GEZI to "Rota / Gezi", PrimaryExperienceCode.MUZE to "Müze",
            PrimaryExperienceCode.TARIHI_YER to "Tarihi yer", PrimaryExperienceCode.MIMARI to "Mimari",
            PrimaryExperienceCode.YEREL_PAZAR to "Yerel pazar", PrimaryExperienceCode.YEREL_YASAM to "Yerel yaşam",
            PrimaryExperienceCode.SERGI_SANAT to "Sergi / Sanat", PrimaryExperienceCode.CANLI_MUZIK to "Canlı müzik",
            PrimaryExperienceCode.BAR_PUB to "Bar / Pub", PrimaryExperienceCode.GECE_HAYATI to "Gece hayatı",
            PrimaryExperienceCode.KONSER_GOSTERI to "Konser / Gösteri", PrimaryExperienceCode.SOSYAL_ETKINLIK to "Sosyal etkinlik",
            PrimaryExperienceCode.BISIKLET to "Bisiklet", PrimaryExperienceCode.TIRMANIS to "Tırmanış",
            PrimaryExperienceCode.KAYAK to "Kayak", PrimaryExperienceCode.SU_SPORU to "Su sporu",
            PrimaryExperienceCode.WORKSHOP to "Atölye", PrimaryExperienceCode.ACIK_HAVA_AKTIVITESI to "Açık hava aktivitesi",
            PrimaryExperienceCode.SAKIN_ZAMAN to "Sakin zaman", PrimaryExperienceCode.SPA_HAMAM to "Spa / Hamam",
            PrimaryExperienceCode.TERMAL to "Termal", PrimaryExperienceCode.YOGA_MEDITASYON to "Yoga / Meditasyon",
            PrimaryExperienceCode.DINLENME to "Dinlenme", PrimaryExperienceCode.OTEL to "Otel",
            PrimaryExperienceCode.BUTIK_OTEL to "Butik otel", PrimaryExperienceCode.HOSTEL to "Hostel",
            PrimaryExperienceCode.KAMP_KONAKLAMASI to "Kamp konaklaması", PrimaryExperienceCode.KIRALIK_EV_BUNGALOV to "Kiralık ev / Bungalov",
            PrimaryExperienceCode.OTHER to "Diğer deneyim", PrimaryExperienceCode.UNKNOWN to "Deneyim",
        )
        return (if (language == DisplayLanguage.TR) tr else en)[code]
    }

    fun feeling(code: OverallFeelingCode, language: DisplayLanguage, emoji: Boolean = true): String {
        val mark = when (code) {
            OverallFeelingCode.BAYILDIM -> "😍"; OverallFeelingCode.GUZELDI -> "🙂"; OverallFeelingCode.EH_ISTE -> "😐"
            OverallFeelingCode.BEKLENTIMI_KARSILAMADI -> "🙁"; OverallFeelingCode.BIR_DAHA_TERCIH_ETMEM -> "😞"; OverallFeelingCode.UNKNOWN -> "•"
        }
        val label = when (language) {
            DisplayLanguage.EN -> when (code) {
                OverallFeelingCode.BAYILDIM -> "Loved it"; OverallFeelingCode.GUZELDI -> "Liked it"; OverallFeelingCode.EH_ISTE -> "It was okay"
                OverallFeelingCode.BEKLENTIMI_KARSILAMADI -> "Not what I expected"; OverallFeelingCode.BIR_DAHA_TERCIH_ETMEM -> "Wouldn't choose again"; OverallFeelingCode.UNKNOWN -> "Feeling"
            }
            DisplayLanguage.TR -> when (code) {
                OverallFeelingCode.BAYILDIM -> "Bayıldım"; OverallFeelingCode.GUZELDI -> "Güzeldi"; OverallFeelingCode.EH_ISTE -> "Eh işte"
                OverallFeelingCode.BEKLENTIMI_KARSILAMADI -> "Beklentimi karşılamadı"; OverallFeelingCode.BIR_DAHA_TERCIH_ETMEM -> "Bir daha tercih etmem"; OverallFeelingCode.UNKNOWN -> "His"
            }
        }
        return if (emoji) "$mark $label" else label
    }

    fun companion(code: CompanionCode, language: DisplayLanguage): String = when (language) {
        DisplayLanguage.EN -> mapOf(CompanionCode.ALONE to "Solo", CompanionCode.PARTNER to "Partner", CompanionCode.FRIENDS to "Friends", CompanionCode.FAMILY to "Family", CompanionCode.CHILDREN to "With children", CompanionCode.UNKNOWN to "Company").getValue(code)
        DisplayLanguage.TR -> mapOf(CompanionCode.ALONE to "Tek başıma", CompanionCode.PARTNER to "Partnerimle", CompanionCode.FRIENDS to "Arkadaşlarla", CompanionCode.FAMILY to "Ailemle", CompanionCode.CHILDREN to "Çocuklarla", CompanionCode.UNKNOWN to "Kiminle").getValue(code)
    }

    fun time(code: TimeOfDayCode, language: DisplayLanguage): String = when (language) {
        DisplayLanguage.EN -> mapOf(TimeOfDayCode.MORNING to "Morning", TimeOfDayCode.DAYTIME to "Daytime", TimeOfDayCode.EVENING to "Evening", TimeOfDayCode.NIGHT to "Night", TimeOfDayCode.UNKNOWN to "Time").getValue(code)
        DisplayLanguage.TR -> mapOf(TimeOfDayCode.MORNING to "Sabah", TimeOfDayCode.DAYTIME to "Gün içinde", TimeOfDayCode.EVENING to "Akşam", TimeOfDayCode.NIGHT to "Gece", TimeOfDayCode.UNKNOWN to "Zaman").getValue(code)
    }

    fun vibe(code: VibeCode, language: DisplayLanguage): String = when (language) {
        DisplayLanguage.EN -> mapOf(VibeCode.CALM to "Calm", VibeCode.LIVELY to "Lively", VibeCode.ROMANTIC to "Romantic", VibeCode.SOCIAL to "Social", VibeCode.INTIMATE to "Cozy", VibeCode.LOCAL_AUTHENTIC to "Local / authentic", VibeCode.SCENIC to "Scenic", VibeCode.ADVENTUROUS to "Adventurous", VibeCode.UNKNOWN to "Vibe").getValue(code)
        DisplayLanguage.TR -> mapOf(VibeCode.CALM to "Sakin", VibeCode.LIVELY to "Canlı", VibeCode.ROMANTIC to "Romantik", VibeCode.SOCIAL to "Sosyal", VibeCode.INTIMATE to "Samimi", VibeCode.LOCAL_AUTHENTIC to "Yerel / otantik", VibeCode.SCENIC to "Manzaralı", VibeCode.ADVENTUROUS to "Maceralı", VibeCode.UNKNOWN to "Atmosfer").getValue(code)
    }

    fun practical(code: PracticalSignalCode, language: DisplayLanguage): String {
        val en = listOf("Accessible without a car", "Car recommended", "Parking is difficult", "Reservation recommended", "Walk-ins welcome", "Crowded on weekends", "May be crowded", "Calmer in the morning", "Ideal for sunset", "Good with children", "Pet friendly", "Walking required", "Arrive early", "Cash may be needed", "Free", "A quiet area is possible", "Practical detail")
        val tr = listOf("Arabasız gidilebilir", "Araç önerilir", "Park zor", "Rezervasyon önerilir", "Rezervasyonsuz gidilebilir", "Hafta sonu kalabalık", "Kalabalık olabilir", "Sabah daha sakin", "Gün batımı için ideal", "Çocuklarla uygun", "Evcil hayvanla uygun", "Yürümek gerekiyor", "Erken gitmek iyi olur", "Nakit gerekebilir", "Ücretsiz", "Sessiz alan bulmak mümkün", "Pratik bilgi")
        return (if (language == DisplayLanguage.TR) tr else en)[code.ordinal]
    }

    fun dimensionState(code: DimensionStateCode, language: DisplayLanguage): String = when (language) {
        DisplayLanguage.EN -> mapOf(DimensionStateCode.VERY_GOOD to "Very good", DimensionStateCode.GOOD to "Good", DimensionStateCode.MEDIUM to "Medium", DimensionStateCode.WEAK to "Weak", DimensionStateCode.VERY_WEAK to "Very weak", DimensionStateCode.UNKNOWN to "Select").getValue(code)
        DisplayLanguage.TR -> mapOf(DimensionStateCode.VERY_GOOD to "Çok iyi", DimensionStateCode.GOOD to "İyi", DimensionStateCode.MEDIUM to "Orta", DimensionStateCode.WEAK to "Zayıf", DimensionStateCode.VERY_WEAK to "Çok zayıf", DimensionStateCode.UNKNOWN to "Seç").getValue(code)
    }

    fun dimensionKey(key: String, language: DisplayLanguage): String {
        val normalized = key.uppercase(Locale.ROOT)
        val en = mapOf("FOOD" to "Food", "SERVICE" to "Service", "ATMOSPHERE" to "Atmosphere", "VALUE" to "Value", "SCENERY" to "Scenery", "TRANQUILITY" to "Calm", "ACCESS" to "Access", "SEA" to "Sea / water", "CLEANLINESS" to "Cleanliness", "COMFORT" to "Comfort", "ROUTE" to "Route", "WALKABILITY" to "Walkability", "LOCALITY" to "Local character", "DISCOVERY_VALUE" to "Discovery value", "CONTENT_INTEREST" to "Content / interest", "MUSIC_ENTERTAINMENT" to "Music / entertainment", "FUN" to "Fun", "ORGANIZATION" to "Organization", "COMFORT_DIFFICULTY" to "Comfort / difficulty", "LOCATION" to "Location")
        val tr = mapOf("FOOD" to "Lezzet", "SERVICE" to "Servis", "ATMOSPHERE" to "Atmosfer", "VALUE" to "Fiyat / Performans", "SCENERY" to "Manzara", "TRANQUILITY" to "Sakinlik", "ACCESS" to "Ulaşım", "SEA" to "Deniz / Su", "CLEANLINESS" to "Temizlik", "COMFORT" to "Konfor", "ROUTE" to "Rota", "WALKABILITY" to "Yürünebilirlik", "LOCALITY" to "Yerellik", "DISCOVERY_VALUE" to "Keşif değeri", "CONTENT_INTEREST" to "İçerik / İlgi", "MUSIC_ENTERTAINMENT" to "Müzik / Eğlence", "FUN" to "Eğlence", "ORGANIZATION" to "Organizasyon", "COMFORT_DIFFICULTY" to "Konfor / Zorluk", "LOCATION" to "Konum")
        return (if (language == DisplayLanguage.TR) tr else en)[normalized] ?: if (language == DisplayLanguage.TR) "Deneyim detayı" else "Experience detail"
    }
}

fun shouldShowExperienceTitle(title: String, placeName: String, classification: ExperienceClassification): Boolean =
    classification != ExperienceClassification.LEGACY_COMPATIBILITY || !title.trim().equals(placeName.trim(), ignoreCase = true)
