#!/usr/bin/env python3
"""Build the upstream GhostLock HOCON profiles into GLK1 assets.

The Android runtime intentionally receives the typed GLK1 document only.  This
small parser covers the HOCON subset used by ``kernel_profiles`` (objects,
arrays, includes, comments and scalar values) so the APK build does not need a
HOCON dependency at runtime.
"""
from __future__ import annotations

import argparse
import json
import re
import struct
from pathlib import Path
from typing import Any, Iterator


MAGIC = 0x0D000721
VERSION = 2
FRONTEND_ROOT_CHILD = 1
BACKEND_CVE_43499 = 1
ROUTES = {"tcp_zerocopy": 1, "select_stack": 2, "multicast_waiter": 3}


def deep_merge(base: dict[str, Any], overlay: dict[str, Any]) -> dict[str, Any]:
    for key, value in overlay.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            deep_merge(base[key], value)
        else:
            base[key] = value
    return base


def strip_comments(text: str) -> str:
    out: list[str] = []
    quoted = False
    escaped = False
    i = 0
    while i < len(text):
        ch = text[i]
        if quoted:
            out.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                quoted = False
            i += 1
            continue
        if ch == '"':
            quoted = True
            out.append(ch)
            i += 1
            continue
        if ch == '#':
            while i < len(text) and text[i] not in "\r\n":
                i += 1
            continue
        if ch == '/' and i + 1 < len(text) and text[i + 1] == '/':
            while i < len(text) and text[i] not in "\r\n":
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


TOKEN_RE = re.compile(r'\s+|([{}\[\]=:,])|("(?:\\.|[^"\\])*")|([^\s{}\[\]=:,]+)')


def tokens(text: str) -> Iterator[tuple[str, str]]:
    pos = 0
    while pos < len(text):
        match = TOKEN_RE.match(text, pos)
        if not match:
            raise ValueError(f"cannot tokenize HOCON near {text[pos:pos + 40]!r}")
        pos = match.end()
        if match.group(1):
            yield match.group(1), match.group(1)
        elif match.group(2):
            yield "string", json.loads(match.group(2))
        elif match.group(3):
            yield "word", match.group(3)


class Parser:
    def __init__(self, text: str):
        self.items = list(tokens(strip_comments(text)))
        self.index = 0

    def peek(self) -> tuple[str, str] | None:
        return self.items[self.index] if self.index < len(self.items) else None

    def pop(self) -> tuple[str, str]:
        item = self.peek()
        if item is None:
            raise ValueError("unexpected end of HOCON")
        self.index += 1
        return item

    def parse(self) -> dict[str, Any]:
        return self.object_body(False)

    def key(self) -> str:
        kind, value = self.pop()
        if kind not in ("word", "string"):
            raise ValueError(f"expected key, got {kind}:{value}")
        return value

    def object_body(self, wrapped: bool) -> dict[str, Any]:
        if wrapped:
            kind, value = self.pop()
            if value != "{":
                raise ValueError("expected object start")
        out: dict[str, Any] = {}
        while True:
            item = self.peek()
            if item is None:
                if wrapped:
                    raise ValueError("unterminated object")
                return out
            if wrapped and item[1] == "}":
                self.pop()
                return out
            if item[1] == ",":
                self.pop()
                continue
            name = self.key()
            next_item = self.peek()
            if next_item is not None and next_item[1] == "{":
                value = self.object_body(True)
            else:
                if next_item is None or next_item[1] not in ("=", ":"):
                    raise ValueError(f"expected assignment for {name}")
                self.pop()
                value = self.value()
            if isinstance(out.get(name), dict) and isinstance(value, dict):
                deep_merge(out[name], value)
            else:
                out[name] = value

    def value(self) -> Any:
        item = self.peek()
        if item is None:
            raise ValueError("missing value")
        if item[1] == "{":
            return self.object_body(True)
        if item[1] == "[":
            self.pop()
            result: list[Any] = []
            while True:
                item = self.peek()
                if item is None:
                    raise ValueError("unterminated array")
                if item[1] == "]":
                    self.pop()
                    return result
                if item[1] == ",":
                    self.pop()
                    continue
                result.append(self.value())
        kind, raw = self.pop()
        if kind == "string":
            return raw
        if raw == "null":
            return None
        if raw == "true":
            return True
        if raw == "false":
            return False
        if re.fullmatch(r"[-+]?0[xX][0-9a-fA-F]+", raw):
            return int(raw, 16)
        if re.fullmatch(r"[-+]?\d+", raw):
            return int(raw, 10)
        return raw


def expand(path: Path, visiting: set[Path]) -> str:
    path = path.resolve()
    if path in visiting:
        raise ValueError(f"recursive include: {path}")
    visiting.add(path)
    lines: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"\s*include\s+\"([^\"]+)\"\s*$", line)
        if match:
            lines.append(expand(path.parent / match.group(1), visiting))
        else:
            lines.append(line)
    visiting.remove(path)
    return "\n".join(lines) + "\n"


