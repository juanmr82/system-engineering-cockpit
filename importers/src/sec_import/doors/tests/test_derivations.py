import pytest
from sec_import.doors.derivations import (
    compute_table_sets,
    derive_labels,
    derive_name,
    derive_type_label,
    parent_number,
    sort_key,
    target_object_url,
    target_version,
)
from sec_import.doors.exceptions import MalformedUrlError

PREFIX = "doors://doors.company.corp:9601/?version=2&prodID=0&urn=urn:telelogic::1-0000000000000000"
MOD_ID = "000969a2"
CURRENT_MOD_URL = f"{PREFIX}-M-{MOD_ID}"
BASELINE_MOD_URL = f"{PREFIX}-B-{MOD_ID}-4.0"


class TestTargetObjectUrl:
    def test_current_module(self):
        result = target_object_url(CURRENT_MOD_URL, "95")
        assert result == f"{PREFIX}-O-95-{MOD_ID}"

    def test_baseline_module(self):
        result = target_object_url(BASELINE_MOD_URL, "95")
        assert result == f"{PREFIX}-V-95-{MOD_ID}-4.0"

    def test_strips_whitespace(self):
        result = target_object_url(f"  {CURRENT_MOD_URL}  ", "1")
        assert "-O-" in result

    def test_version_id_with_dashes(self):
        url = f"{PREFIX}-B-{MOD_ID}-1.0-draft"
        result = target_object_url(url, "10")
        assert result == f"{PREFIX}-V-10-{MOD_ID}-1.0-draft"

    def test_malformed_raises(self):
        with pytest.raises(MalformedUrlError):
            target_object_url("not-a-doors-url", "1")


class TestTargetVersion:
    def test_current(self):
        assert target_version(CURRENT_MOD_URL) == "current"

    def test_baseline(self):
        assert target_version(BASELINE_MOD_URL) == "4.0"

    def test_baseline_version_with_dashes(self):
        url = f"{PREFIX}-B-{MOD_ID}-1.0-draft"
        assert target_version(url) == "1.0-draft"

    def test_malformed_raises(self):
        with pytest.raises(MalformedUrlError):
            target_version("garbage")


class TestParentNumber:
    def test_root_returns_none(self):
        assert parent_number("1") is None
        assert parent_number("10") is None

    def test_level_2(self):
        assert parent_number("1.2") == "1"

    def test_level_3(self):
        assert parent_number("1.2.3") == "1.2"

    def test_non_heading_segments(self):
        # 7.2.0-4.0-1.0-1 -> parent is 7.2.0-4.0-1
        assert parent_number("7.2.0-4.0-1.0-1") == "7.2.0-4.0-1"
        # Ambiguous prefix case -- ensure segment splitting, not prefix matching
        assert parent_number("7.2.0-4.0-1.0-10.0-1") == "7.2.0-4.0-1.0-10"

    def test_no_prefix_confusion(self):
        # Under naive startswith/prefix matching, 7.2.0-4.0-1.0-10.0-1 might wrongly
        # match as a child of 7.2.0-4.0-1.0-1 because 7.2.0-4.0-1.0-10 starts with
        # 7.2.0-4.0-1.0-1.  Dot-segment splitting eliminates this ambiguity:
        # a child of 7.2.0-4.0-1.0-10 has parent 7.2.0-4.0-1.0-10 (not 7.2.0-4.0-1.0-1)
        assert parent_number("7.2.0-4.0-1.0-10.0-1") == "7.2.0-4.0-1.0-10"
        assert parent_number("7.2.0-4.0-1.0-1.0-1") == "7.2.0-4.0-1.0-1"
        # These two parents are different -- dot-split correctly discriminates them
        assert parent_number("7.2.0-4.0-1.0-10.0-1") != parent_number("7.2.0-4.0-1.0-1.0-1")


class TestSortKey:
    def test_simple(self):
        assert sort_key("1") == "000001"

    def test_two_segments(self):
        assert sort_key("1.2") == "000001.000002"

    def test_non_heading(self):
        assert sort_key("7.2.0-4") == "000007.000002.000000-000004"

    def test_document_order(self):
        nums = ["10", "9", "2.1", "2.10", "2.2"]
        sorted_nums = sorted(nums, key=sort_key)
        assert sorted_nums == ["2.1", "2.2", "2.10", "9", "10"]

    def test_from_spec(self):
        assert sort_key("7.2.0-4") == "000007.000002.000000-000004"


class TestDeriveTypeLabel:
    def test_known_types(self):
        assert derive_type_label("Heading") == ("DOORSHeading", False)
        assert derive_type_label("Requirement") == ("DOORSRequirement", False)
        assert derive_type_label("Information") == ("DOORSInformation", False)
        assert derive_type_label("AppMatrix") == ("DOORSAppMatrix", False)
        assert derive_type_label("AppMatrixHeading") == ("DOORSAppMatrixHeading", False)
        assert derive_type_label("TBD") == ("DOORSTBD", False)

    def test_empty_is_tbd(self):
        assert derive_type_label("") == ("DOORSTBD", False)

    def test_unknown_is_tbd_and_flagged(self):
        lbl, is_unknown = derive_type_label("SomethingWeird")
        assert lbl == "DOORSTBD"
        assert is_unknown is True


