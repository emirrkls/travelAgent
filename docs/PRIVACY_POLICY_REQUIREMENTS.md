# Privacy policy requirements

Checklist for a **real** privacy policy before Play closed testing. **LEGAL/OWNER REVIEW REQUIRED.** Do not treat this as the policy. Do not publish until counsel/owner approve and a public HTTPS page exists.

Play User Data policy (Help Center): every app must post a privacy policy URL in Play Console and a link or the policy text **in the app**. The URL must be active, publicly accessible, non-geofenced, non-editable, and not a PDF. The developer or app name as it appears on the listing must appear in the policy.

## Required disclosures (from actual product behavior)

The policy should describe, in ordinary language:

1. Identity of the operator / developer named on the Play listing  
2. Account data: email, username, display name, password (hashed)  
3. UGC: Visits, reviews, private memories, photos, collections, follows  
4. Optional device location for map/nearby  
5. How visibility (PUBLIC / FRIENDS / PRIVATE) and blocking work  
6. Service providers: hosting, database, object storage, Google Maps on device  
7. No ads and no in-app analytics SDK in the current beta (update if that changes)  
8. How to delete an account **in the app** and **on the web** (once the web URL exists)  
9. Report retention after account deletion  
10. Signed URL TTL / delayed media byte deletion  
11. Contact for privacy requests (email TBD — do not invent)  
12. That closed-beta data may be reset before a public launch, if that remains the operational policy  

## Hosting

**MISSING.** No domain is provisioned. Do not deploy from this milestone.

## In-app surfacing

Do not hardcode a placeholder URL in the Android client. When a real URL exists, add it via build config / remote config and a Settings row.
