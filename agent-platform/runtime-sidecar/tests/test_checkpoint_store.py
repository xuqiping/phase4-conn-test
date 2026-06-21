from app.checkpoint_store import FileCheckpointStore, InMemoryCheckpointStore, create_checkpoint_store


def test_checkpoint_store_saves_and_loads_state_by_ref():
    store = InMemoryCheckpointStore()
    state = {
        "input": {"message": "hello"},
        "visited": ["start-1"],
        "outputs": {"start-1": {"nodeId": "start-1"}},
    }

    store.save("checkpoint-1001", state)
    loaded = store.load("checkpoint-1001")

    assert loaded == state


def test_checkpoint_store_returns_none_for_missing_ref():
    store = InMemoryCheckpointStore()

    assert store.load("missing") is None


def test_file_checkpoint_store_persists_state_across_instances(tmp_path):
    first_store = FileCheckpointStore(tmp_path)
    state = {
        "input": {"message": "hello"},
        "visited": ["start-1", "agent-1"],
        "outputs": {"agent-1": {"nodeId": "agent-1"}},
    }

    first_store.save("checkpoint-1001", state)
    second_store = FileCheckpointStore(tmp_path)

    assert second_store.load("checkpoint-1001") == state


def test_create_checkpoint_store_uses_env_directory(monkeypatch, tmp_path):
    monkeypatch.setenv("RUNTIME_CHECKPOINT_DIR", str(tmp_path))

    store = create_checkpoint_store()
    store.save("checkpoint-1001", {"visited": ["start-1"]})

    assert FileCheckpointStore(tmp_path).load("checkpoint-1001") == {"visited": ["start-1"]}
