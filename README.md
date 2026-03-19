# kdbx-git Android

An Android companion app for [kdbx-git](https://github.com/null-dev/kdbx-git) that keeps your KeePass database automatically synchronized across devices.

## What it does

kdbx-git Android bridges your KeePass app (e.g. KeePassDX) and a self-hosted kdbx-git server. It exposes your synced database as a file through Android's Storage Access Framework, so your KeePass app can open it just like any local file — while the sync happens silently in the background.

- **Automatic sync on save** — when you save a change in KeePass, it's pushed to the server within seconds.
- **Instant sync via UnifiedPush** — when another device pushes a change, all other devices are notified immediately and pull the update.
- **Server-side merge** — if two devices edit the database at the same time, the server performs an entry-level merge. No data is lost.
- **No persistent connection** — the app holds no open network connections when idle. Battery usage is negligible.

## Requirements

- A running **kdbx-git server** that you control
- A **KeePass app** on Android — e.g. [KeePassDX](https://www.keepassdx.com/)
- A **UnifiedPush distributor** installed on the device for instant sync (e.g. [ntfy](https://ntfy.sh/), [Gotify-UP](https://github.com/gotify/android)) — this is **optional** for instant sync to work as Google's [FCM](https://firebase.google.com/docs/cloud-messaging) will be used as a fallback when no UnifiedPush distributor is installed.


## Setup

### 1. Configure the app

Open the kdbx-git Android app and fill in the settings:

| Setting | Description |
|---|---|
| Server URL | The base URL of your kdbx-git server, e.g. `https://kdbx.example.com` |
| Client ID | A unique name for this device, e.g. `phone` or `laptop` |
| Password | The password configured on the server for this client |
| Custom CA certificate | (Optional) PEM-encoded CA certificate, for servers using a self-signed or private CA |

Tap **Save**. The app will immediately attempt an initial sync to download the database.

### 2. Open the database in KeePassDX

1. In KeePassDX, choose **Open an existing database**.
2. Tap the storage picker and select **kdbx-git sync** from the list of storage providers.
3. Choose **database.kdbx**.
4. Enter your KeePass master password.

KeePassDX will reload the database automatically whenever the app pulls a new version from the server.

### 3. (Optional) Enable instant sync

Install a UnifiedPush distributor app on your device. Once installed, the kdbx-git app will register with it automatically — no additional configuration is needed. The **Settings** screen shows the current push status.

If no distributor is installed but Google Play Services is available, the app falls back to push delivery via FCM automatically.

If neither is available, the app falls back to periodic sync only.

## How sync works

- **You save a change** → the app pushes your database to the server immediately.
- **Another device saves a change** → the server notifies this device via push, which pulls the update.
- **Both devices save at the same time** → the server merges the two versions at the entry level and the result is pulled by all devices.
- **You're offline** → changes are queued and pushed as soon as connectivity is restored.

The sync log on the main screen shows a history of every sync attempt, its trigger, and its outcome.

## Security

- Server credentials are stored in Android's `EncryptedSharedPreferences` and never leave the device unencrypted.
- Database access is mediated entirely by Android's Storage Access Framework — no app can read your database without you explicitly granting access through the system file picker.
- Push notifications carry no database content or credentials; they are wake-up signals only.
- Custom CA certificates are supported for servers behind a private or self-signed CA, without disabling certificate validation.

## Multiple devices

Each device registers with its own **Client ID**. The server maintains a separate branch per client and merges all changes into a shared `main` branch. You can add as many devices as you like — just use a different Client ID for each.
