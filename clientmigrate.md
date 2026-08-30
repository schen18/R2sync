# Migrating companion apps to the new R2sync identity

R2sync was rebranded from its prototype identity to a permanent one. The
provider API — paths, call methods, extras, columns, and sync semantics — is
**unchanged**. Only the identity strings moved: the applicationId, the
content-provider authority, the signature-level permission names, and the MIME
types. Every companion app that talks to the hub must update those strings and
re-pair with the new hub build.

> If you have **not** integrated with the hub yet, ignore this file and follow
> `app/src/main/java/com/dissonance/r2sync/client/README.md` instead — it
> already describes the new identity.

## What changed

| Item | Old | New |
|---|---|---|
| Hub applicationId | `com.aistudio.r2sync.cfsync` | `com.dissonance.r2sync` |
| Hub code namespace | `com.example` (+ subpackages) | `com.dissonance.r2sync` |
| Hub ContentProvider authority | `com.aistudio.r2sync.cfsync.provider.r2hub` | `com.dissonance.r2sync.provider.r2hub` |
| SAF DocumentsProvider authority | `com.aistudio.r2sync.cfsync.provider.documents` | `com.dissonance.r2sync.provider.documents` |
| Read permission | `com.aistudio.r2sync.cfsync.permission.ACCESS_R2_HUB` | `com.dissonance.r2sync.permission.ACCESS_R2_HUB` |
| Write permission | `com.aistudio.r2sync.cfsync.permission.WRITE_R2_HUB` | `com.dissonance.r2sync.permission.WRITE_R2_HUB` |
| MIME type (dir / item) | `vnd.android.cursor.dir/vnd.com.example.r2hub.file` / `vnd.android.cursor.item/vnd.com.example.r2hub.file` | `vnd.android.cursor.dir/vnd.com.dissonance.r2sync.r2hub.file` / `vnd.android.cursor.item/vnd.com.dissonance.r2sync.r2hub.file` |

Also be aware of a **pre-prototype authority** (`content://com.example.provider.r2hub`)
that appears in very old design docs; if your code or stored data still
references it, treat it the same as the old authority above.

## What did NOT change

- Resource path segments: `files`, `file`, `sync`, `status`, `clients`
- Call methods: `trigger_sync`, `get_status`, `register_client`, `mark_dirty`
- Call extras: `extra_namespace`, `extra_relative_path`, `extra_package_name`,
  `extra_app_name`, `extra_force_immediate`
- Column names and row structure of all three tables (`Files`, `Status`, `Clients`)
- Sync semantics: writes are cached locally and marked dirty when the write
  stream closes; deletions queue a remote delete; the periodic worker also
  downloads remote changes into the vault
- **Your own applicationId** — you keep your package. The client is identified
  to the hub at registration time via `context.packageName`, not by anything
  you hardcode.
- The signature-level requirement: hub and companion must be signed with the
  **same certificate**.

## Why the old strings stop working

Changing the hub's applicationId makes the new build a **different app** — it
installs side-by-side with the old one and nothing (vault DB, permissions,
authorities) carries over:

1. **Authority**: the new hub publishes `com.dissonance.r2sync.provider.r2hub`.
   Calls to the old authority throw `IllegalArgumentException: Unknown
   authority` once the old app is uninstalled — or silently hit the *old,
   stale* hub while both are installed.
2. **Permissions**: `com.aistudio.r2sync.cfsync.permission.*` no longer exists
   in any installed app, so those grants are never issued. Even with the
   correct new authority, calls fail with `SecurityException` until you declare
   the new permission names.
3. **Package visibility (API 30+)**: your `<queries>` element must list the new
   authority or the provider is invisible to your process on Android 11+.
4. **Persisted URIs**: any `content://com.aistudio.r2sync.cfsync...` URI you
   stored (preferences, database columns) is dead and must be discarded.

## Migration checklist

### 1. Manifest

```diff
-    <uses-permission android:name="com.aistudio.r2sync.cfsync.permission.ACCESS_R2_HUB" />
-    <uses-permission android:name="com.aistudio.r2sync.cfsync.permission.WRITE_R2_HUB" />
+    <uses-permission android:name="com.dissonance.r2sync.permission.ACCESS_R2_HUB" />
+    <uses-permission android:name="com.dissonance.r2sync.permission.WRITE_R2_HUB" />

     <queries>
-        <provider android:authorities="com.aistudio.r2sync.cfsync.provider.r2hub" />
+        <provider android:authorities="com.dissonance.r2sync.provider.r2hub" />
     </queries>
```

Delete the old entries — do not keep both. Stale permission names are never
granted and an old `<queries>` authority only adds confusion.

### 2. Code constants

