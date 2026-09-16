
# PHOKARTA V2 PRODUCT CONTRACT

Version: 1.0
Date: 2026-09-16
Status: LOCKED FOR ARCHITECTURE / MIGRATION AUDIT

This document defines the target product behavior for Phokarta V2.

It intentionally describes WHAT the product must become, not HOW it must be implemented.

The existing Phokarta backend, Android application, iOS application, database, APIs, offline systems, media systems, security rules and social graph must be audited against this contract before implementation begins.

The migration must preserve the currently working beta wherever possible.

This document is the product source of truth for the V2 migration audit.

---

# 1. PRODUCT VISION

Phokarta must not position itself primarily as:

- a place directory,
- a generic review application,
- a numeric rating application,
- an Instagram clone,
- a TikTok clone,
- or a Google Maps clone.

The core Phokarta product model is:

PLACE
→ EXPERIENCE
→ PERSON

The canonical Place remains important, but the primary unit of user-generated content is the Experience Card.

The main question Phokarta should answer is:

“İnsanlar orada ne yaşadı?”

rather than only:

“Burası nerede?”
“Puanı kaç?”
“İnsanlar ne paylaştı?”

The intended brand and product character is:

- warm,
- simple,
- social,
- modern,
- slightly premium,
- playful but not childish,
- discovery-oriented,
- not influencer-dominated,
- low cognitive load,
- photo-friendly,
- human-centered.

---

# 2. CORE DOMAIN MODEL

The target conceptual chain is:

PLACE
↓
PRIMARY EXPERIENCE
↓
OVERALL FEELING
+
CONTEXT
+
PRACTICAL SIGNALS
+
OPTIONAL DIMENSIONS
+
STORY
+
TIP
+
MEDIA
↓
EXPERIENCE CARD

Definitions:

PLACE
A canonical physical place, business, destination, attraction or geographic point.

EXPERIENCE
What a person actually did or experienced at that Place.

EXPERIENCE CARD
The primary user-generated content object presented throughout Phokarta.

The Experience Card becomes the central content unit of V2.

A Place may contain many completely different Experiences.

Example:

Place:
Kavala Beach

Possible Experiences:
- Gün Batımı
- Deniz
- Sakin Zaman
- Fotoğraf Noktası
- Sahil Yürüyüşü

Place category and Experience type are not the same concept.

---

# 3. ROOT NAVIGATION

The target V2 root navigation is:

Keşfet | Harita | + | Planım | Profil

Keşfet:
Experience-first discovery feed.

Harita:
Geographic discovery.

+:
Create an Experience Card.

Planım:
Saved Experiences, Saved Places and Collections.

Profil:
Identity, social graph, discovery history, badges and Experience Cards.

Full trip itinerary planning is NOT required in the first V2 migration.

---

# 4. EXPLORE

Explore must be Experience-first.

It must not primarily look like a list of businesses.

The upper discovery surface should support the question:

“Bugün ne yaşamak istiyorsun?”

Search concept:

“Deneyim, yer veya his ara”

The main Explore lenses are:

For You
Following
Nearby
Popular

Recommended initial ordering:

For You | Following | Nearby | Popular

For You is the default lens.

---

# 5. FOR YOU

For You is the primary personalized discovery feed.

It should primarily contain Experience Cards.

Supporting Place Cards may also appear.

For You may eventually use signals such as:

- Experiences previously viewed,
- Experiences added to Planım,
- user-created Experiences,
- followed people,
- Experience taxonomy preferences,
- geographic interests,
- Saved Places,
- Collections,
- social signals.

The first V2 implementation does not require a complex machine-learning recommendation system.

A deterministic/basic recommendation strategy is acceptable initially.

---

# 6. FOLLOWING

Following is a meaningful product surface.

The Follow graph must not be treated as decorative.

Following primarily displays Experience Cards from accounts the viewer follows and is authorized to see.

Follow means:

“This person's discovery style interests me and I want to see their new Experiences.”

Following should remain useful for future:

- creators,
- premium features,
- creator analytics,
- commercial partnerships,
- business relationships.

Follower count must not dominate Experience Cards visually, but the Follow relationship itself is important.

---

# 7. NEARBY

Nearby is geographic discovery.

It may contain a higher ratio of Place Cards than the other Explore lenses.

Nearby must remain useful if location permission is denied.

Possible fallback behavior:

- manually selected city,
- manually selected region,
- current map viewport.

Phokarta must not require persistent GPS history for Nearby to work.

---

# 8. POPULAR

Popular must not simply mean:

“Most Likes”

because generic Like count is not the main Phokarta value signal.

Possible future Popular ranking signals include:

- Planıma Ekle count,
- Ben de Yaşadım count,
- Experience Detail opens,
- Follow conversion,
- Question/Comment interaction,
- recency,
- meaningful planning behavior.

Exact ranking is an implementation concern.

---

# 9. GENERIC LIKE

Generic Like is NOT a required core V2 action.

The primary meaningful interactions are:

- Takip Et
- Planıma Ekle
- Ben de Yaşadım
- Sorular & Yorumlar
- Kendi Deneyimini Oluştur

Like may be reconsidered later.

No V2 migration should depend on Like.

---

# 10. EXPERIENCE CARD — FEED PREVIEW

The Experience Card preview must be easy to scan.

It must NOT attempt to show every field.

The feed card should generally support:

- hero / cover media,
- media count if multiple media items exist,
- author identity,
- author avatar,
- author title,
- Follow control,
- Experience title,
- short story preview,
- overall feeling,
- maximum approximately 3 useful context indicators,
- optional Tip preview,
- Place name,
- Detayı Gör / Kartı Aç,
- Sorular & Yorumlar,
- Planıma Ekle,
- Ben de Yaşadım.

The card must NOT become overloaded with:

- every dimension,
- every practical signal,
- full story,
- full gallery,
- full Place metadata,
- telephone,
- opening hours,
- detailed address,
- many statistics,
- many colors,
- many icons.

Primary principle:

PHOTO
→ STORY
→ USEFUL CONTEXT
→ INTERACTION

The card should make the user think:

“Bunu ben de yapmak ister miyim?”

or:

“Bu bana uygun mu?”

---

# 11. EXPERIENCE CARD AUTHOR IDENTITY

The Experience Card must preserve the importance of the author.

