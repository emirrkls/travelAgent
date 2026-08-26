# Account deletion web resource (template)

Play User Data policy: if the app allows account creation, provide **both** an in-app deletion path **and** a web link where users can request account and associated data deletion **without reinstalling the app**.

Official: [Understanding Google Play’s app account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111).

The page must load, mention **Phokarta** (or the listing developer name), and make the deletion request path obvious. A customer-service email or a form is allowed if the user can actually start the request there.

**Do not invent a domain.** When a site exists, a path such as `https://<domain>/account-deletion` is sufficient. This file is not that page.

## In-app path (already shipped)

Profile → Settings → Account → Delete account. Password accounts confirm with the current password. `DELETE /api/v1/me`. See [ACCOUNT_DELETION.md](ACCOUNT_DELETION.md).

## Factual content the future page should include

1. How to request deletion  
   - Signed-in users: use in-app Settings.  
   - Users who uninstalled the app: email the support address (TBD) from the **account email**, subject “Phokarta account deletion”, including username if known.  
2. What is deleted  
   - Profile, Visits, private memories, saved places, collections, follows, blocks, uploaded photos (bytes asynchronously)  
3. What may remain for a short time  
   - Object-storage signed GET URLs until their short TTL  
   - Media bytes until durable cleanup jobs finish  
4. Safety exception  
   - Abuse reports may remain with reason/details/status after accounts are removed; live user IDs are cleared  
5. No “freeze account” alternative — deletion is a hard delete of the Phokarta account  
6. Re-registering the same email creates a **new** account; old Visits do not come back  

Operator SLA and legal retention periods are **not** defined here.
