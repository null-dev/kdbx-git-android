# Instant Sync — UnifiedPush Design

## Overview

Instant sync eliminates the up-to-15-minute lag introduced by the periodic WorkManager job.
When any client pushes a new database version to the server, all other registered clients
receive a push wakeup within seconds and immediately pull the latest state.

The mechanism is **UnifiedPush** — an open standard for push notifications that does not
depend on Google Play Services. The app delegates delivery to a user-chosen *distributor*
(e.g. ntfy, Gotify-UP, a self-hosted server) which maintains the persistent connection on
the device's behalf. The app itself remains idle between pushes.

---

## UnifiedPush Primer

```
kdbx-git server  ──POST──►  Push provider         ──deliver──►  Distributor app
                             (ntfy / Gotify-UP /                  (on device)
                              any UP-compatible)
                                                                        │
                                                                        ▼ broadcast
                                                                  kdbx-git app
                                                                  (MessagingReceiver)
```

1. The kdbx-git Android app registers with the distributor and receives a unique
   **endpoint URL** (an HTTPS URL on the push provider's server).
2. The app POSTs that endpoint URL to the kdbx-git server.
3. When `main` advances on the server, the server POSTs a small notification payload to
   every registered endpoint URL.
4. The push provider delivers the payload to the distributor app on the device.
5. The distributor app broadcasts an intent; the kdbx-git app's `MessagingReceiver` wakes
   up and enqueues an expedited `SyncWorker(PUSH)`.

No persistent network connection is held by the kdbx-git app itself.

---

## Server-Side Design

### New REST endpoints

All endpoints are authenticated with HTTP Basic (username = client ID, password = configured
password), identical to the existing WebDAV endpoints.

#### Register / update a push endpoint

```
POST /push/{client_id}/endpoint
Content-Type: application/json

{
  "endpoint": "https://push.example/...",
  "keys": {
    "p256dh": "<Base64url P-256 public key>",
    "auth":   "<Base64url auth secret>"
  }
}
```

- Stores (or replaces) the Web Push subscription for `{client_id}`.
- Returns `204 No Content` on success.
- `keys` is present when the UP library has generated Web Push key pairs for the client
  (always the case for the embedded FCM distributor; may be absent for some external
  distributors). When `keys` is present the server **must** encrypt push payloads using
  RFC 8291 (Web Push Encryption) before delivery.
- If a subscription was previously registered for this client it is silently replaced
  (supporting distributor changes or re-installs).

#### Unregister a push endpoint

```
DELETE /push/{client_id}/endpoint
```

- Removes any stored endpoint URL for `{client_id}`.
- Returns `204 No Content` (also `204` if no endpoint was registered — idempotent).

#### Get VAPID public key

```
GET /push/{client_id}/vapid-public-key
```

Response:

```json
{ "public_key": "<Base64url-encoded uncompressed P-256 public key>" }
```

- Returns the server's VAPID public key.
- Required by the embedded FCM distributor: it uses the VAPID key to authenticate push
  messages with Google's FCM infrastructure.
- The client fetches this key before calling `UnifiedPush.register(vapid = key)`.
- External UP distributors (ntfy, Gotify-UP, etc.) do not require a VAPID key; the
  client passes `null` for those.

### Persistence

The server has no database. Push endpoint state is stored in a single **`sync-state.json`**
file on disk, kept alongside the existing server data files.

```json
{
  "push_endpoints": {
    "alice-phone": {
      "endpoint": "https://push.example/AbCdEfGhIjKlMnOp",
      "keys": { "p256dh": "...", "auth": "..." },
      "last_seen_at": "2026-03-18T10:23:00Z"
    },
    "bob-laptop": {
      "endpoint": "https://ntfy.example.com/ZyXwVuTsRqPoNmLk",
      "last_seen_at": "2026-03-15T08:01:00Z"
    }
  }
}
```

The top-level object is versioned implicitly by the key set; additional fields can be
added in the future without breaking existing readers.

#### Atomic file replacement

To avoid leaving a corrupt or truncated `sync-state.json` if the process crashes mid-write,
all writes follow the write-to-temp-then-rename pattern:

1. Serialise the new state to a temp file in the same directory (e.g. `sync-state.json.tmp`).
2. `fsync` the temp file.
3. Atomically rename the temp file over `sync-state.json` (POSIX `rename(2)` is atomic
   within the same filesystem).

Because rename is atomic on POSIX systems, readers will always see either the old complete
file or the new complete file — never a partial write.

#### Pruning on write

Expired entries are pruned **before every write** to `sync-state.json`, not in a separate
background job. Concretely, any entry whose `last_seen_at` is older than 14 days is
dropped from the in-memory map before serialisation. This keeps the file tidy without
requiring a scheduler or cron job, and ensures expired entries are removed the next time
any write occurs (registration, unregistration, or a delivery that triggers a 404/410
cleanup).

### Delivery logic

After every commit to `main` (i.e. after any `PUT /dav/{id}/database.kdbx` that results
in a new git commit), the server:

1. Reads `sync-state.json` into memory. Because expired entries are pruned on every write,
   all entries present in the file are within the 14-day TTL.
2. For each endpoint URL in the loaded state, sends:

```
POST {endpoint_url}
Content-Type: application/json

{ "event": "branch-updated" }
```

3. Uses a short timeout (e.g. 5 s) and does **not** retry on failure — push delivery is
   best-effort. Clients that miss a push will catch up via periodic sync.
4. Delivery is fire-and-forget; it must not block the HTTP response to the uploading
   client or delay the git commit. Run deliveries in a background goroutine/task.
5. Endpoints that return 404 or 410 from the push provider have been permanently revoked.
   Remove them from the in-memory state, prune expired entries, then atomically save
   `sync-state.json`.

### Endpoint expiry

There is no background cleanup job. Expired entries (those with `last_seen_at` older than
14 days) are pruned from the in-memory state **before every write** to `sync-state.json`.
This means entries are cleaned up the next time any mutation occurs — registration,
unregistration, or a 404/410 removal after delivery.

The 14-day TTL pairs with the client's 3-day refresh schedule (see Android client design
below): a healthy client refreshes roughly every 3 days, so 14 days gives more than four
missed refresh cycles before expiry — enough headroom for a device that's offline for an
extended period or simply not charging.