Replace the authority/permission/MIME constants you copied from
`R2HubContract` with the current ones (copy the file again from
`app/src/main/java/com/dissonance/r2sync/provider/R2HubContract.kt`, or apply):

```diff
-    const val AUTHORITY = "com.aistudio.r2sync.cfsync.provider.r2hub"
+    const val AUTHORITY = "com.dissonance.r2sync.provider.r2hub"

-    const val PERMISSION_ACCESS_HUB = "com.aistudio.r2sync.cfsync.permission.ACCESS_R2_HUB"
-    const val PERMISSION_WRITE_HUB = "com.aistudio.r2sync.cfsync.permission.WRITE_R2_HUB"
+    const val PERMISSION_ACCESS_HUB = "com.dissonance.r2sync.permission.ACCESS_R2_HUB"
+    const val PERMISSION_WRITE_HUB = "com.dissonance.r2sync.permission.WRITE_R2_HUB"

-    const val CONTENT_TYPE = "vnd.android.cursor.dir/vnd.com.example.r2hub.file"
-    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.com.example.r2hub.file"
+    const val CONTENT_TYPE = "vnd.android.cursor.dir/vnd.com.dissonance.r2sync.r2hub.file"
+    const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.com.dissonance.r2sync.r2hub.file"
```

Then sweep your whole project for stragglers. Every one of these patterns must
return zero hits in `src/`:

```
com.aistudio.r2sync.cfsync
com.example.provider.r2hub      (pre-prototype authority)
vnd.com.example.r2hub
com/aistudio/r2sync
```

### 3. Discard persisted old-authority URIs

If you persist `content://` URIs from the hub (attachment references, cursors
to re-query, etc.), treat any old-authority URI as invalid at startup and
re-resolve it through the new contract:

```kotlin
private val LEGACY_HUB_AUTHORITIES = setOf(
    "com.aistudio.r2sync.cfsync.provider.r2hub",
    "com.example.provider.r2hub",
)

fun Uri.isLegacyHubUri(): Boolean = authority in LEGACY_HUB_AUTHORITIES
```

Don't attempt to rewrite stored URIs by string substitution — the new hub's
vault starts empty, so an old row's file id may not exist there. Drop the
reference and let your normal sync/restore flow re-fetch it under the new
authority.

### 4. Re-pair with the new hub

The new hub build has a **fresh client registry** (its Room DB is new private
storage). After both apps are updated and installed:

1. Call `R2HubClient.registerClient(...)` again (it sends your
   `context.packageName`, app label, and namespace — nothing for you to
   hardcode).
2. Confirm `get_status` succeeds; your app should reappear in the hub's
   Clients screen.

### 5. Install order & signing

- Build both apps from the refactored sources and install the **hub first**,
  then the companion. Signature-level permissions are granted at install time
  when the certificates match; reinstalling either app re-evaluates the grants.
- Both APKs must be signed with the same certificate (for local testing: the
  standard debug keystore). A mismatch means every provider call throws
  `SecurityException`.
- The old hub app (`com.aistudio.r2sync.cfsync`) remains installed
  side-by-side. Keep it until the new pairing is verified, then uninstall it —
  but see the data note below first.

### 6. Data: what survives, what doesn't

- **Cloudflare R2 bucket contents survive** — the bucket is untouched by the
  rebrand. The new hub's first 3-phase worker pass downloads remote changes
  into its vault, so previously synced files come back automatically.
- **The old hub's local vault does not transfer.** Un-synced ("dirty") writes
  that existed only in the old app's vault are lost when it is uninstalled.
  Before removing the old app, open it once (charging + Wi-Fi, or trigger a
  sync) and let it finish uploading so the bucket is current.
- Namespace names (`e.g. geonoted`) are just string keys under the same
  `hub/<namespace>/` bucket prefix — no bucket-side migration is needed.

## Verification

```bash
# Both identities may be listed side-by-side until you uninstall the old one
adb shell pm list packages | grep r2sync

# The new hub publishes the new authorities
adb shell dumpsys package com.dissonance.r2sync | grep -i "provider.r2hub"

# Your app holds the NEW permission names (granted=true)
adb shell dumpsys package <your.applicationId> | grep R2_HUB
```

In-app: `registerClient` returns `true`, `get_status` returns engine state,
and logcat shows no `SecurityException` or `Unknown authority` from
`R2HubClient`. If `get_status` throws `SecurityException`, the permission
names or signing certificates don't match; if it throws
`IllegalArgumentException: Unknown authority`, the authority constant or
`<queries>` entry is still old.

## Rollback

Until you uninstall the old hub, rollback is trivial: revert the constant
changes and reinstall your previous APK — the old pairing still works. After
the old hub is gone, the only path back is reinstalling an old-identity R2sync
build and re-syncing from the bucket (its vault DB was removed with the
uninstall).
