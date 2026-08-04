from sec_import.core.identity import derive_id


def test_derive_id_namespaces_by_source() -> None:
    assert derive_id("doors", "12345") == "doors:12345"


def test_derive_id_is_deterministic() -> None:
    assert derive_id("windchill", "WC-1") == derive_id("windchill", "WC-1")