### Notification payload

Plain (unencrypted, for distributors without keys):
```json
{ "event": "branch-updated" }
```

Encrypted (RFC 8291, for subscriptions that include `keys`):
```
Content-Type: application/octet-stream
Content-Encoding: aes128gcm

<encrypted bytes>
```
The plaintext before encryption is `{"event":"branch-updated"}`.

The payload carries **no database content and no credentials**. It is a pure wakeup
signal. The client discards the payload body and simply triggers a normal sync cycle.
When encryption is used, the payload is protected end-to-end between server and device
using the client's P-256 ECDH key pair and auth secret.

---

## Android Client Design

### Library

```kotlin
// build.gradle.kts
implementation("org.unifiedpush.android:connector:3.x.x")
implementation("org.unifiedpush.android:embedded-fcm-distributor:3.x.x")
```

The connector library provides:
- `UnifiedPush.register()` / `UnifiedPush.unregister()` — registration lifecycle
- `MessagingReceiver` — abstract `BroadcastReceiver` subclass with typed callbacks
  (`PushEndpoint`, `PushMessage`, `FailedReason`)
- `UnifiedPush.tryUseCurrentOrDefaultDistributor()` — deeplink-based distributor selection
- `UnifiedPush.getDistributors()` / `UnifiedPush.saveDistributor()` — manual distributor
  selection (used as fallback for the embedded FCM distributor)

The `embedded-fcm-distributor` artifact bundles a fallback distributor that routes push
messages through Google's FCM infrastructure when no external UP distributor is installed.
It requires Play Services but not the Firebase SDK or `google-services.json`.

### New class: `PushReceiver`

```kotlin
class PushReceiver : MessagingReceiver() {

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        // Called when UP assigns (or reassigns) an endpoint URL.
        // Save locally and enqueue registration with the kdbx-git server.
        SettingsRepository(context).savePushEndpoint(endpoint.url)
        PushRegistrationWorker.enqueue(context)
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        // Wakeup signal received — trigger an expedited pull-only sync.
        SyncWorker.enqueueSyncNow(context, SyncTrigger.PUSH)
    }

    override fun onUnregistered(context: Context, instance: String) {
        // Distributor revoked the registration — inform the server.
        PushRegistrationWorker.enqueueDelete(context)
        SettingsRepository(context).clearPushEndpoint()
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        SettingsRepository(context).clearPushEndpoint()
    }
}
```

