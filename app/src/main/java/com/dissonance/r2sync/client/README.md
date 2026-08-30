# Reference client library for the R2 Hub provider

The files in this folder are the **reference implementation** for companion
apps that want to sync through the R2sync Hub provider. They are not used by
the R2sync app itself — nothing inside the helper depends on them.

## Integrating a companion app

1. Copy `R2HubClient.kt` and `R2HubModels.kt` into the companion app.
2. Copy the constants from `com.dissonance.r2sync.provider.R2HubContract` (authority,
   permission names, path/method/column constants) — or the whole file — into
   the companion app. The **authority and permission strings must match this
   build exactly**:
   - Authority: `com.dissonance.r2sync.provider.r2hub`
   - Permissions (signature-level): `com.dissonance.r2sync.permission.ACCESS_R2_HUB`
     and `…permission.WRITE_R2_HUB`
3. Declare the permissions and the `<queries>` element in the companion's
   manifest:

   ```xml
   <uses-permission android:name="com.dissonance.r2sync.permission.ACCESS_R2_HUB" />
   <uses-permission android:name="com.dissonance.r2sync.permission.WRITE_R2_HUB" />
   <queries>
       <provider android:authorities="com.dissonance.r2sync.provider.r2hub" />
   </queries>
   ```

4. **Sign both apps with the same certificate** — the hub's custom permissions
   are signature-level, so a different signing key means every provider call
   throws `SecurityException`.

## Semantics to be aware of

- Files are written to a local vault cache and marked dirty for upload **when
  the write stream is closed** — always close streams (the client's helpers
  use `use`, which is sufficient).
- `deleteFile` removes the cached copy and queues a remote deletion that the
  helper's worker propagates to R2.
- The helper downloads remote changes into the vault on its periodic sync, so
  reads eventually reflect writes made by other devices or the web editor.
- Use `writeFileStreaming` / `readFileTo` for large payloads; the ByteArray
  `readFile`/`writeFile` variants buffer whole files in memory (fine for
  small JSON documents).
