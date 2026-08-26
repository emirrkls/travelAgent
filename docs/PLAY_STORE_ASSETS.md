# Play Store assets

Closed-beta listing assets. Marked **READY** (copy in repo) or **MISSING** (operator/design). Do not submit from this document.

Official graphic rules used: [Add preview assets](https://support.google.com/googleplay/android-developer/answer/9866151).

## App name

**READY** — Phokarta (`app_name`, launcher label). Do not change the brand.

## Short description

Limit: 80 characters. Factual; no superlatives or unshipped claims.

| Locale | Candidate | Status |
|---|---|---|
| EN | Discover, rate, remember, and share places with people you trust. | READY (draft) |
| TR | Yerleri keşfet, puanla, hatırla ve güvendiğin insanlarla paylaş. | READY (draft) |

## Full description

See drafts below. **READY** as drafts only. Owner review before Console paste.

### English (draft)

Phokarta is a travel companion for discovering places, recording Visits, and sharing what you loved with friends.

- Explore places and browse the map
- Rate places with category-aware scores
- Save places you want to go and keep them in collections
- Follow friends and see friend-visible activity
- Keep private memories private
- Block accounts and report users or Visits if something is wrong
- Delete your account from Settings when you want to leave

Phokarta does not include ads or in-app purchases in this closed beta. Recommendations are not guaranteed. Google Sign-In, password reset, and email verification are not part of this beta.

### Turkish (draft)

Phokarta, yerleri keşfetmek, Ziyaret kaydetmek ve beğendiklerini arkadaşlarınla paylaşmak için bir seyahat arkadaşıdır.

- Yerleri keşfet ve haritada gez
- Kategoriye göre puan ver
- Gitmek istediğin yerleri kaydet ve koleksiyonlarda tut
- Arkadaşlarını takip et ve arkadaşlara açık akışı gör
- Özel anılarını gizli tut
- Gerekirse hesapları engelle, kullanıcıları veya Ziyaretleri bildir
- Ayrılmak istediğinde Ayarlar’dan hesabını sil

Bu kapalı betada reklam veya uygulama içi satın alma yoktur. Öneriler garanti edilmez. Google ile giriş, şifre sıfırlama ve e-posta doğrulama bu betada yoktur.

## Category

**READY (recommendation only)** — Travel & Local. Confirm in Play Console category list at submission time. Do not submit from this repo.

## Support contact

**MISSING** — external input required. Do not invent an email or domain.

Placeholders:

- Support email: `TBD`
- Privacy email: `TBD`
- Website: `TBD`
- Privacy policy URL: `TBD`
- Account deletion web URL: `TBD`

## Privacy URL

**MISSING** — Play User Data policy requires a publicly reachable, non-PDF privacy policy URL in Play Console **and** a link or policy text in the app. The app does not wire a fake URL. Hosting is an external blocker.

## Feature graphic

**MISSING** — required to publish a store listing.

- JPEG or 24-bit PNG (no alpha)
- Exactly **1024×500** px

Do not generate in this milestone.

## Phone screenshots

**MISSING** — images not in repo (`screenshots/` is local QA only and gitignored).

Official minimum to publish a listing: **at least two** screenshots across device types; JPEG or 24-bit PNG; 320–3840 px; longest side at most 2× the shortest. Highly recommended for apps: at least **four** screenshots at ≥1080 px (9:16 portrait or 16:9 landscape).

Shot list (release-quality seeded data; no emulator debug overlays):

1. Explore
2. Map
3. Place detail
4. Rate / Visit
5. Collections
6. Profile
7. Community / friends activity

## Tablet screenshots

**MISSING / optional for this beta.** The app does not declare a phone-only `<supports-screens>` restriction, but the UI is not tablet-optimized and is portrait-locked. Do not claim tablet support in listing copy. Add 7-inch/10-inch shots only after a real tablet pass.

## Store icon (512×512)

**MISSING** — Play listing icon is a 512×512 PNG (typically 32-bit). The repo has only `app/src/main/res/drawable/ic_launcher.xml` (48dp vector coral mark). That is not an adaptive icon set and not a store-sized PNG.

Launcher: `android:icon` / `roundIcon` → `@drawable/ic_launcher`. No `mipmap-anydpi-v26` adaptive layers.

## Maps attribution

Google Maps in the map shell uses `contentPadding` so the Google logo/attribution sits above the bottom sheet. Do not cover required Maps branding in future overlays. Device-check on small widths remains a screenshot QA item.