Declared in `AndroidManifest.xml` with the intent filter required by the UP library
(the library's documentation specifies the exact action strings).

### New class: `PushRegistrationWorker`

A `CoroutineWorker` that performs the network call to `POST /push/{id}/endpoint` or
`DELETE /push/{id}/endpoint`. Separating registration from `PushReceiver` means the
broadcast receiver returns quickly (required by Android) and the HTTP call benefits from
WorkManager's retry logic if the server is temporarily unreachable.

```
enqueue(endpoint)  →  POST /push/{client_id}/endpoint   { "endpoint": "..." }
enqueueDelete()    →  DELETE /push/{client_id}/endpoint
```

Both requests use the same HTTP Basic credentials as the WebDAV client.

### Registration lifecycle

Distributor selection and `UnifiedPush.register()` use a two-pass approach, because the
embedded FCM distributor does not register a `unifiedpush://link` deeplink Activity (only
a broadcast receiver), so `tryUseCurrentOrDefaultDistributor` alone cannot find it:

```
App start / settings save
    │
    ▼
1. Fetch VAPID public key  ←── GET /push/{id}/vapid-public-key
    │
    ▼
2. tryUseCurrentOrDefaultDistributor()  (deeplink scan)
    │
    ├─ success ──► UnifiedPush.register(vapid = key)
    │
    └─ failure (no deeplink handler)
         │
         ▼
       getDistributors()  (ACTION_REGISTER broadcast scan)
         │
         ├─ found ──► saveDistributor() → UnifiedPush.register(vapid = key)
         │
         └─ none  ──► log warning, periodic sync only
              │
              ▼
         PushReceiver.onNewEndpoint(endpoint)
              │
              ▼
         PushRegistrationWorker → POST /push/{id}/endpoint


Credentials changed / server URL changed
    │
    ▼
UnifiedPush.unregister(context)
    │
    ▼
PushReceiver.onUnregistered()
    │
    ▼
PushRegistrationWorker → DELETE /push/{id}/endpoint
```

The VAPID key is passed to `register()` unconditionally — external distributors silently
ignore it, and the embedded FCM distributor requires it.

### Periodic endpoint refresh

To keep the server's `last_seen_at` current and prevent expiry, the app re-POSTs its
stored endpoint URL to the server every **3 days**, but only while the device is charging.
This is implemented as a `PeriodicWorkRequest` with a 3-day repeat interval and a
`CHARGING` constraint:

```kotlin
PeriodicWorkRequestBuilder<PushRegistrationWorker>(3, TimeUnit.DAYS)
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresCharging(true)
            .build()
    )
    .setInputData(workDataOf(KEY_ACTION to ACTION_REGISTER))
    .build()
```

The `CHARGING` constraint means the refresh only runs opportunistically (no battery cost
to the user) while still providing enough refresh cycles to stay well within the 14-day
server TTL. If the device hasn't charged for an unusually long time the endpoint may
expire, but the app re-registers automatically on the next settings-configured startup.

The refresh worker reuses the `endpoint` URL already stored in `EncryptedSharedPreferences`
by `PushReceiver.onNewEndpoint` — it does not call `UnifiedPush.registerApp()` again
(which would be a full UP re-registration). It simply re-POSTs the existing URL to bump
`last_seen_at` on the server.

### Settings UI additions

In `SettingsScreen`:

- **"Instant sync (UnifiedPush)"** — toggle (enabled by default when a distributor is
  present, hidden or disabled with a note when none is installed).
- **Status row** — shows one of:
  - `"Active — via {distributor name}"`
  - `"No distributor installed — using periodic sync only"`
  - `"Registration pending…"`
  - `"Registration failed — tap to retry"`

No endpoint URL is shown to the user; it is an implementation detail.

### Fallback behaviour

The `embedded-fcm-distributor` library is bundled in the app as an automatic fallback for
devices that have no external UP distributor installed. When Play Services are available,
`getDistributors()` returns the app's own package name and the embedded distributor handles
delivery via Google's FCM infrastructure. No external distributor app is needed.

If Play Services are also unavailable:
- `getDistributors()` returns an empty list.
- The app falls back to periodic WorkManager sync only.
- A status message in Settings explains that instant sync is unavailable.

---

## End-to-End Flow

```
KeePassDX saves file
       │
       ▼
KdbxDocumentsProvider.commitStaging()
       │  enqueue expedited SyncWorker(WRITE)
       ▼
SyncRepository.sync()
  └─ PUT /dav/{id}/database.kdbx   ←── server merges → commits to main
  └─ GET /dav/{id}/database.kdbx
       │
       │  (server, after commit)
       ▼
server reads sync-state.json, iterates endpoints
  └─ POST https://ntfy.example.com/...   { "event": "branch-updated" }
       │
       ▼
ntfy delivers to distributor app on other devices
       │  (broadcast intent)
       ▼
PushReceiver.onMessage()  [other devices]
  └─ SyncWorker.enqueueSyncNow(PUSH)
       │
       ▼
SyncRepository.sync()
  └─ GET /dav/{id}/database.kdbx   (hash differs → overwrite local file)
  └─ notifyChange() → KeePassDX reloads
```

End-to-end latency from save to other devices receiving the update: typically **1–5 s**,
dominated by the push provider delivery time.

---

## Security Considerations

| Concern | Mitigation |
|---|---|
| Endpoint URL leakage | Endpoint is stored in `EncryptedSharedPreferences`; never logged or displayed in UI |
| Payload snooping | Payload contains no credentials or database content — only `{"event":"branch-updated"}` |
| Spoofed push messages | A fake wakeup triggers a sync that fetches from the authenticated server; an attacker cannot inject false data, only cause a spurious (harmless) sync |
| Stale endpoints | Server removes endpoints that return 404/410; client re-registers on app reinstall |
| No GCM/FCM dependency | UnifiedPush works without Google Play Services; compatible with de-Googled devices and F-Droid |

---

## Open Questions

- **Per-client vs. broadcast push**: Currently the design pushes to *all* registered
  clients when *any* client commits. An optimisation would be to skip the pushing client
  (it already has the latest data), but the resulting no-op sync is cheap enough that this
  is not necessary initially.
- **Multiple UP instances per client**: The UP library supports multiple instances per app.
  For now, a single instance (and a single endpoint per client ID) is sufficient.
