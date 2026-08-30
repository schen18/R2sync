# R2sync

Android helper app that syncs **local device folders** to **Cloudflare R2** and
acts as a **sync hub for companion apps** (e.g. [GeoNotes](../)) through a
secured ContentProvider.

```
Companion app (GeoNotes) ──ContentProvider──▶ Hub vault ──R2SyncWorker──▶ Cloudflare R2
Local device folders ────SAF picker────────▶ SyncEngine ──FolderSyncWorker──▶ Cloudflare R2
```

Both paths share one R2 bucket configuration and run as WorkManager background
jobs with energy-aware constraints (Wi-Fi / charging / battery).

## Requirements

- Android Studio (minSdk 24, targetSdk 35)
- A Cloudflare R2 account: **Account ID**, an S3-style **Access Key ID +
  Secret Access Key**, and a **bucket**

There is no offline or sandbox mode — without credentials every operation
fails with a clear "R2 not configured" error and the status chip shows
**Not Configured**.

## Getting started

1. Open the project in Android Studio and run the `app` configuration on a
   device or emulator.
2. Tap the status chip (top bar) → **Configure** and enter your R2
   credentials. **Test Connection** verifies them against the bucket.
   - The secret access key is stored in `EncryptedSharedPreferences`
     (Keystore-encrypted) and is excluded from cloud backups and device
     transfers (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).
3. The chip turns **Live R2** when configured.

## Folder sync

- **Add a folder** via the system folder picker (`OpenDocumentTree`). The app
  takes a persistable read/write grant on the tree — folders whose grant was
  lost show a warning in the folder editor and must be re-picked.
- Per-folder settings: sync direction (two-way / upload-only / download-only),
  R2 key prefix, file-extension filter, hidden-file exclusion, conflict
  strategy (Manual review · Newest · Keep local · Keep remote · Keep both),
  auto-sync toggle.
- **Two-way semantics**: locally changed files upload; remotely changed files
  download when the local copy is untouched; simultaneous changes raise a
  conflict handled by the folder's strategy (pending conflicts are listed on
  the Conflicts screen). Identical content is skipped via MD5/etag checks.
- Background passes run on a WorkManager periodic job whose interval and
  constraints follow **Settings → Energy** (master toggle, interval, Wi-Fi
  only, charging only, pause on low battery).

## Hub provider for companion apps

Companion apps sync files through the hub ContentProvider instead of talking
to R2 themselves:

- Authority: `com.dissonance.r2sync.provider.r2hub`
- Access requires the **signature-level** custom permissions
  (`…permission.ACCESS_R2_HUB` / `WRITE_R2_HUB`) — companion and hub must be
  **signed with the same certificate**.
- Files live in per-client **namespaces** in a local vault; writes are cached
  locally and marked dirty when the write stream **closes**, then uploaded.
  Deletions queue a `DELETE_PENDING` state the worker propagates to R2.
- The `R2SyncWorker` runs three phases on a 15-minute periodic job (and
  immediately after writes): propagate pending deletes → upload dirty files →
  **download remote changes** into the vault, so edits made by other devices
  or the web editor reach companion apps automatically.
- **Reference client library**: `app/src/main/java/com/dissonance/r2sync/client/`
  contains a drop-in `R2HubClient` + models and a README with the full
  integration recipe (manifest entries, permission strings, semantics). The
  helper itself does not depend on it.
- **Existing integrations**: if your companion app was built against the old
  identity (`com.aistudio.r2sync.cfsync` / `com.example`), see
  [clientmigrate.md](clientmigrate.md) for the migration steps.

## Building a release APK

Release builds refuse to sign with the public debug keystore. Configure a
keystore in `local.properties` (or environment variables):

```properties
KEYSTORE_PATH=/absolute/path/to/keystore
STORE_PASSWORD=…
KEY_ALIAS=upload          # optional, defaults to "upload"
KEY_PASSWORD=…
```

- For **local testing / pairing with a debug-signed companion app** (required
  for the signature-level hub permissions!), point `KEYSTORE_PATH` at the
  standard machine debug keystore (`~/.android/debug.keystore`, passwords
  `android`, alias `androiddebugkey`) so both apps share a certificate.
- Alternatively, `ALLOW_DEBUG_SIGNING=true` in `local.properties` explicitly
  permits debug-key signing for throwaway builds.

## Project layout

```
app/src/main/java/com/dissonance/r2sync/
├── client/            Reference client library for companion apps (+ README)
├── provider/          Hub ContentProvider, SAF DocumentsProvider, vault manager
├── r2/                SigV4 R2 client (streaming upload/download, pagination)
├── sync/              SyncEngine (folder sync, conflicts, energy gating)
├── work/              R2SyncWorker (hub, 3-phase) + FolderSyncWorker (periodic)
├── data/              Room DB (v4, schemas exported), DAOs, repository
├── energy/            Battery/Wi-Fi/charging policy
└── ui/                Compose screens (Dashboard, Folders, Hub, Conflicts, History, Settings)
```

Notable internals: media transfers stream (never whole-file buffered), the
sync history table is pruned to the newest 1000 rows, Room schemas are
exported under `app/schemas/` for future migrations, and R2 credentials never
leave the device in backups.
