# Community guidelines / terms — requirements

Play’s User Generated Content policy requires apps that host UGC to:

- Require users to accept terms of use and/or a user policy **before** they create or upload UGC
- Define objectionable content and behavior (in a way that does not conflict with Play policies)
- Provide in-app **report** and **block** for content and users, at a level matching the product (Phokarta has publicly accessible UGC and 1:1 social surfaces)
- Moderate reports in a timely way

Source: [User Generated Content](https://support.google.com/googleplay/android-developer/answer/9876937) and the Developer Program Policy UGC section.

## What the product already has

- Report Visit and report user  
- Block user (symmetric visibility)  
- In-app account deletion  
- Safety documentation in [RELEASE_SAFETY.md](RELEASE_SAFETY.md)
- Versioned **technical** User Policy acceptance before Visit create, photo upload/confirm, and collection create/add (server-authoritative; current required version `2026-08-beta`)
- In-app Accept sheet with an unchecked checkbox (not a silent backfill). Report and block remain available without acceptance.

## What is missing for Play UGC compliance

- **Terms of Service** — not written as a final legal document; not shown at register; public HTTPS page **MISSING**
- **Community Guidelines / Acceptable Use** — in-app draft only, labeled OWNER/LEGAL REVIEW REQUIRED; public HTTPS page **MISSING**
- **Register checkbox** — `RegisterRequest` has no terms field; the gate is on UGC create/upload, not account creation
- **Admin moderation UI** — operators use private database inspection  
- **Named moderation owner** — required operationally before testers  

Closed beta can proceed with a small trusted tester set while legal docs are drafted, but **Play review of a closed track still applies Play policies**. Do not claim Play approval.

## Drafting rules

Do not paste fabricated legal terms into the app or Console. Owner/counsel should produce:

1. Terms of Service (account, UGC license to operate the service, termination)  
2. Community Guidelines (spam, harassment, hate, sexual content, impersonation, privacy violations, illegal content)  
3. How to report and what happens next (manual review; no auto-ban)  

When those pages exist, set `PHOKARTA_TERMS_URL` and `PHOKARTA_COMMUNITY_GUIDELINES_URL` (absolute `https://`). Empty URLs must not open placeholder hosts.

## Closed-beta minimum if legal text is not ready

The **repo** technical gate is implemented. Hosting final Terms/Guidelines remains an **external Play/legal blocker**, not a runtime crash. Options the owner may choose later:

- Host short guidelines + terms on the same public site as the privacy policy, then point the in-app links at those URLs  
- Limit closed testing to Internal testing (does **not** satisfy personal-account production-access testing rules)
