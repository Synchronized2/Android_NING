# Built-in avatar verification

Run `node tools/audit_avatar_catalog.cjs` to validate all catalog resource references,
load Cubism 3 models with the bundled Core, and exercise the production framing
functions. The JSON report is written to `artifacts/avatar-audit/catalog-audit.json`.
Effect-like mesh names and unusual bounds are review candidates, not grounds for
automatically hiding a mesh: names containing `BG` or `light` often refer to hair,
clothing, or eyes.

The existing Android instrumentation supports `-e allAvatars true`, with optional
zero-based inclusive `-e from N` / exclusive `-e to N` indices. It opens the actual
character picker, loads every requested model, and saves both full-body and portrait
screenshots plus geometry diagnostics under the app's external `avatar-audit`
directory. It does not select a different character or write chat history; the
original body-mode setting is restored on normal completion. Run in batches of
100 or fewer so interrupted device connections can be resumed.

Pull those files to `artifacts/avatar-audit/device`, then run
`tools/avatar_contact_sheets.ps1` (and `-Mode portrait`) for visual inspection.
A model reporting ready does not prove correct appearance: inspect the sheets for
missing body parts, incorrect UVs, tiny figures, clipping and offsets.

Verified fixes in this audit:

- Cubism 2: measure `document.documentElement`, not Pixi's inline canvas dimensions.
  Pixi autoDensity initially writes 800x600; using that as the viewport cropped and
  displaced characters in the Android preview. Transform actual mesh coordinates
  without the previous ad-hoc horizontal canvas offset.
- Cubism 2: ignore invisible meshes, hidden parts and tap helpers when calculating
  bounds. This fixes models including Wanko and both live_uu entries without hiding
  any rendered character artwork.
- Golden (three variants): exclude the remote `D_PSD.81` shell from framing only.
- Kar98k-normal: exclude the oversized `D_PSD.28` / `D_PSD.30` effect planes from
  framing only. Their rendering is preserved.
- Admiral Graf Spee: exclude the confirmed full-canvas `ArtMesh0` helper from
  rendering and framing. Do not apply its generic mesh name to other characters.
- Shōkaku: exclude the `Part5` fog group, which extends beyond the character and
  pulls the fit away from the actual figure.
- Bismarck alternate entry: its original MOC is incompatible with the only supplied
  texture atlas. Preserve the existing catalog ID for saved selections but point it
  to the working Bismarck MOC and matching preview; the original resource is retained.

Limits: screenshots cover initial idle poses and both framing modes. They cannot
prove every frame of every motion/expression correct on every GPU.

2026-09-23 / 2.3.39: all 273 catalog entries loaded on the connected Android
device (50 Cubism 3, 223 Cubism 2), with 546 full/portrait preview screenshots
reviewed. Resource validation found no missing references. Final device records
are report-0.json (indices 0-49), report-50.json (50-149), report-150.json
(150-249), and report-250.json (250-272). Older overlapping reports are not the
final results. Some source models are intentionally bust/knee-length artwork.