Author identity should not be visually erased.

The Follow action may appear directly on the card.

However, large follower counts should not dominate the card.

Creator identity and Experience content should remain balanced.

---

# 12. EXPERIENCE CARD DETAIL / EXPANSION

Opening an Experience Card should feel like opening the card itself.

Preferred interaction concept:

Feed Card
→ slight elevation
→ card expands
→ hero media grows
→ detailed information becomes visible

The animation should feel specific to Phokarta.

It may subtly reference a physical collectible card.

It must remain:

- fast,
- smooth,
- functional,
- not distracting.

Accessibility settings such as reduced motion should be respected.

Exact animation implementation and duration are not part of this contract.

---

# 13. EXPANDED EXPERIENCE CARD

The expanded Experience surface should be able to expose:

- full media gallery,
- author identity,
- Follow state,
- full title,
- full story,
- overall feeling,
- context,
- optional dimensions,
- practical signals,
- Tip,
- Place link,
- Place actions,
- Planıma Ekle,
- Ben de Yaşadım,
- Questions & Comments,
- people who also lived the Experience,
- related versions of the Experience.

The feed card remains lightweight.

The expanded card is the rich representation.

---

# 14. EXPERIENCE MEDIA

An Experience Card supports:

0 to 6 images.

Media is NOT mandatory.

If media exists:

- one image acts as the primary/cover visual,
- multiple media items may be browsed in the expanded Experience.

The feed may show a media counter such as:

1 / 4

The full gallery should primarily live in the expanded card.

---

# 15. NO-MEDIA EXPERIENCE CARD

A valid Experience may exist without a photo.

If no media is provided, Phokarta may create a branded typographic card using:

- Experience symbol,
- title,
- Place,
- soft brand palette,
- typographic hierarchy.

Photo-less Experiences must not appear visually broken.

---

# 16. EXPERIENCE CREATION

Experience creation should be fast.

Target mental model:

approximately 20–30 seconds for a basic Experience.

Recommended flow:

Place
↓
Ne yaşadın?
↓
Nasıl hissettin?
↓
Context
↓
Media
↓
Story / Tip
↓
Card preview
↓
Publish

The user should not be presented with a giant empty review form.

Structured choices should reduce effort.

---

# 17. MINIMUM EXPERIENCE CARD CONTENT

Required:

- Place,
- Primary Experience,
- Overall Feeling,

PLUS at least ONE of:

- Media,
- Story,
- Tip.

A completely empty Experience containing only metadata must not be publishable.

---

# 18. EXPERIENCE TITLE

Experience title is automatically suggested by Phokarta.

Example source data:

Gün Batımı
Partnerimle
Sakin
Foça

Possible automatic title:

“Foça’da sakin bir gün batımı”

The user MAY edit the title.

Writing a title manually must not be required for publication.

---

# 19. STORY

Story answers:

“Sen ne yaşadın?”

Story is:

- personal,
- free-form,
- optional,
- potentially longer than the Tip.

Example:

“19.00 gibi geldik. Kayalıkların üstünde oturduk, hava sakindi ve güneş batarken ortam gerçekten çok güzeldi.”

The feed should show only a short preview.

The expanded card may show the complete Story.

---

# 20. TIP

Tip answers:

“Buraya gelecek biri ne bilmeli?”

Tip is:

- short,
- practical,
- optional,
- useful to the next visitor.

Example:

“Gün batımından 30 dakika önce gel.”

or:

“Hafta sonu park bulmak zor.”

Story and Tip are separate product concepts.

They must not be collapsed into the same field by default.

---

# 21. OVERALL FEELING

The primary overall rating interaction is emotional, not numeric.

Target options:

😍 Bayıldım
🙂 Güzeldi
😐 Eh işte
🙁 Beklentimi karşılamadı
😞 Bir daha tercih etmem

The UI must prefer this model instead of a primary 0–10 overall slider.

The backend MAY internally normalize these values numerically for:

- ranking,
- aggregate calculations,
- recommendation,
- backward compatibility.

Users do not need to see the normalized value as a 0–10 overall score.

---

# 22. DIMENSION RATINGS

Dimension ratings remain useful.

They are separate from Overall Feeling.

Dimension values should use human-readable states such as:

Çok iyi
İyi
Orta
Zayıf
Çok zayıf

Dimension ratings are optional.

The user must not be forced to complete every dimension before publishing.

Dimension templates are primarily determined by Experience Family.

---

# 23. DIMENSION TEMPLATE — YEME & İÇME

- Lezzet
- Servis
- Atmosfer
- Fiyat / Performans

---

# 24. DIMENSION TEMPLATE — MANZARA & AN

- Manzara
- Atmosfer
- Sakinlik
- Ulaşım

---

# 25. DIMENSION TEMPLATE — DENİZ & SU

- Deniz / Su
- Temizlik
- Konfor
- Ulaşım

---

# 26. DIMENSION TEMPLATE — DOĞA & AÇIK HAVA

- Doğa / Manzara
- Rota
- Sakinlik
- Ulaşım

---

# 27. DIMENSION TEMPLATE — GEZİ & KEŞİF

- Atmosfer
- Yürünebilirlik
- Yerellik
- Keşif Değeri

---

# 28. DIMENSION TEMPLATE — KÜLTÜR & YEREL YAŞAM

- İçerik / İlgi
- Atmosfer
- Erişim
- Fiyat / Değer

---

# 29. DIMENSION TEMPLATE — EĞLENCE & GECE

- Atmosfer
- Müzik / Eğlence
- Servis
- Fiyat / Performans

---

# 30. DIMENSION TEMPLATE — AKTİVİTE & MACERA

- Eğlence
- Organizasyon
- Konfor / Zorluk
- Fiyat / Değer

---

# 31. DIMENSION TEMPLATE — DİNLENME & WELLNESS

- Atmosfer
- Konfor
- Temizlik
- Fiyat / Değer

---

# 32. DIMENSION TEMPLATE — KONAKLAMA

- Temizlik
- Konfor
- Konum
- Hizmet

---

# 33. EXPERIENCE TAXONOMY PRINCIPLE

Phokarta must use a controlled canonical Experience taxonomy.

The user must NOT create arbitrary canonical Experience types.

However, the user must not be forced to browse a huge taxonomy.

The user-facing question is:

