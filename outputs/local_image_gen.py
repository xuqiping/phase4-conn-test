#!/usr/bin/env python3
"""Run Codex's official imagegen script through the active local Codex provider."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover
    import tomli as tomllib  # type: ignore


TRANSIENT_MARKERS = (
    "rate_limit",
    "rate limit",
    "429",
    "502",
    "503",
    "timeout",
    "timed out",
)


def parse_args() -> tuple[argparse.Namespace, list[str]]:
    parser = argparse.ArgumentParser(
        description="Inject Codex provider credentials, then call the official imagegen script.",
        add_help=True,
    )
    parser.add_argument("--local-max-attempts", type=int, default=1)
    parser.add_argument("--local-retry-delay", type=float, default=10.0)
    parser.add_argument("--local-config", type=Path, default=Path.home() / ".codex" / "config.toml")
    parser.add_argument("--local-auth", type=Path, default=Path.home() / ".codex" / "auth.json")
    parser.add_argument(
        "--local-imagegen-script",
        type=Path,
        default=Path.home() / ".codex" / "skills" / ".system" / "imagegen" / "scripts" / "image_gen.py",
    )
    parser.add_argument("imagegen_args", nargs=argparse.REMAINDER)
    ns = parser.parse_args()
    forwarded = ns.imagegen_args
    if forwarded and forwarded[0] == "--":
        forwarded = forwarded[1:]
    if not forwarded:
        parser.error("Pass the official imagegen command after local flags, e.g. 'edit --model gpt-image-2 ...'.")
    return ns, forwarded


def load_provider(config_path: Path) -> tuple[str, str]:
    if not config_path.exists():
        raise FileNotFoundError(f"Codex config not found: {config_path}")
    with config_path.open("rb") as f:
        config = tomllib.load(f)

    provider = config.get("model_provider")
    if not provider:
        raise ValueError(f"No model_provider found in {config_path}")

    providers = config.get("model_providers") or {}
    provider_config = providers.get(provider) or {}
    base_url = provider_config.get("base_url") or provider_config.get("api_base_url")
    if not base_url:
        raise ValueError(f"Provider {provider!r} has no base_url/api_base_url in {config_path}")
    return str(provider), str(base_url).rstrip("/")


def find_key(value: object) -> str | None:
    if isinstance(value, dict):
        for key, item in value.items():
            if key == "OPENAI_API_KEY" and isinstance(item, str) and item:
                return item
        for item in value.values():
            found = find_key(item)
            if found:
                return found
    elif isinstance(value, list):
        for item in value:
            found = find_key(item)
            if found:
                return found
    return None


def load_api_key(auth_path: Path) -> str:
    if not auth_path.exists():
        raise FileNotFoundError(f"Codex auth file not found: {auth_path}")
    with auth_path.open("r", encoding="utf-8") as f:
        auth = json.load(f)
    key = find_key(auth)
    if not key:
        raise ValueError(f"OPENAI_API_KEY not found in {auth_path}")
    return key


def infer_endpoint(args: list[str]) -> str:
    command = next((arg for arg in args if not arg.startswith("-")), "")
    if command == "edit":
        return "/v1/images/edits"
    if command in {"generate", "create"}:
        return "/v1/images/generations"
    return "/v1/images"


def read_option(args: list[str], name: str) -> str | None:
    prefix = name + "="
    for index, arg in enumerate(args):
        if arg == name and index + 1 < len(args):
            return args[index + 1]
        if arg.startswith(prefix):
            return arg[len(prefix) :]
    return None


def is_transient(text: str) -> bool:
    lower = text.lower()
    return any(marker in lower for marker in TRANSIENT_MARKERS)


def main() -> int:
    ns, forwarded = parse_args()
    if ns.local_max_attempts < 1:
        raise ValueError("--local-max-attempts must be >= 1")
    if not ns.local_imagegen_script.exists():
        raise FileNotFoundError(f"Official imagegen script not found: {ns.local_imagegen_script}")

    provider, base_url = load_provider(ns.local_config)
    api_key = load_api_key(ns.local_auth)
    endpoint = infer_endpoint(forwarded)
    model = read_option(forwarded, "--model") or "gpt-image-2"

    print(f"provider={provider}")
    print(f"base_url={base_url}")
    print(f"endpoint={endpoint}")
    print(f"model={model}")
    print(f"key_source={ns.local_auth} (OPENAI_API_KEY present, value hidden)")

    env = os.environ.copy()
    env["OPENAI_BASE_URL"] = base_url
    env["OPENAI_API_KEY"] = api_key

    cmd = [sys.executable, str(ns.local_imagegen_script), *forwarded]
    last_output = ""
    for attempt in range(1, ns.local_max_attempts + 1):
        print(f"attempt={attempt}/{ns.local_max_attempts}")
        proc = subprocess.run(cmd, env=env, text=True, capture_output=True)
        if proc.stdout:
            print(proc.stdout, end="")
        if proc.stderr:
            print(proc.stderr, end="", file=sys.stderr)
        if proc.returncode == 0:
            return 0

        last_output = f"{proc.stdout}\n{proc.stderr}"
        if attempt >= ns.local_max_attempts or not is_transient(last_output):
            return proc.returncode
        time.sleep(ns.local_retry_delay)

    print(last_output, file=sys.stderr)
    return 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"local_image_gen_error={type(exc).__name__}: {exc}", file=sys.stderr)
        raise SystemExit(1)