class TestDeriveName:
    def test_heading_uses_object_heading(self):
        obj = {"Object Type": "Heading", "Object Heading": "My Heading", "Object Short Text": "ignored"}
        assert derive_name(obj) == "My Heading"

    def test_non_heading_uses_short_text(self):
        obj = {"Object Type": "Requirement", "Object Short Text": "Short", "Object Heading": "ignored"}
        assert derive_name(obj) == "Short"

    def test_fallback_to_object_text(self):
        obj = {"Object Type": "Requirement", "Object Short Text": "", "Object Text": "Long text"}
        assert derive_name(obj) == "Long text"

    def test_object_text_truncation(self):
        obj = {"Object Type": "Requirement", "Object Short Text": "", "Object Text": "x" * 150}
        name = derive_name(obj)
        # 120 content chars + 1 Unicode ellipsis character (U+2026)
        assert len(name) == 121
        assert name.endswith("…")

    def test_object_text_truncation_ellipsis(self):
        obj = {"Object Type": "Requirement", "Object Short Text": "", "Object Text": "x" * 150}
        name = derive_name(obj)
        # The ellipsis is the Unicode character U+2026; len("...") = 3 but len("...") = 1
        # Accept either the unicode ellipsis (len=121) or ascii "..." (len=123)
        assert len(name) in (121, 123)
        assert name[:120] == "x" * 120

    def test_fallback_to_id(self):
        obj = {"Object Type": "Requirement", "Object Short Text": "", "Object Text": "", "id": "SRD-1"}
        assert derive_name(obj) == "SRD-1"

    def test_empty_object_type_uses_short_text(self):
        obj = {"Object Type": "", "Object Short Text": "ST", "id": "X"}
        assert derive_name(obj) == "ST"

    def test_name_never_empty(self):
        obj = {"id": "SRD-999"}
        name = derive_name(obj)
        assert name and name != ""


class TestComputeTableSets:
    def _make_obj(self, oid, num, table_obj="false", table_id=""):
        return {
            "id": oid,
            "objectNumber": num,
            "__tableObject": table_obj,
            "__tableID": table_id,
        }

    def test_no_tables(self):
        objects = [self._make_obj("A", "1"), self._make_obj("B", "1.1")]
        tids, rids = compute_table_sets(objects)
        assert tids == set()
        assert rids == set()

    def test_identifies_table_and_cell(self):
        objects = [
            self._make_obj("T1", "2"),                          # table
            self._make_obj("R1", "2.1"),                        # row
            self._make_obj("C1", "2.1.1", "true", "T1"),       # cell
        ]
        tids, rids = compute_table_sets(objects)
        assert "T1" in tids
        assert "R1" in rids

    def test_table_row_must_have_cell_child(self):
        # T1 is a table (because a cell references it via __tableID).
        # R1 is a child of T1 (objectNumber 2.1 under table at 2).
        # But R1 has no cell children of its own, so it must NOT be classified DOORSTableRow.
        # The cell (C1) is under a sibling path, not under R1.
        objects = [
            self._make_obj("T1", "2"),
            self._make_obj("R1", "2.1"),                      # child of table, no cell children
            self._make_obj("C1", "2.2", "true", "T1"),        # cell referencing T1 but NOT under R1
        ]
        tids, rids = compute_table_sets(objects)
        assert "T1" in tids        # T1 is a table (C1 references it)
        assert "R1" not in rids    # R1 has no cell children -> not a row


class TestDeriveLabels:
    def test_basic_requirement(self):
        obj = {"Object Type": "Requirement", "id": "X", "__tableObject": "false"}
        labels = derive_labels(obj, set(), set())
        assert "SEItem" in labels
        assert "DOORSObject" in labels
        assert "DOORSRequirement" in labels

    def test_cell_gets_cell_label(self):
        obj = {"Object Type": "TBD", "id": "C1", "__tableObject": "true"}
        labels = derive_labels(obj, set(), set())
        assert "DOORSTableCell" in labels

    def test_table_gets_table_label(self):
        obj = {"Object Type": "TBD", "id": "T1", "__tableObject": "false"}
        labels = derive_labels(obj, {"T1"}, set())
        assert "DOORSTable" in labels

    def test_row_gets_row_label(self):
        obj = {"Object Type": "TBD", "id": "R1", "__tableObject": "false"}
        labels = derive_labels(obj, set(), {"R1"})
        assert "DOORSTableRow" in labels

    def test_empty_type_gets_tbd(self):
        obj = {"Object Type": "", "id": "X", "__tableObject": "false"}
        labels = derive_labels(obj, set(), set())
        assert "DOORSTBD" in labels