“Ne yaşadın?”

The internal Experience Family should normally remain invisible.

---

# 34. EXPERIENCE FAMILY — YEME & İÇME

Canonical Experiences:

- Kahvaltı
- Öğün / Yemek
- Kahve
- Tatlı
- Sokak Lezzeti
- Yerel Lezzet

---

# 35. EXPERIENCE FAMILY — MANZARA & AN

Canonical Experiences:

- Gün Batımı
- Gün Doğumu
- Manzara
- Gece Manzarası
- Fotoğraf Noktası

---

# 36. EXPERIENCE FAMILY — DENİZ & SU

Canonical Experiences:

- Deniz / Yüzme
- Plaj
- Tekne
- Dalış / Şnorkel
- Su Aktivitesi

---

# 37. EXPERIENCE FAMILY — DOĞA & AÇIK HAVA

Canonical Experiences:

- Doğa Yürüyüşü
- Piknik
- Kamp
- Orman
- Göl / Şelale
- Seyir Noktası

---

# 38. EXPERIENCE FAMILY — GEZİ & KEŞİF

Canonical Experiences:

- Sokak Keşfi
- Mahalle / Şehir Gezisi
- Sahil Yürüyüşü
- Gizli Köşe
- Rota / Gezi

---

# 39. EXPERIENCE FAMILY — KÜLTÜR & YEREL YAŞAM

Canonical Experiences:

- Müze
- Tarihi Yer
- Mimari
- Yerel Pazar
- Yerel Yaşam
- Sergi / Sanat

---

# 40. EXPERIENCE FAMILY — EĞLENCE & GECE

Canonical Experiences:

- Canlı Müzik
- Bar / Pub
- Gece Hayatı
- Konser / Gösteri
- Sosyal Etkinlik

---

# 41. EXPERIENCE FAMILY — AKTİVİTE & MACERA

Canonical Experiences:

- Bisiklet
- Tırmanış
- Kayak
- Su Sporu
- Workshop
- Açık Hava Aktivitesi

---

# 42. EXPERIENCE FAMILY — DİNLENME & WELLNESS

Canonical Experiences:

- Sakin Zaman
- Spa / Hamam
- Termal
- Yoga / Meditasyon
- Dinlenme

---

# 43. EXPERIENCE FAMILY — KONAKLAMA

Canonical Experiences:

- Otel
- Butik Otel
- Hostel
- Kamp Konaklaması
- Kiralık Ev / Bungalov

---

# 44. PLACE CATEGORY VS EXPERIENCE TYPE

Place category and Experience type are independent concepts.

Example:

Place Category:
BEACH

Possible Experiences:
- Deniz
- Gün Batımı
- Sakin Zaman
- Fotoğraf Noktası
- Sahil Yürüyüşü

A restaurant may contain:

- Kahvaltı
- Yemek
- Kahve
- Gün Batımı
- Canlı Müzik

Place category MAY be used to rank the most relevant Experience choices.

Place category MUST NOT artificially prevent valid Experience choices.

---

# 45. PRIMARY EXPERIENCE

Every Experience Card has ONE primary canonical Experience.

Example:

Primary Experience:
GÜN_BATIMI

Additional meaning is represented through Context rather than creating dozens of new Experience types.

---

# 46. UNKNOWN / OTHER EXPERIENCES

If the user's Experience does not match the canonical taxonomy:

Primary Experience may temporarily be:

OTHER

with a short raw user description.

Example:

primaryExperience = OTHER
rawExperienceLabel = “seramik boyama”

Users must not create permanent canonical taxonomy entries directly.

Repeated real-world OTHER values may later inform taxonomy expansion.

---

# 47. TAXONOMY SYNONYMS

Search aliases may normalize to the same canonical Experience.

Examples:

sunset
günbatımı
güneş batışı

→ GÜN_BATIMI

trekking
hiking
doğa yürüyüşü

→ DOĞA_YÜRÜYÜŞÜ

Canonical identity must remain stable even if display language changes.

---

# 48. EXPERIENCE CONTEXT

Experience diversity should come primarily from Context, not from uncontrolled taxonomy growth.

Core Context dimensions:

- Companion
- Time of Day
- Vibe
- Practical Signals

---

# 49. COMPANION

One primary Companion choice:

- Tek başıma
- Partnerimle
- Arkadaşlarla
- Ailemle
- Çocuklarla

The first V2 migration does not need complex multi-selection here.

---

# 50. TIME OF DAY

Canonical values:

- Sabah
- Gün içinde
- Akşam
- Gece

If reliable time metadata exists, Phokarta MAY suggest the value automatically.

The user should not be unnecessarily burdened with information the system already knows.

---

# 51. VIBE

Maximum recommended selection:

2

Canonical initial values:

- Sakin
- Canlı
- Romantik
- Sosyal
- Samimi
- Yerel / Otantik
- Manzaralı
- Maceralı

Example:

Experience:
Yemek

Companion:
Partnerimle

Time:
Akşam

Vibe:
Romantik

“Romantik Akşam” should NOT need to become a new canonical Experience type.

---

# 52. PRACTICAL SIGNALS

Practical Signals are not Experiences.

They are also not Vibes.

Initial canonical set should support at least:

- Arabasız gidilebilir
- Araç önerilir
- Park zor
- Rezervasyon önerilir
- Rezervasyonsuz gidilebilir
- Hafta sonu kalabalık
- Kalabalık olabilir
- Sabah daha sakin
- Gün batımı için ideal
- Çocuklarla uygun
- Evcil hayvanla uygun
- Yürümek gerekiyor
- Erken gitmek iyi olur
- Nakit gerekebilir
- Ücretsiz
- Sessiz alan bulmak mümkün

The composer must NOT show every signal at once.

Relevant signals should be prioritized based on:

- Place category,
- Experience type,
- context.

Practical Signals may become less reliable with age.

Future aggregate calculations MAY give newer signals more weight.

---

# 53. “BEN DE YAŞADIM”

Ben de Yaşadım is a core Phokarta social mechanic.

It is NOT equivalent to Like.

It means:

“I also lived an Experience sufficiently related to this specific Experience Card.”

---

# 54. BEN DE YAŞADIM — CARD-SPECIFIC RELATION

Ben de Yaşadım applies to a specific Experience Card.

It must NOT automatically apply to every card with:

