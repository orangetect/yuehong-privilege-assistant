#!/usr/bin/env python3
"""Generate the APK-side GhostLock kernel list from the upstream profile index."""
from __future__ import annotations

import argparse
import re
from pathlib import Path


PACKAGE = "roro.stellar.yuehong.ghostlock"
PROFILE_RE = re.compile(
    r'\{\s*release\s*=\s*"([^"]+)"\s*,\s*file\s*=\s*"([^"]+)"\s*\}'
)


def java_string(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def collect_releases(vendor_root: Path) -> list[str]:
    profiles_root = vendor_root / "app" / "src" / "main" / "assets" / "kernel_profiles"
    index = profiles_root / "index.conf"
    entries = PROFILE_RE.findall(index.read_text(encoding="utf-8"))
    if not entries:
        raise RuntimeError(f"no upstream profiles found in {index}")

    releases: list[str] = []
    seen: set[str] = set()
    for release, filename in entries:
        if not release.startswith("6.") or release.endswith("-template"):
            continue
        profile = profiles_root / filename
        if not profile.is_file():
            raise RuntimeError(f"missing upstream kernel profile: {profile}")
        if release not in seen:
            seen.add(release)
            releases.append(release)
    if not releases:
        raise RuntimeError(f"no exact 6.x kernel profiles found in {index}")
    return releases


def render(releases: list[str]) -> str:
    values = "\n".join(f'        "{java_string(release)}",' for release in releases)
    return f'''package {PACKAGE};

/** Generated from the bundled upstream kernel profile index; do not edit. */
public final class SupportedKernels {{
    public static final String[] UNAMES = {{
{values}
    }};

    private SupportedKernels() {{}}
}}
'''


def main() -> None:
    project_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--vendor-root",
        type=Path,
        default=project_root / "third_party" / "ghostlock",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=project_root
        / "assistant"
        / "src"
        / "main"
        / "java"
        / "roro"
        / "stellar"
        / "yuehong"
        / "ghostlock"
        / "SupportedKernels.java",
    )
    args = parser.parse_args()
    releases = collect_releases(args.vendor_root.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(releases), encoding="utf-8", newline="\n")
    print(
        f"GENERATED_KERNELS={len(releases)} ONLY_6_X=true "
        f"OUTPUT={args.output.resolve()}"
    )


if __name__ == "__main__":
    main()
