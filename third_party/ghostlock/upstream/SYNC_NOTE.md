# GhostLock upstream mirror

This directory mirrors the native `src/` tree, Rust extractor sources, and
`kernel_profiles/` data from the GhostLock upstream archive supplied for the
2026-09-29 sync.

The active YueHong App still uses the legacy native/JSON contract in the parent
`third_party/ghostlock` directory. The mirrored upstream binary uses the GLK1
profile stream (`--ghostlock-app-call` / `--load-prebuilt-profile`), so it is kept
isolated until an explicit profile adapter and App call-site migration are added.