same Place
+
same Experience type.

Example:

Two people may both have SUNSET Experiences at the same Place but have meaningfully different Experiences.

The explicit user relationship is the key signal.

---

# 55. BEN DE YAŞADIM — INITIAL ACKNOWLEDGEMENT

Pressing Ben de Yaşadım does NOT immediately create a new Experience Card.

It creates a lightweight acknowledgement.

At that point:

- the user may contribute to the source Card's “X kişi de yaşadı” signal,
- the source Experience may appear in the user's Ben de Yaşadım profile tab,
- the user does NOT receive a new Experience Card,
- the user does NOT create a new rating/sentiment contribution,
- the acknowledgement does NOT count as a completed Experience Card for badges/progression.

---

# 56. BEN DE YAŞADIM — CONVERTING TO OWN EXPERIENCE

The user can later choose:

“Kendi deneyimini ekle”

The composer may be pre-filled with:

- Place,
- Primary Experience.

The user then provides their own:

- Overall Feeling,
- Context,
- Story,
- Tip,
- Media,
- Visibility,
- optional Dimensions,
- Practical Signals.

After successful publication:

The acknowledgement disappears from:

Ben de Yaşadım

and the user's own card appears under:

Deneyimlerim.

The relationship to the source Experience should remain available for Experience grouping/thread behavior.

---

# 57. EXPERIENCE THREAD / RELATED EXPERIENCES

Phokarta may group related Experience Cards when users explicitly connect themselves through Ben de Yaşadım.

Conceptual example:

Kavala Beach · Gün Batımı

Yağmur’s Experience
↓
41 people also lived it
↓
Emircan created his version
Selin created her version
Mert created his version

The exact persistence model is an implementation decision.

---

# 58. EXPERIENCE THREAD OWNERSHIP

The first user to publish an Experience is NOT the owner of the real-world Experience concept.

The UI must not claim:

“Original Experience Creator”

or imply ownership over a sunset, meal, location or other real-world experience.

Cards may be ordered chronologically if useful.

---

# 59. EXPERIENCE THREAD DELETION SAFETY

Deleting the first/source Experience Card must NOT delete other users’ Experience Cards.

Deleting the first/source user must NOT delete unrelated cards created by others.

The relationship model must not make all related cards dependent on one user-owned Card's lifetime.

---

# 60. BEN DE YAŞADIM REMINDERS

If a user chooses Ben de Yaşadım but does not create their own Experience Card, Phokarta MAY occasionally remind them.

Example:

“Senin gözünden nasıldı?”

or:

“Bu deneyime kendi izini bırakmak ister misin?”

Reminders must:

- be truthful,
- correspond to a real unfinished action,
- avoid fake urgency,
- be frequency-capped,
- be dismissible,
- be disableable.

Exact timing is configuration/implementation detail.

---

# 61. PROFILE

The Phokarta Profile represents:

the user's discovery identity.

It should NOT simply be an Instagram-style post grid.

Suggested hierarchy:

Identity
↓
Social relationship
↓
Discovery map
↓
Badges
↓
Experience content

---

# 62. PROFILE CORE INFORMATION

For an accessible/open profile, the Profile may display:

- Avatar,
- personalized Seal / Companion,
- Display Name,
- Username,
- Title,
- Bio,
- Experience count,
- Follower count,
- Following count,
- City count,
- discovery map,
- badges,
- Deneyimlerim,
- Ben de Yaşadım.

Follower count is meaningful but should not be the only measure of identity or quality.

---

# 63. PROFILE — DENEYİMLERİM

Deneyimlerim is the default Experience content tab.

It contains ONLY Experience Cards actually created by that user.

A Ben de Yaşadım acknowledgement does not appear here unless the user converts it to their own Experience Card.

---

# 64. PROFILE — BEN DE YAŞADIM

This tab contains Experiences from other users where the profile owner selected Ben de Yaşadım but has not yet created their own Experience Card.

The source author must remain clear.

The UI may expose:

“Kendi deneyimini oluştur”

When the user creates their own Experience Card, the acknowledgement should no longer appear in this tab.

---

# 65. FOLLOW AND FRIEND

Follow and Friend are separate concepts.

FOLLOW:
one-way relationship.

FRIEND:
mutual follow.

Follow means:

“I want to see this person's Experiences.”

Friend means:

both people follow each other.

Existing mutual-friend semantics should be preserved unless migration analysis discovers a compelling technical incompatibility.

---

# 66. PUBLIC PROFILE

An open/public profile may normally be followed without approval.

Accessible profile content remains subject to each Experience Card's own visibility.

---

# 67. PRIVATE PROFILE

A user may make their profile private.

A private profile remains discoverable in user search.

Before an incoming follow request is approved, a viewer may see only identity-level information such as:

- Avatar,
- Display Name,
- Username,
- Title,
- short Bio,
- follow request action.

Before approval, the following should NOT be exposed:

- Experience count,
- City count,
- Badge collection,
- Follower count,
- Following count,
- Discovery map,
- Deneyimlerim,
- Ben de Yaşadım.

---

# 68. FOLLOW REQUEST

Private profiles require follow approval.

Approved follower does NOT automatically mean Friend.

Friend still requires mutual following.

---

# 69. EXPERIENCE VISIBILITY

The existing conceptual Experience visibility levels remain:

PUBLIC
FRIENDS
PRIVATE

The user-facing labels may be localized/natural language.

Open profile behavior:

PUBLIC:
visible according to normal public access rules.

FRIENDS:
visible to mutual Friends.

PRIVATE:
visible only to owner.

Private profile behavior:

PUBLIC:
visible only to approved followers or stronger authorized audiences.

FRIENDS:
visible only to mutual Friends.

PRIVATE:
visible only to owner.

Account privacy acts as an upper visibility boundary.

A content-level PUBLIC value must not bypass a private account boundary.

---

# 70. ANONYMOUS COMMUNITY AGGREGATES

Identity visibility and anonymous statistical contribution are separate concepts.

Experience Cards belonging to private profiles may still anonymously contribute to Community aggregates when their content visibility is:

PUBLIC
or
FRIENDS.

The following must NOT leak through the aggregate:

- username,
- display name,
- avatar,
- Story,
- Tip attribution,
- photo attribution,
- identifiable Experience Card content.

