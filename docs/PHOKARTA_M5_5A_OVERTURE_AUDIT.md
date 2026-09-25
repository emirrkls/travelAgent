# Phokarta M5.5A — Overture miss and gold-set audit

## Scope and anti-bias controls

This audit was completed before any Foursquare data was queried. It covers all
47 misses from the first Overture run and all 60 gold fixtures. Corrections were
limited to independently supported facts and provider-neutral benchmark behavior.
The 250 m gold distance, 0.95 near-exact-name, 0.88 category-compatible-name,
0.03 ambiguity, 30 m duplicate, and 50 m cross-provider thresholds were not
changed.

The original adapter used only `names.primary`. Overture schema v2 defines
`names.common` plus `names.rules` for common, official, alternate, and short
names. Retaining those source-supplied variants is a parsing fix, not a threshold
change. See the official Overture [Names](https://docs.overturemaps.org/schema/reference/common/names/),
[NameRule](https://docs.overturemaps.org/schema/reference/common/name_rule/), and
[NameVariant](https://docs.overturemaps.org/schema/reference/common/name_variant/)
definitions.

The gold model now supports factual, frozen name variants. Variants are checked
symmetrically for every provider. Squares and marinas no longer masquerade as
parks or viewpoints: they remain `UNMAPPED` because the neutral benchmark has no
square or marina bucket. The Foça radius is 12 km because the previous 10 km
circle contradicted its own stated Old/New Foça scope and excluded the pre-existing
Yeni Foça fixture by about 302 m. No fixture was added or removed.

## Frozen Overture rerun

- Release: `2026-09-23.0`
- Schema: `v2.0.0`
- Retrieval: `2026-09-25T10:35:38.128595+00:00`
- Output: `C:\Users\Emir\Documents\Phokarta_Place_Benchmark\M5_5A\20260925_1335_frozen`
- Raw: 153,073
- Usable: 143,211 (93.557%)
- Gold: 30 matched, 2 ambiguous, 28 missing (50.000% raw recall)
- Query time across six areas: 161.387 s
- Duplicate candidates: 456 (128 high-confidence, 328 ambiguous)
- Category mapping: 37.553% unweighted area mean

| Area | Matched | Ambiguous | Missing | Recall |
|---|---:|---:|---:|---:|
| Didim | 5 | 1 | 4 | 50% |
| Foça | 5 | 0 | 5 | 50% |
| Bodrum | 3 | 0 | 7 | 30% |
| Çeşme | 3 | 0 | 7 | 30% |
| Kadıköy | 6 | 0 | 4 | 60% |
| Antalya Kaleiçi | 8 | 1 | 1 | 80% |

An earlier folder named `20260925_1332_frozen` records a dependency-gate failure
with zero completed providers. It is not a benchmark result and was not reused.

## Audit of the original 47 misses

The classification is the primary verified reason for the original miss. A
provider record can have more than one weakness; the notes retain that nuance.

| Area | Gold Place | Primary classification | Frozen rerun | Verified reason |
|---|---|---|---|---|
| Didim | Altınkum Plajı | COORDINATE_THRESHOLD_EFFECT | MISSING | Exact-name provider point is about 2.4 km from the independently retained beach anchor. |
| Didim | İkinci Koy | MATCHER_FALSE_NEGATIVE | MISSING | A nearby record uses the noisy name “Didim 2.ci Koyda”; the conservative name gate correctly remains unchanged. |
| Didim | Üçüncü Koy | MATCHER_FALSE_NEGATIVE | MISSING | A nearby beach record has a truncated/noisy suffix and remains below the frozen name gate. |
| Didim | Tavşanburnu Tabiat Parkı | GOLD_FIXTURE_ISSUE | MATCHED | Anchor corrected to the documented nature-park location; exact name now matches. |
| Didim | Didim Marina | NAME_VARIANT_EFFECT | MATCHED | Official D-Marin aliases recover the record; neutral category corrected to `UNMAPPED`. |
| Didim | Manastır Koyu | GOLD_FIXTURE_ISSUE | MATCHED | The prior anchor was about 0.9 km east; corrected anchor is 7 m from the exact-name record. |
| Didim | Cennet Koyu | AMBIGUOUS_ENTITY | MISSING | The name is used for multiple informal coastal anchors; no safe provider identity was found. |
| Didim | Didim Kent Meydanı | GOLD_FIXTURE_ISSUE | MATCHED | Correct official identity is Cumhuriyet Kent Meydanı; coordinate and neutral category were corrected. |
| Didim | Didim Halk Pazarı | GOLD_FIXTURE_ISSUE | MATCHED | Generic label was resolved to the existing central Didim Pazar Yeri rather than replacing the hard fixture. |
| Foça | Karakum Plajı | GOLD_FIXTURE_ISSUE | MATCHED | Prior coordinate was about 1.5 km north; corrected beach anchor matches within 5 m. |
| Foça | Mersinaki Koyu | ENTITY_GRANULARITY_DIFFERENCE | MISSING | Multiple points describe the long bay/coast; the closest plausible name is outside the point threshold. |
| Foça | Siren Kayalıkları | ENTITY_GRANULARITY_DIFFERENCE | MISSING | Offshore rock-group geometry and a visitor anchor are not equivalent point geometries. |
| Foça | Fatih Camii | GOLD_FIXTURE_ISSUE | MATCHED | Building coordinate corrected; exact historical-place record is 9 m away. |
| Foça | Foça Yeryüzü Pazarı | COORDINATE_THRESHOLD_EFFECT | MISSING | Exact-name market record is about 266 m away, just outside the frozen limit. |
| Foça | İngiliz Burnu | ENTITY_GRANULARITY_DIFFERENCE | MISSING | Peninsula/viewpoint anchors vary across a broad natural feature. |
| Foça | Yeni Foça Halk Plajı | OTHER_VERIFIED_REASON | MISSING | Original 10 km pilot contradicted the declared New Foça scope; scope is fixed, but no safe match remains. |
| Bodrum | Bodrum Kalesi | NAME_VARIANT_EFFECT | MISSING | Correct-location records expose non-Turkish primaries while exact Turkish records have displaced coordinates; no safe automatic match. |
| Bodrum | Halikarnas Mozolesi | COORDINATE_THRESHOLD_EFFECT | MISSING | Exact-name provider point is about 300 m from the independently retained monument anchor. |
| Bodrum | Bodrum Antik Tiyatrosu | GOLD_FIXTURE_ISSUE | MISSING | Gold anchor corrected to the documented theatre; provider geometry remains materially displaced. |
| Bodrum | Zeki Müren Sanat Müzesi | GOLD_FIXTURE_ISSUE | MISSING | Gold anchor corrected; remaining provider name is still below the conservative alias/category gate. |
| Bodrum | Bodrum Yel Değirmenleri | GOLD_FIXTURE_ISSUE | MISSING | Anchor corrected to the Değirmenburnu windmill ridge; provider points still represent different group/point granularity. |
| Bodrum | Bitez Plajı | ENTITY_GRANULARITY_DIFFERENCE | MISSING | Long beachfront and named access-point geometries differ. |
| Bodrum | Gümbet Plajı | ENTITY_GRANULARITY_DIFFERENCE | MISSING | Long beachfront, marina, and access-point identities are not safe substitutes. |
| Çeşme | Çeşme Kalesi | NAME_VARIANT_EFFECT | MATCHED | Factual English/transliterated variant recovers the nearby castle. |
| Çeşme | Çeşme Müzesi | NAME_VARIANT_EFFECT | MATCHED | Factual English/transliterated variant recovers the nearby museum. |
| Çeşme | Çeşme Marina | COORDINATE_THRESHOLD_EFFECT | MISSING | Exact-name provider points are roughly 420 m away; category corrected to `UNMAPPED`. |
| Çeşme | Ayios Haralambos Kilisesi | NAME_VARIANT_EFFECT | MATCHED | Aya/Çeşme naming variants recover the nearby historical Place. |
| Çeşme | Ilıca Plajı | ENTITY_GRANULARITY_DIFFERENCE | MISSING | Beach polygon/access anchors differ by about 400 m. |
| Çeşme | Altınkum Plajı | ENTITY_GRANULARITY_DIFFERENCE | MISSING | Multiple beach/club/access anchors span a broad coastal feature. |
| Çeşme | Pırlanta Plajı | ENTITY_GRANULARITY_DIFFERENCE | MISSING | Broad beach geometry differs materially across source anchors. |
| Çeşme | Alaçatı Değirmenleri | COORDINATE_THRESHOLD_EFFECT | MISSING | Exact-name provider point is about 427 m from the retained windmill anchor. |
| Çeşme | Alaçatı Pazarı | COORDINATE_THRESHOLD_EFFECT | MISSING | Plausible Pazar Yeri record is over 0.5 km away and the name is not near-exact. |
| Çeşme | Delikli Koy | ENTITY_GRANULARITY_DIFFERENCE | MISSING | Named cove/beach points vary by kilometers across the coastal feature. |
| Kadıköy | Haydarpaşa Garı | NAME_VARIANT_EFFECT | MATCHED | Turkish/English train-station aliases recover the record at 12 m. |
| Kadıköy | Süreyya Operası | NAME_VARIANT_EFFECT | MATCHED | Official English opera-house alias recovers the nearby record. |
| Kadıköy | Barış Manço Evi | GOLD_FIXTURE_ISSUE | MATCHED | Address-derived building coordinate corrected; exact name matches within 5 m. |
| Kadıköy | İstanbul Oyuncak Müzesi | GOLD_FIXTURE_ISSUE | MATCHED | Official-address building coordinate corrected; exact name matches within 10 m. |
| Kadıköy | Müze Gazhane | TRUE_PROVIDER_MISSING | MISSING | No usable nearby record exposes the verified museum identity or factual aliases. |
| Kadıköy | Kadıköy Çarşısı | CATEGORY_MAPPING_EFFECT | MISSING | Nearby fuzzy name is plausible, but source taxonomy remains `UNMAPPED`; the mapping is not guessed. |
| Kadıköy | Moda Sahil Parkı | ENTITY_GRANULARITY_DIFFERENCE | MISSING | Elongated coastal park and “Moda Parkı” anchors do not form a safe point identity. |
| Kadıköy | Kadıköy Boğa Heykeli | NAME_VARIANT_EFFECT | MATCHED | Factual short name “Boğa Heykeli” recovers the monument. |
| Kadıköy | Caddebostan Plajı | ENTITY_GRANULARITY_DIFFERENCE | MISSING | The named beach has multiple numbered sections and a broader “sahil” geometry. |
| Antalya Kaleiçi | Hadrian Kapısı | MATCHER_FALSE_NEGATIVE | MISSING | Nearby provider name is malformed (“Hadrian Kale Kalpısı”); relaxing the gate would increase false-link risk. |
| Antalya Kaleiçi | Hıdırlık Kulesi | NAME_VARIANT_EFFECT | MATCHED | English/transliterated tower alias recovers the record. |
| Antalya Kaleiçi | Yivli Minare | NAME_VARIANT_EFFECT | MATCHED | Minaret/mosque variants recover the historical Place. |
| Antalya Kaleiçi | Antalya Saat Kulesi | NAME_VARIANT_EFFECT | AMBIGUOUS | Factual short name exposes more than one near-equal candidate; ambiguity is retained. |
| Antalya Kaleiçi | Antalya Oyuncak Müzesi | NAME_VARIANT_EFFECT | MATCHED | The documented Antalya Çocuk Müzesi variant recovers the museum. |

Classification counts: `TRUE_PROVIDER_MISSING=1`, `MATCHER_FALSE_NEGATIVE=3`,
`GOLD_FIXTURE_ISSUE=11`, `AMBIGUOUS_ENTITY=1`,
`ENTITY_GRANULARITY_DIFFERENCE=11`, `CATEGORY_MAPPING_EFFECT=1`,
`COORDINATE_THRESHOLD_EFFECT=6`, `NAME_VARIANT_EFFECT=12`, and
`OTHER_VERIFIED_REASON=1`.

## All 60 gold fixtures reviewed

`VERIFIED` means identity, approximate location, neutral category, and the retained
source were reviewed and no objective correction was justified. `CORRECTED` means
the pre-FSQ fixture was changed using public/official evidence. `ALIASES` means only
factual names were added; it does not change distance or score thresholds.

| # | Area | Place | Review disposition |
|---:|---|---|---|
| 1 | Didim | Apollon Tapınağı | VERIFIED + ALIASES (Didyma/English) |
| 2 | Didim | Altınkum Plajı | VERIFIED; difficult provider geometry retained |
| 3 | Didim | İkinci Koy | CORRECTED coordinate/source + ALIASES (2. Koy) |
| 4 | Didim | Üçüncü Koy | CORRECTED coordinate/source + ALIASES (3. Koy) |
| 5 | Didim | Tavşanburnu Tabiat Parkı | CORRECTED coordinate + spelling variant |
| 6 | Didim | Didim Marina | CORRECTED category to `UNMAPPED` + official D-Marin aliases |
| 7 | Didim | Manastır Koyu | CORRECTED coordinate/source |
| 8 | Didim | Cennet Koyu | VERIFIED; hard/ambiguous coastal identity retained |
| 9 | Didim | Cumhuriyet Kent Meydanı | CORRECTED official name, coordinate, source, and category |
| 10 | Didim | Didim Pazar Yeri | CORRECTED unique venue name, coordinate, and source |
| 11 | Foça | Beşkapılar Kalesi | VERIFIED |
| 12 | Foça | Foça Kalesi | VERIFIED |
| 13 | Foça | Karakum Plajı | CORRECTED coordinate + halk-plajı alias |
| 14 | Foça | Mersinaki Koyu | VERIFIED; broad-bay anchor retained |
| 15 | Foça | Siren Kayalıkları | VERIFIED; rock-group identity retained |
| 16 | Foça | Fatih Camii | CORRECTED coordinate + Foça alias |
| 17 | Foça | Foça Yeryüzü Pazarı | VERIFIED |
| 18 | Foça | Foça Demokrasi Meydanı | CORRECTED coordinate and category to `UNMAPPED` |
| 19 | Foça | İngiliz Burnu | VERIFIED; broad peninsula/viewpoint retained |
| 20 | Foça | Yeni Foça Halk Plajı | CORRECTED coordinate + Yenifoça alias; pilot scope corrected separately |
| 21 | Bodrum | Bodrum Kalesi | VERIFIED + official English aliases |
| 22 | Bodrum | Bodrum Sualtı Arkeoloji Müzesi | VERIFIED + English alias |
| 23 | Bodrum | Halikarnas Mozolesi | VERIFIED + Halicarnassus aliases |
| 24 | Bodrum | Bodrum Antik Tiyatrosu | CORRECTED coordinate + factual English aliases |
| 25 | Bodrum | Zeki Müren Sanat Müzesi | CORRECTED coordinate + English alias |
| 26 | Bodrum | Myndos Kapısı | VERIFIED |
| 27 | Bodrum | Bodrum Deniz Müzesi | VERIFIED |
| 28 | Bodrum | Bodrum Yel Değirmenleri | CORRECTED ridge coordinate/source + aliases |
| 29 | Bodrum | Bitez Plajı | VERIFIED; broad beachfront retained |
| 30 | Bodrum | Gümbet Plajı | VERIFIED; broad beachfront retained |
| 31 | Çeşme | Çeşme Kalesi | VERIFIED + English/transliterated aliases |
| 32 | Çeşme | Çeşme Müzesi | VERIFIED + English/transliterated aliases |
| 33 | Çeşme | Çeşme Marina | CORRECTED category to `UNMAPPED` + transliterated alias |
| 34 | Çeşme | Ayios Haralambos Kilisesi | VERIFIED + Aya/Çeşme aliases |
| 35 | Çeşme | Ilıca Plajı | VERIFIED; broad beach anchor retained |
| 36 | Çeşme | Altınkum Plajı | VERIFIED; broad beach anchor retained |
| 37 | Çeşme | Pırlanta Plajı | VERIFIED; broad beach anchor retained |
| 38 | Çeşme | Alaçatı Değirmenleri | VERIFIED |
| 39 | Çeşme | Alaçatı Pazarı | VERIFIED |
| 40 | Çeşme | Delikli Koy | VERIFIED; broad coastal identity retained |
| 41 | Kadıköy | Haydarpaşa Garı | VERIFIED + Turkish/English transport aliases |
| 42 | Kadıköy | Süreyya Operası | VERIFIED + English/official aliases |
| 43 | Kadıköy | Barış Manço Evi | CORRECTED coordinate and official source + aliases |
| 44 | Kadıköy | İstanbul Oyuncak Müzesi | CORRECTED coordinate and official contact source + English alias |
| 45 | Kadıköy | Müze Gazhane | VERIFIED + Hasanpaşa/English aliases |
| 46 | Kadıköy | Kadıköy Çarşısı | VERIFIED |
| 47 | Kadıköy | Moda Sahil Parkı | VERIFIED; elongated park anchor retained |
| 48 | Kadıköy | Fenerbahçe Parkı | VERIFIED |
| 49 | Kadıköy | Kadıköy Boğa Heykeli | VERIFIED + short/English aliases |
| 50 | Kadıköy | Caddebostan Plajı | VERIFIED; multi-section beach anchor retained |
| 51 | Antalya Kaleiçi | Hadrian Kapısı | VERIFIED + Hadrianus/Üçkapılar/English aliases |
| 52 | Antalya Kaleiçi | Hıdırlık Kulesi | VERIFIED + English/transliterated alias |
| 53 | Antalya Kaleiçi | Yivli Minare | VERIFIED + minaret/mosque aliases |
| 54 | Antalya Kaleiçi | Antalya Müzesi | VERIFIED + archaeology/English aliases |
| 55 | Antalya Kaleiçi | Suna İnan Kıraç Kaleiçi Müzesi | VERIFIED + English alias |
| 56 | Antalya Kaleiçi | Karaalioğlu Parkı | VERIFIED |
| 57 | Antalya Kaleiçi | Antalya Saat Kulesi | VERIFIED + short/English aliases |
| 58 | Antalya Kaleiçi | Kesik Minare | VERIFIED |
| 59 | Antalya Kaleiçi | Mermerli Plajı | VERIFIED |
| 60 | Antalya Kaleiçi | Antalya Oyuncak Müzesi | VERIFIED official source + Çocuk/English aliases |

## Evidence highlights for objective corrections

- Didim nature-park identity and location: [official protected-area inventory](https://www.tarimorman.gov.tr/DKMP/Belgeler/Korunan%20Alanlar%20Listesi/Tabiat%20Parklar%C4%B1.pdf).
- Didim Cumhuriyet Kent Meydanı identity: [Didim municipal map](https://harita.didim.bel.tr/Home/Detail?id=117).
- D-Marin identity: [official Didim marina page](https://www.d-marin.com/en/marinas/didim/).
- Bodrum windmill ridge scope: [Bodrum municipality competition brief](https://www.bodrum.bel.tr/yarisma/yeldegirmenleri/assets/DegirmenburnuTarihiYelDegirmenleriYarismasiSartnamesi.pdf).
- Barış Manço Evi address: [Kadıköy municipality](https://barismanco.kadikoy.bel.tr/iletisim).
- İstanbul Oyuncak Müzesi address: [museum contact page](https://istanbuloyuncakmuzesi.com/pages/iletisim).
- Antalya Oyuncak Müzesi identity and address: [official museum page](https://oyuncakmuzesi.antalya.bel.tr/).

No production import, database migration, mobile change, canonical merge, or
provider decision was performed.