def parse_file(path: Path) -> dict[str, Any]:
    value = Parser(expand(path, set())).parse()
    if not isinstance(value, dict):
        raise ValueError(f"profile is not an object: {path}")
    return value


def get_path(root: dict[str, Any], path: str, default: Any = None) -> Any:
    value: Any = root
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            return default
        value = value[part]
    return value


def route_name(profile: dict[str, Any]) -> str:
    route = profile.get("route")
    if isinstance(route, str):
        return route
    if isinstance(route, dict):
        for name in ROUTES:
            if name in route:
                return name
    raise ValueError(f"profile has no supported route: {profile.get('release')}")


def fallback_name(profile: dict[str, Any]) -> str | None:
    fallback = profile.get("fallback")
    if isinstance(fallback, dict):
        value = fallback.get("to")
        if isinstance(value, str) and value != "none":
            return value
    value = profile.get("fallback_to")
    return value if isinstance(value, str) and value != "none" else None


def route_value(profile: dict[str, Any], route: str, key: str) -> Any:
    branch = profile.get("route")
    if isinstance(branch, dict):
        branch = branch.get(route)
        if isinstance(branch, dict) and key in branch:
            return branch[key]
    fallback = profile.get("fallback")
    if isinstance(fallback, dict):
        fallback_routes = fallback.get("route")
        if isinstance(fallback_routes, dict):
            branch = fallback_routes.get(route)
            if isinstance(branch, dict) and key in branch:
                return branch[key]
    return None


def u64(value: Any) -> int:
    if value is None:
        return 0
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value & ((1 << 64) - 1)
    return int(str(value), 0) & ((1 << 64) - 1)


def section(name: str, entries: list[tuple[str, int]]) -> bytes:
    name_bytes = name.encode()
    out = bytearray([len(name_bytes)])
    out += name_bytes
    out += struct.pack("<I", len(entries))
    for key, value in entries:
        key_bytes = key.encode()
        out += bytes([len(key_bytes)]) + key_bytes + struct.pack("<Q", u64(value))
    return bytes(out)


def glk1(profile: dict[str, Any]) -> bytes:
    release = str(profile["release"])
    route = route_name(profile)
    fallback = fallback_name(profile)
    execution = profile.get("execution", {})
    entries: list[bytes] = []
    entries.append(section("meta", [
        ("kernel_major", profile.get("kernel_major", 0)),
        ("recommend_shizuku", profile.get("recommend_shizuku", 0)),
        ("fallback_route", ROUTES.get(fallback, 0)),
        ("safe_mode", 0),
    ]))
    task = profile.get("task_struct", {})
    entries.append(section("task_struct", [(name, task.get(name, 0)) for name in (
        "prio", "normal_prio", "sched_task_group", "pi_lock", "pi_waiters",
        "pi_top_task", "pi_blocked_on", "pid", "tgid", "atomic_flags",
        "real_cred", "cred", "comm", "tasks", "seccomp")]))
    cred = profile.get("cred", {})
    entries.append(section("cred", [(name, cred.get(name, 0)) for name in (
        "copy_size", "usage_offset", "usage_value", "caps_offset", "caps_count",
        "caps_value", "ref_count", "ref0_offset", "ref1_offset", "ref2_offset",
        "ref3_offset", "ref0_image", "ref1_image", "ref2_image", "ref3_image")]))
    offset = profile.get("offset", {})
    entries.append(section("offset", [(name, offset.get(name, 0)) for name in (
        "init_task", "init_cred", "empty_zero_page", "root_task_group",
        "selinux_enforcing", "selinux_blob_sizes", "security_hook_heads",
        "slide_nfulnl_logger", "slide_loggers_0_1", "slide_boot_id")]))
    kernel_entries: list[tuple[str, int]] = []
    if "kernel_phys_load" in profile:
        kernel_entries.append(("kernel_phys_load", profile["kernel_phys_load"]))
    compact = route_value(profile, route, "compact_waiter")
    if compact is not None:
        kernel_entries.append(("compact_waiter", compact))
    snitch = profile.get("kernelsnitch", {})
    if isinstance(snitch, dict):
        if "collisions" in snitch:
            kernel_entries.append(("kernelsnitch_collisions", snitch["collisions"]))
        if "mm_struct_sz" in snitch:
            kernel_entries.append(("mm_struct_sz", snitch["mm_struct_sz"]))
    if kernel_entries:
        entries.append(section("kernel", kernel_entries))
    def execution_map(name: str) -> dict[str, Any]:
        value = execution.get(name, {}) if isinstance(execution, dict) else {}
        return value if isinstance(value, dict) else {}
    cpus = execution_map("recommended_cpus")
    heap = execution_map("heap")
    race = execution_map("race")
    stages = execution_map("stages")
    handoff = execution_map("handoff")
    routes = execution_map("routes")
    select = routes.get("select_stack", {}) if isinstance(routes.get("select_stack"), dict) else {}
    entries += [
        section("execution.recommended_cpus", [("main", cpus.get("main", 0)), ("consumer", cpus.get("consumer", 1))]),
        section("execution.heap", [(name, heap.get(name, 0)) for name in ("prepare_max_attempts", "prepare_timeout_ms", "kernelsnitch_timeout_ms")]),
        section("execution.race", [(name, race.get(name, 0)) for name in ("route_wait_ms", "route_done_timeout_ms", "setup_settle_us", "state_poll_interval_us")]),
        section("execution.stages", [(name, stages.get(name, 0)) for name in ("w1_attempts", "w1_settle_us", "w1_scratch_repair_attempts", "w2_attempts", "w2_settle_us", "w3_chain_rounds", "w3_attempts", "w3_settle_us")]),
        section("execution.handoff", [(name, handoff.get(name, 0)) for name in ("pre_dispatch_settle_ms", "module_poll_attempts", "module_poll_interval_ms", "enforce_poll_attempts", "enforce_poll_interval_ms")]),
        section("execution.consumer", [("max_calls", select.get("consumer_max_calls", 0)), ("burst_calls", select.get("consumer_burst_calls", 0))]),
    ]
    if route == "tcp_zerocopy":
        tcp = routes.get(route, {}) if isinstance(routes.get(route), dict) else {}
        entries.append(section("route.tcp_zerocopy", [(name, tcp.get(name, 0)) for name in ("attempts", "arm_sequence", "post_receive_hold_iterations")]))
    elif route == "select_stack":
        entries.append(section("route.select_stack", [
            ("waiter_shift", route_value(profile, route, "waiter_shift") or 0),
            ("compact_waiter", compact or 0),
            ("enter_delay_us", select.get("enter_delay_us", 0)),
            ("timeout_us", select.get("timeout_us", 0)),
        ]))
    else:
        mcast = profile.get("route", {}).get(route, {}) if isinstance(profile.get("route"), dict) else {}
        entries.append(section("route.multicast_waiter", [(name, mcast.get(name, 0)) for name in ("waiter_off", "buffer_size", "task_offset", "lock_offset")]))
    release_bytes = release.encode()
    header = struct.pack("<IHHHHHH", MAGIC, VERSION, FRONTEND_ROOT_CHILD,
                         BACKEND_CVE_43499, ROUTES[route], len(release_bytes), 0)
    return header + release_bytes + struct.pack("<H", len(entries)) + b"".join(entries)