PRIVATE Experiences must NOT contribute to Community aggregates.

---

# 71. SMALL-SAMPLE PRIVACY

Community aggregate features should avoid indirectly identifying private users in very small samples.

Future implementations MAY use minimum sample thresholds before exposing detailed aggregate insights.

Exact threshold is not defined in this contract.

---

# 72. COMMUNITY COUNT VS VISIBLE CARD COUNT

The following are different concepts:

Visible Experience Card count

and

Community evaluation count.

Example:

35 visible Experience Cards

may be based on:

52 anonymous/non-private Community evaluations.

The UI must not treat these counts as identical.

---

# 73. PLACE PAGE

The Place Page must primarily answer:

“What do people experience here?”

rather than:

“What is this Place’s single score?”

Suggested hierarchy:

Place identity
↓
What people experience here
↓
Community feeling
↓
useful aggregate information
↓
Experience Cards

---

# 74. PLACE PAGE HERO

The Place page may contain:

- Place media,
- Place name,
- location,
- Place category,
- visible Experience count,
- Map action,
- Save Place action.

---

# 75. PLACE PAGE — EXPERIENCE TYPES

The Place page should surface what people actually do/experience there.

Example:

Burada neler yaşanıyor?

Gün Batımı 18
Deniz 14
Sakin Zaman 11
Sahil Yürüyüşü 8

These Experience types may be used to filter the Experience Card feed for that Place.

---

# 76. PLACE PAGE — COMMUNITY FEELING

The Place page should prioritize human-readable sentiment distribution over a single generic numeric overall score.

Example:

%62 Bayıldı
%27 Güzel buldu
%8 Eh işte
%3 Beklentimi karşılamadı

A numeric normalized score may still exist internally.

---

# 77. PLACE PAGE — DIMENSION AGGREGATES

Relevant dimension summaries may be shown.

Example:

Manzara — Çok iyi
Sakinlik — İyi
Ulaşım — Orta

The dimension set depends on relevant Experiences and/or Experience families.

---

# 78. PLACE PAGE — PRACTICAL AGGREGATES

Practical signals may be aggregated.

Example:

23 kişi:
Arabasız gidilebilir

31 kişi:
Gün batımı için ideal

17 kişi:
Park zor

Practical aggregate presentation must preserve privacy.

---

# 79. PLACE DETAILS

Traditional Place information remains useful but should be secondary to Experience discovery.

Examples:

- detailed address,
- phone,
- opening hours,
- website.

These may live under a secondary:

“Mekan detayları”

surface.

---

# 80. SAVE PLACE VS PLAN EXPERIENCE

These are separate user intents.

SAVE PLACE means:

“This Place interests me.”

PLAN EXPERIENCE means:

“I want to live this specific Experience.”

The UI should preserve the distinction.

---

# 81. PLANIM

Planım is a root navigation destination.

Initial V2 scope contains:

- Gitmek İstediklerim
- Koleksiyonlar

Full itinerary planning is deferred.

---

# 82. GİTMEK İSTEDİKLERİM

The primary planned object is the Experience Card.

Users should be able to add an Experience Card using:

“Planıma ekle”

Gitmek İstediklerim may also provide a separate view/filter for Saved Places.

Recommended conceptual separation:

Deneyimler
Mekanlar

Experience is the primary object.

Place is secondary.

---

# 83. COLLECTIONS

Collections may contain BOTH:

- Experience Cards,
- Places.

Example Collection:

“Foça Hafta Sonu”

may contain:

- a sunset Experience,
- a breakfast Experience,
- a saved beach,
- a café Place.

This prepares the product for future itinerary planning without requiring itinerary functionality now.

---

# 84. FUTURE TRIP PLANNING

Future versions may allow converting collections/saved Experiences into a full trip.

Possible future fields:

- start date,
- end date,
- days,
- time slots,
- route,
- transportation,
- map ordering,
- itinerary optimization.

This is NOT required for initial Experience V2 migration.

---

# 85. QUESTIONS & COMMENTS

Experience Detail has one discussion surface:

“Sorular & Yorumlar”

Two primary content types:

QUESTION
COMMENT

Questions and Comments exist in the same thread/surface.

---

# 86. QUESTION

A Question is a user asking for additional useful information about the Experience.

Examples:

“Arabasız gidilir mi?”

“Rezervasyon yaptınız mı?”

“Gün batımı için kaçta geldiniz?”

---

# 87. COMMENT

A Comment is a general reaction/contribution.

Example:

“Biz de geçen yaz gittik, manzara gerçekten çok güzeldi.”

---

# 88. DISCUSSION REPLIES

The first V2 implementation should prefer a simple thread model.

Recommended depth:

Main Question/Comment
→ one reply level

Deep Reddit-style nested discussions are NOT required.

---

# 89. EXPERIENCE AUTHOR ANSWER

When an Experience Card author answers a Question, the UI may distinguish the response.

Example:

“Kart sahibinin cevabı”

This is useful because the person who lived the Experience is often the most relevant respondent.

---

# 90. COMMENT MODERATION

Experience Card owners must NOT have arbitrary power to delete other users' legitimate Questions/Comments.

Safety handling should use:

- Report,
- Block,
- platform moderation.

A sponsored/creator account must not be able to clean negative discussion merely because it owns the Experience Card.

---

# 91. SEARCH

Search is not only Place search.

The product should eventually understand intent such as:

“Sevgilimle sakin bir akşam”

“Arabasız Foça”

“Gün batımı”

“İzmir’de kahvaltı”

“Arkadaşlarla gece”

Search may eventually combine:

- geography,
- Place,
- Primary Experience,
- Companion,
- Time of Day,
- Vibe,
- Practical Signals.

Advanced semantic AI search is not required for the first V2 implementation.

The data model should not prevent it later.

---

# 92. MAP

Map remains a root feature.

It must not become merely a generic Google Maps clone.

The Map may eventually surface:

- Places,
- Experience density,
- Experience types,
- Saved status,
- Planned Experience status,
- Visited state,
- Friend signals.

Existing stable map functionality should be preserved during migration unless a change is required by the V2 contract.

Experience-first Map redesign may happen incrementally.

---

# 93. USER SEAL / PHOKARTA COMPANION

Every Phokarta account has a personalized Harbor Seal character.

The Seal is a permanent part of the Phokarta identity.

