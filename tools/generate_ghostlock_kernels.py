#!/usr/bin/env python3
"""Generate the APK-side GhostLock kernel list from upstream offset headers."""
from __future__ import annotations

import argparse
import re
from pathlib import Path


PACKAGE = "roro.stellar.yuehong.ghostlock"
INCLUDE_RE = re.compile(r'^\s*#include\s+"([^"/]+/offsets\.h)"\s*$', re.MULTILINE)
RELEASE_RE = re.compile(r'OFFSETS_ENTRY\(\s*"([^"]+)"')


def java_string(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def collect_releases(vendor_root: Path) -> list[str]:
    kernels_root = vendor_root / "src" / "kernels"
    shared_header = kernels_root / "offsets.h"
    includes = INCLUDE_RE.findall(shared_header.read_text(encoding="utf-8"))
    if not includes:
        raise RuntimeError(f"no upstream kernel includes found in {shared_header}")

    releases: list[str] = []
    seen: set[str] = set()
    for relative in includes:
        header = kernels_root / relative
        if not header.is_file():
            raise RuntimeError(f"missing upstream kernel header: {header}")
        matches = RELEASE_RE.findall(header.read_text(encoding="utf-8"))
        if not matches:
            raise RuntimeError(f"no OFFSETS_ENTRY release found in {header}")
        for release in matches:
            if not release.startswith("6."):
                raise RuntimeError(f"non-6.x kernel found in upstream support list: {release}")
            if release not in seen:
                seen.add(release)
                releases.append(release)
    return releases


def render(releases: list[str]) -> str:
    values = "\n".join(f'        "{java_string(release)}",' for release in releases)
    return f'''package {PACKAGE};

/** Generated from the bundled upstream kernel offset headers; do not edit. */
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
