from __future__ import annotations

from copy import deepcopy
import json
import os
from pathlib import Path
from typing import Any, Protocol
from urllib.parse import quote


class CheckpointStore(Protocol):
    def save(self, checkpoint_ref: str, state: dict[str, Any]) -> None:
        ...

    def load(self, checkpoint_ref: str) -> dict[str, Any] | None:
        ...


class InMemoryCheckpointStore:
    def __init__(self) -> None:
        self._states: dict[str, dict[str, Any]] = {}

    def save(self, checkpoint_ref: str, state: dict[str, Any]) -> None:
        self._states[checkpoint_ref] = deepcopy(state)

    def load(self, checkpoint_ref: str) -> dict[str, Any] | None:
        state = self._states.get(checkpoint_ref)
        return deepcopy(state) if state is not None else None


class FileCheckpointStore:
    def __init__(self, directory: str | Path) -> None:
        self._directory = Path(directory)
        self._directory.mkdir(parents=True, exist_ok=True)

    def save(self, checkpoint_ref: str, state: dict[str, Any]) -> None:
        path = self._path_for(checkpoint_ref)
        path.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")

    def load(self, checkpoint_ref: str) -> dict[str, Any] | None:
        path = self._path_for(checkpoint_ref)
        if not path.exists():
            return None
        return json.loads(path.read_text(encoding="utf-8"))

    def _path_for(self, checkpoint_ref: str) -> Path:
        safe_name = quote(checkpoint_ref, safe="")
        return self._directory / f"{safe_name}.json"


def create_checkpoint_store() -> FileCheckpointStore:
    checkpoint_dir = os.getenv("RUNTIME_CHECKPOINT_DIR")
    if checkpoint_dir:
        return FileCheckpointStore(checkpoint_dir)
    return FileCheckpointStore(Path.cwd() / ".runtime-checkpoints")