Default profile avatar:

personalized Seal.

---

# 94. REAL PROFILE PHOTO

The user may optionally use a real personal photo as the primary social avatar.

If a real photo is used:

the personalized Seal remains visible as the user's Phokarta Companion.

The Seal is never removed from the identity system.

---

# 95. SEAL CUSTOMIZATION

Initial customization should remain bounded.

Possible initial customization:

- expression,
- head accessory,
- neck accessory,
- backpack,
- camera,
- limited travel accessories.

The canonical Harbor Seal silhouette and brand character must remain recognizable.

---

# 96. SEAL PROGRESSION

Seal progression should reflect discovery history.

Possible examples:

- backpack,
- camera,
- travel accessories,
- badges,
- thematic cosmetic rewards.

The system must NOT become a heavy addictive XP/level mechanic by default.

Desired meaning:

“The Seal visually carries the history of the user's discoveries.”

---

# 97. TITLE SYSTEM

Title answers:

“How does this person discover?”

Examples:

- Gün Batımı Avcısı
- Kıyı Kaşifi
- Lezzet Avcısı
- Doğa Gezgini
- Gizli Köşe Avcısı

Follower count should not be the main criterion for Titles.

---

# 98. BADGE SYSTEM

Badge answers:

“What has this person actually done?”

Examples:

- 10 Gün Batımı
- 5 Ada
- 10 Şehir
- 10 Yemek Deneyimi
- İlk Yurtdışı Deneyimi

Difference:

Title = discovery identity.

Badge = specific achieved discovery/history.

---

# 99. DISCOVERY MAP

Profile may contain a personal discovery map.

Working presentation concept:

“Momo’nun İzleri”

The map should primarily reflect locations where the user actually created Experience Cards.

A simple Ben de Yaşadım acknowledgement does not need to count as a completed discovery for this map.

---

# 100. FOLLOW GRAPH AND FUTURE CREATOR ECONOMY

The Follow system must remain meaningful.

Future creator metrics may include:

- Reach,
- Plan conversion,
- Ben de Yaşadım conversion,
- Experience conversion,
- Follow conversion,
- Question response rate.

Follower count alone should not define creator quality.

Example:

A creator with fewer followers but high Plan and Experience conversion may be more valuable than a creator with many passive followers.

---

# 101. FUTURE PREMIUM / REVENUE READINESS

The V2 architecture should not block future support for:

- Premium user features,
- advanced trip planning,
- AI trip planning,
- creator premium analytics,
- business profiles,
- creator-business partnerships,
- sponsored Experiences,
- creator marketplace,
- premium Seal cosmetics.

These are NOT required initial V2 deliverables.

---

# 102. SPONSORED CONTENT

If paid partnerships are introduced later:

Sponsored Experiences must be explicitly disclosed.

Sponsored content must not secretly look organic.

Payment/sponsorship must NOT:

- increase Community sentiment,
- increase Community dimension aggregates,
- suppress negative Experiences,
- alter organic rating calculations.

Commercial content and Community truth must remain separate.

---

# 103. VISUAL DESIGN DIRECTION

Phokarta V2 should feel:

- light,
- airy,
- calm,
- modern,
- warm,
- slightly premium,
- playful without looking childish.

Photography and user content should provide much of the visual color.

The UI itself should remain restrained.

Current palette direction:

- off-white / white,
- very light gray,
- baby blue / mist blue,
- cool blue-gray,
- dark blue-gray text,
- small optional coral accents.

Exact final hex values are NOT a migration blocker.

---

# 104. SEMANTIC DESIGN TOKENS

UI implementation should prefer semantic design tokens such as:

background
surface
surfaceSoft
primary
primarySoft
textPrimary
textSecondary
border
success
warning
error

The palette should be replaceable centrally without redesigning every screen.

---

# 105. CORE USER LOOP

The intended V2 loop is:

Experience keşfet
↓
Planıma ekle
↓
Gerçek hayatta yaşa
↓
Ben de Yaşadım
↓
Kendi Experience Card’ını oluştur
↓
Profil / collection / Seal progression
↓
İnsanları takip et
↓
Yeni Experiences keşfet
↓
Tekrar plan yap

This loop is central to the product.

---

# 106. V2 INITIAL NON-GOALS

The initial migration does NOT need to deliver:

- full itinerary planner,
- route optimization,
- AI trip planner,
- creator marketplace,
- Business dashboard,
- paid partnership marketplace,
- subscriptions,
- complex Seal economy,
- deep nested discussions,
- direct messaging,
- complete semantic AI search,
- sophisticated recommendation ML.

The architecture should not unnecessarily block these future capabilities.

---

# 107. EXISTING SYSTEMS THAT MUST NOT BE REGRESSED

The V2 migration must preserve the currently verified behavior and security guarantees of the existing Phokarta beta wherever they remain applicable.

This includes at least:

- authentication,
- JWT access/refresh behavior,
- session restoration,
- policy acceptance,
- canonical Place identity,
- PostgreSQL/PostGIS geo behavior,
- Map functionality,
- media ownership,
- media upload intent,
- presigned direct object-storage upload,
- media confirmation,
- media visibility,
- no Bearer token to object-storage PUT,
- offline Visit/Experience drafts,
- offline mutation queue,
- lost-ACK/idempotency behavior,
- account isolation,
- follow graph,
- mutual Friend semantics,
- Block symmetric product barrier,
- reporting,
- account deletion,
- durable object-storage cleanup,
- PUBLIC / FRIENDS / PRIVATE visibility,
- Private Memory privacy,
- release HTTPS enforcement,
- no infrastructure credentials in clients,
- EN/TR localization,
- Android release integrity,
- iOS/Xcode Cloud integrity.

V2 is an evolution of the working beta.

It is NOT a greenfield rewrite.

---

# 108. DATA SAFETY

Migration planning must assume existing beta data may exist.

No migration strategy may casually:

- delete existing Visits,
- delete media,
- invalidate ownership,
- break account deletion,
- break Saved/Collections,
- expose Private Memory,
- expose private/friend-only content,
- duplicate canonical Visits/Experiences,
- break offline queued mutations.

Existing data compatibility must be explicitly addressed.

---

# 109. VISIT VS EXPERIENCE

The current codebase contains a Visit domain.

The target product introduces Experience Card behavior.