DEFAULT_EXECUTION = {
    "recommended_cpus": {"main": 0, "consumer": 1},
    "heap": {"prepare_max_attempts": 4, "prepare_timeout_ms": 240000, "kernelsnitch_timeout_ms": 60000},
    "race": {"route_wait_ms": 1000, "route_done_timeout_ms": 300000, "setup_settle_us": 50000, "state_poll_interval_us": 1000},
    "stages": {"w1_attempts": 15, "w1_settle_us": 100000, "w1_scratch_repair_attempts": 3, "w2_attempts": 15, "w2_settle_us": 100000, "w3_chain_rounds": 3, "w3_attempts": 6, "w3_settle_us": 50000},
    "handoff": {"pre_dispatch_settle_ms": 2000, "module_poll_attempts": 30, "module_poll_interval_ms": 100, "enforce_poll_attempts": 200, "enforce_poll_interval_ms": 100},
    "routes": {
        "select_stack": {"enter_delay_us": 50000, "timeout_us": 200000, "consumer_max_calls": 1, "consumer_burst_calls": 1},
        "tcp_zerocopy": {"attempts": 2000, "arm_sequence": 16, "post_receive_hold_iterations": 20000},
    },
}


def main() -> None:
    project = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--profiles", type=Path, default=project / "third_party/ghostlock/upstream/kernel_profiles")
    parser.add_argument("--output", type=Path, default=project / "assistant/src/main/assets/kernel_profiles")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    index = parse_file(args.profiles / "index.conf")
    items = index.get("profiles", [])
    count = 0
    for item in items:
        if not isinstance(item, dict):
            continue
        release = item.get("release")
        file_name = item.get("file")
        if not isinstance(release, str) or release.endswith("-template") or not isinstance(file_name, str):
            continue
        profile = parse_file(args.profiles / file_name)
        deep_merge(profile, json.loads(json.dumps(DEFAULT_EXECUTION)))
        # Profile values have priority over shared defaults.
        profile = parse_file(args.profiles / file_name)
        defaults = json.loads(json.dumps(DEFAULT_EXECUTION))
        profile["execution"] = deep_merge(defaults, profile.get("execution", {}))
        data = glk1(profile)
        (args.output / f"{release}.bin").write_bytes(data)
        count += 1
    print(f"GHOSTLOCK_PROFILES={count} OUTPUT={args.output.resolve()}")


if __name__ == "__main__":
    main()
