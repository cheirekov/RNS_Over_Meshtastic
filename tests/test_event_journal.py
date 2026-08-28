from pathlib import Path

from rns_meshtastic.event_journal import MAX_EVENTS, EventJournal


def test_event_journal_is_bounded_and_cursor_readable(tmp_path: Path):
    journal = EventJournal(tmp_path / "events.jsonl")
    for index in range(MAX_EVENTS + 5):
        journal.append("console", "info", "test", f"event {index}")
    first = journal.read(limit=10)
    assert len(journal.read(limit=MAX_EVENTS)["events"]) == MAX_EVENTS
    assert len(first["events"]) == 10
    assert first["events"][0]["id"] < 2**53
    second = journal.read(after=first["cursor"], limit=10)
    assert second["events"][0]["id"] > first["cursor"]
    assert (tmp_path / "events.jsonl").stat().st_mode & 0o777 == 0o600


def test_event_journal_redacts_environment_secrets(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("EXAMPLE_PASSWORD", "do-not-return")
    journal = EventJournal(tmp_path / "events.jsonl")
    journal.append("test", "error", "example", "failure do-not-return")
    assert "do-not-return" not in str(journal.read())