This contract intentionally does NOT require:

“Delete Visit and replace it with a brand-new Experience system.”

The migration audit must first determine whether the current Visit domain can safely evolve into or back the Experience Card model.

Possible reuse must be preferred over unnecessary rewrite.

The final domain naming strategy is a migration architecture decision.

---

# 110. EXISTING RATING DATA

The current codebase contains numeric rating/dimension structures.

V2 uses:

- emotional Overall Feeling,
- human-readable Dimension states.

The migration audit must determine how old numeric data can:

- remain readable,
- be mapped,
- be backfilled,
- coexist,
- or be gradually deprecated.

Existing valid data must not simply be discarded.

---

# 111. EXISTING SAVED / COLLECTIONS

Current Saved Place and Collection behavior must be audited for reuse.

Target V2 requires:

- Save Place,
- Plan Experience,
- Collections containing Places and Experience Cards.

The migration audit must determine how to extend existing structures without unnecessary destructive migration.

---

# 112. EXISTING SOCIAL / PRIVACY MODEL

Existing:

- Follow,
- mutual Friends,
- PUBLIC / FRIENDS / PRIVATE,
- Block,
- Report,

must form the migration baseline.

New profile privacy adds:

- public profile,
- private profile,
- follow request / approval.

The migration audit must determine how to introduce this without weakening current privacy/security guarantees.

---

# 113. EXISTING MEDIA

Existing first-class media storage must be reused unless a proven incompatibility exists.

V2 media requirements do not justify replacing a working:

- media ownership model,
- upload-intent lifecycle,
- direct storage PUT,
- confirm lifecycle,
- signed read access,
- ordered media association,
- account deletion cleanup system.

Experience Card media should build on this infrastructure.

---

# 114. EXISTING OFFLINE ARCHITECTURE

Existing Android/iOS durable draft and offline mutation systems are valuable.

The Experience V2 composer and publish process must preserve:

- process-death survival,
- draft recovery,
- pending state,
- idempotent mutation identity,
- reconnect synchronization,
- duplicate prevention,
- account isolation.

Do not replace these systems simply because the UI changes.

---

# 115. BACKWARD-COMPATIBLE MIGRATION PRINCIPLE

Preferred migration strategy:

EXPAND
↓
BACKWARD COMPATIBILITY
↓
DATA MIGRATION / BACKFILL
↓
NEW CLIENT SURFACES
↓
DEPRECATE
↓
CONTRACT

A big-bang rewrite is NOT the default acceptable plan.

Schema/API evolution should remain backward compatible where reasonably possible while Android and iOS migrate.

---

# 116. CROSS-PLATFORM RULE

Android and iOS remain native implementations.

Backend/API contracts are the product contract boundary.

Android and iOS do not need identical source architecture.

They must provide equivalent V2 product behavior and preserve platform-appropriate native UX.

---

# 117. PRODUCT CONTRACT VS CURRENT CODE

The migration audit must distinguish:

CURRENT IMPLEMENTATION

from:

TARGET PRODUCT CONTRACT.

Existing code is not automatically the desired V2 behavior.

However:

the Product Contract is also NOT permission to rewrite stable infrastructure unnecessarily.

The goal is:

maximum safe reuse
+
minimum destructive change
+
correct V2 product behavior.

---

# 118. ARCHITECTURAL AUDIT REQUIREMENT

Before implementation begins, the repository must be audited against this contract.

For every substantial V2 requirement, the audit should identify:

CURRENT STATE

TARGET STATE

REUSABLE COMPONENTS

REQUIRED CHANGE

DATABASE IMPACT

API IMPACT

BACKWARD COMPATIBILITY RISK

ANDROID IMPACT

IOS IMPACT

OFFLINE IMPACT

PRIVACY / SECURITY IMPACT

TEST IMPACT

DEPENDENCIES

RECOMMENDED IMPLEMENTATION PHASE

---

# 119. MIGRATION AUDIT MUST REVIEW

At minimum:

Backend:
- entities,
- repositories,
- services,
- controllers,
- DTOs,
- visibility policies,
- social graph,
- block/report,
- account deletion,
- media,
- geo,
- Flyway,
- production configuration.

Database:
- users,
- places,
- visits,
- dimension scores,
- saved places,
- collections,
- follows,
- blocks,
- reports,
- media,
- deletion jobs,
- existing indexes/constraints.

Android:
- domain models,
- Room schema,
- repositories,
- ViewModels,
- Compose screens,
- navigation,
- offline drafts,
- offline mutation queue,
- media store,
- WorkManager,
- Saved/Collections,
- social state,
- privacy,
- localization.

iOS:
- domain models,
- SQLite persistence,
- stores/controllers,
- SwiftUI screens,
- navigation,
- offline drafts,
- mutation sync,
- media,
- Saved/Collections,
- social state,
- privacy,
- localization.

Tests:
- backend,
- Android unit,
- Android connected,
- iOS XCTest,
- migration tests,
- security/privacy tests,
- idempotency tests,
- account-isolation tests.

---

# 120. MIGRATION AUDIT MUST NOT IMPLEMENT

The first migration audit is READ ONLY.

It must NOT:

- edit code,
- create migrations,
- change schemas,
- refactor code,
- commit,
- push,
- rewrite history,
- modify infrastructure,
- deploy backend,
- modify TestFlight,
- modify Google Play,
- delete data.

Its purpose is to understand the dependency graph and produce a safe migration plan.

---

# 121. EXPECTED MIGRATION AUDIT OUTPUT

The final audit should contain:

1. Repository / architecture baseline.

2. Current Visit domain analysis.

3. Experience V2 mapping.

4. Data model gap analysis.

5. Rating → Feeling migration options.

6. Dimension migration options.

7. Taxonomy storage strategy options.

8. Experience Context strategy.

9. Practical Signal strategy.

10. Ben de Yaşadım / Experience Thread strategy.

11. Profile privacy / Follow Request strategy.

12. Questions & Comments strategy.

13. Planım / Saved / Collections strategy.

14. Profile / Seal / Badge impact.

15. Explore feed impact.

16. Place Page impact.

17. Map impact.

18. Offline and idempotency impact.

19. Media impact.

20. Account deletion impact.

21. Privacy / visibility audit.

22. API compatibility analysis.

23. Android migration analysis.

