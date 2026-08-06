import json
import tempfile
from pathlib import Path
import pytest
from sec_import.doors.parser import parse_module, _make_pairs_hook, ReportEntry
from sec_import.doors.exceptions import ImportValidationError


def _write_json(data: dict) -> Path:
    f = tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False, encoding="utf-8")
    json.dump(data, f)
    f.close()
    return Path(f.name)


def _minimal_module(**extra) -> dict:
    base = {
        "__objectId": "000969a2",
        "__name": "SRD",
        "__version": "current",
        "url": "doors://host:9601/prefix-M-000969a2",
        "__contents": [],
    }
    base.update(extra)
    return base


class TestDuplicateKeyHook:
    def test_detects_duplicate(self):
        entries: list[ReportEntry] = []
        hook = _make_pairs_hook(entries)
        result = hook([("id", "SRD-1"), ("name", "foo"), ("id", "SRD-99")])
        # First value kept
        assert result["id"] == "SRD-1"
        # Second stored under attr::
        assert result["attr::id"] == "SRD-99"
        assert any(e.category == "duplicate_key" for e in entries)

    def test_no_duplicate(self):
        entries: list[ReportEntry] = []
        hook = _make_pairs_hook(entries)
        result = hook([("a", 1), ("b", 2)])
        assert result == {"a": 1, "b": 2}
        assert entries == []


class TestParseModule:
    def test_parses_valid_file(self):
        m = _minimal_module()
        path = _write_json(m)
        result = parse_module(path)
        assert result.module["__name"] == "SRD"
        assert result.module["__version"] == "current"

    def test_raises_on_invalid_json(self):
        f = tempfile.NamedTemporaryFile(mode="wb", suffix=".json", delete=False)
        f.write(b'{"broken": }')
        f.close()
        with pytest.raises(json.JSONDecodeError) as exc_info:
            parse_module(Path(f.name))
        assert "byte offset" in str(exc_info.value)

    def test_warns_on_unknown_meta_key(self):
        m = _minimal_module(__contents=[{
            "id": "SRD-1023",
            "objectNumber": "1",
            "objectLevel": "1",
            "__objectUrl": "doors://host:9601/prefix-O-1-000969a2",
            "__moduleUrl": "doors://host:9601/prefix-M-000969a2",
            "__tableObject": "false",
            "__tableID": "",
            "__tableURL": "",
            "__tableRowIndex": "",
            "__tableColumnIndex": "",
            "__taSbleRowIndex": "0",   # corrupt/unknown key
            "__outputLinks": [],
            "__inputLinks": [],
        }])
        path = _write_json(m)
        result = parse_module(path)
        warn_cats = [e.category for e in result.entries]
        assert "unknown_meta_key" in warn_cats

    def test_warns_on_truncation(self):
        objs = [
            {
                "id": f"X-{i}",
                "objectNumber": str(i),
                "objectLevel": "1",
                "__objectUrl": f"u-{i}",
                "__moduleUrl": "m",
                "__tableObject": "false",
                "__tableID": "",
                "__tableURL": "",
                "__tableRowIndex": "",
                "__tableColumnIndex": "",
                "__outputLinks": [],
                "__inputLinks": [],
            }
            for i in range(12000)
        ]
        m = _minimal_module(__contents=objs)
        path = _write_json(m)
        result = parse_module(path)
        assert any(e.category == "probable_truncation" for e in result.entries)

    def test_raises_on_missing_required_key(self):
        m = _minimal_module()
        del m["url"]
        path = _write_json(m)
        with pytest.raises(ImportValidationError):
            parse_module(path)

    def test_raises_on_version_mismatch(self):
        # -M- URL but version != "current"
        m = _minimal_module()
        m["__version"] = "4.0"
        path = _write_json(m)
        with pytest.raises(ImportValidationError):
            parse_module(path)

    def test_baseline_url_version_match(self):
        m = _minimal_module(
            url="doors://host:9601/prefix-B-000969a2-4.0",
            __version="4.0",
        )
        path = _write_json(m)
        result = parse_module(path)
        assert result.module["__version"] == "4.0"
