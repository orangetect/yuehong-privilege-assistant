# GhostLock upstream mirror

This directory mirrors the native `src/` tree, Rust extractor sources, and
`kernel_profiles/` data from the GhostLock upstream archive supplied for the
2026-09-29 sync.

The YueHong App now runs this mirrored native core. `GhostLockActivity` keeps
its existing OTA, JSON import, root handoff script, log file, retry, and screen
flow. Before each native attempt it resolves the current kernel profile to the
upstream GLK1 v2 binary document and writes that document to
`libghostlock.so --ghostlock-app-call` through stdin. APK profiles are generated
from the upstream HOCON files at build time; a matching imported `offsets.json`
remains the first choice for the current release and is converted to GLK1 by
the App-side legacy adapter.