24. iOS migration analysis.

25. Database/Flyway migration analysis.

26. Existing beta data migration strategy.

27. Test strategy.

28. Dependency graph.

29. Recommended phased implementation order.

30. Risks and blockers.

31. Explicit list of infrastructure/components that should be reused unchanged.

---

# 122. MIGRATION QUALITY BAR

The migration proposal should optimize for:

- no user-data loss,
- no privacy regression,
- no security regression,
- no media regression,
- no account-isolation regression,
- no offline-sync regression,
- no idempotency regression,
- no unnecessary rewrite,
- reversible/controlled rollout where possible,
- understandable Flyway history,
- backward-compatible APIs during transition where practical,
- deterministic tests,
- independent Android and iOS migration capability.

---

# 123. CHANGE CONTROL

This file is the V2 Product Contract.

During architectural audit:

The code agent may identify:

- ambiguity,
- conflict,
- implementation difficulty,
- migration risk.

The code agent MAY propose product questions.

The code agent MUST NOT silently change a locked product decision.

If a V2 product decision must change:

the Product Contract should be reviewed explicitly before implementation.

---

# 124. FINAL TARGET

Phokarta V2 should evolve from a Place/Visit/rating-oriented beta into an Experience-first discovery community while preserving the strong infrastructure already built.

The target product loop is:

Discover a real person's Experience
→
decide whether it fits you
→
add the Experience to Planım
→
live it in the real world
→
say Ben de Yaşadım
→
optionally create your own Experience Card
→
build your discovery identity
→
follow people whose discovery style you value
→
discover the next Experience.

The migration should make this possible without sacrificing the reliability, privacy, offline behavior, media ownership, account safety or cross-platform foundations of the existing Phokarta beta.

---

# 125. LEGACY NUMERIC RATING → OVERALL FEELING COMPATIBILITY

Existing numeric Visit ratings must remain unchanged.

For V2 read compatibility, a legacy Visit that does not have an explicitly selected Overall Feeling may derive one using the following mapping:

- 9.0–10.0 → BAYILDIM
- 7.0–8.9 → GUZELDI
- 5.0–6.9 → EH_ISTE
- 3.0–4.9 → BEKLENTIMI_KARSILAMADI
- 0.0–2.9 → BIR_DAHA_TERCIH_ETMEM

This mapping is for backward-compatible interpretation of legacy data.

It must NOT overwrite or destroy the original numeric rating.

V2-native publication uses an explicit Overall Feeling rather than asking the user for an overall 0–10 value.

The system should preserve whether the Feeling was:

- EXPLICIT
- DERIVED_LEGACY

Derived legacy Feelings and explicit V2 Feelings may be analytically distinguished when useful.

The user-facing Experience Card does not need to display this provenance.

---

# 126. DIMENSION STATE → NUMERIC COMPATIBILITY

V2 dimension evaluation uses stable semantic states.

Canonical compatibility mapping:

- VERY_GOOD / Çok iyi → 10
- GOOD / İyi → 8
- MEDIUM / Orta → 6
- WEAK / Zayıf → 4
- VERY_WEAK / Çok zayıf → 2

For V2-native dimension data, the semantic state code must be preserved.

A numeric compatibility score alone is not sufficient identity for the semantic state.

Conceptually:

state_code = VERY_GOOD
compatibility_score = 10

Existing legacy numeric dimension scores remain unchanged.

Legacy values must not be destructively rewritten merely to fit the V2 semantic-state model.

A derived state may be exposed through compatibility logic where required, while the original numeric value remains available.

---

# 127. EXPERIENCE TITLE PERSISTENCE

Experience Card titles are automatically suggested by Phokarta.

The user may edit the suggested title before publishing.

Title source must be distinguishable as:

- GENERATED
- CUSTOM

If the user accepts the generated title without editing it, the resolved generated title string must still be persisted when the Experience is published.

The published card must not depend on regenerating its title every time it is read.

This ensures that future:

- taxonomy wording changes,
- localization changes,
- title-generation algorithm changes

do not silently rename already published Experience Cards.

A user-edited title is persisted with:

title_source = CUSTOM

An accepted generated title is persisted with:

title_source = GENERATED

Generated-title logic may evolve for future Experiences without mutating historical published titles.

---

# 128. BLOCKING AND ANONYMOUS COMMUNITY AGGREGATES

Blocking is an identity and authored-content visibility barrier.

Blocking must hide, according to the existing symmetric block policy:

- profile identity,
- Experience Cards,
- discussions,
- acknowledgements,
- follow relationships,
- other attributable user-authored surfaces.

However, blocking does NOT remove that person's otherwise eligible anonymous contribution from global Community aggregates.

Example:

If User A blocks User B:

- B's Experience Card is not visible to A.
- B's identity is not visible to A.
- B's Questions/Comments are not visible to A.
- B's anonymous eligible Feeling/dimension contribution may remain part of the Place-level Community aggregate.

Community aggregate values should therefore remain globally consistent rather than being recalculated according to each viewer's block list.

PRIVATE Experiences never contribute to Community aggregates regardless of block state.

No aggregate response may reveal the blocked contributor's identity.

---

# 129. BEN DE YAŞADIM CONVERSION COUNT SEMANTICS

A Ben de Yaşadım acknowledgement remains semantically true after the user converts it into their own Experience Card.

Therefore the source Experience's:

“X kişi de yaşadı”

count may include both:

- unconverted acknowledgements,
- converted acknowledgements.

Conceptually, acknowledgement state may distinguish:

- ACKNOWLEDGED
- CONVERTED

The profile's Ben de Yaşadım tab must show only acknowledgements that have not yet been converted into the user's own Experience Card.

After conversion:

- the acknowledgement no longer appears in the user's Ben de Yaşadım tab,
- the user's own card appears in Deneyimlerim,
- the source card may continue counting that user as someone who also lived the Experience,
- the related Experience/thread connection remains.

Conversion must not decrease the historical “also lived” meaning of the source Experience.

---

# 130. CHANGE STATUS

Sections 125–129 are locked product and compatibility decisions for the initial Phokarta V2 migration.

They supersede corresponding ambiguities identified during the first Technical Migration Audit.

Future implementation agents must treat these rules as part of the authoritative Product Contract.

END OF PHOKARTA V2 PRODUCT CONTRACT