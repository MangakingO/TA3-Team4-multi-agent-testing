"""Unit tests of the deterministic tools (no Maven, no model)."""
from __future__ import annotations

import textwrap

from agent_system import prompts
from agent_system.llm import extract_json, parse_model
from agent_system.schemas import RepairPlan, TestSuiteDraft
from agent_system.tools import dedup, jacoco, javasrc, junit, maven, refactor_parse


def _case(cid, junit_code, method="pull()", inp="pull once", expected="LOW", tech="EP"):
    return {"id": cid, "technique": tech, "method": method, "condition": "c", "input": inp,
            "expected": expected, "junit": junit_code}


def test_dedup_same_code_different_variable_names():
    a = _case("EP-01", "FanSwitch fan = new FanSwitch();\nfan.pull();\nassertEquals(\"LOW\", fan.getSetting());")
    b = _case("BVA-01", "FanSwitch f = new FanSwitch(); // one pull\n f.pull(); assertEquals(\"LOW\", f.getSetting());",
              inp="one event", tech="BVA")
    suite, report = dedup.curate([a], [b])
    assert [c["id"] for c in suite] == ["EP-01"]
    assert suite[0]["also"] == ["BVA-01"] and suite[0]["technique"] == "EP+BVA"
    assert report["duplicates"][0]["rule"] == "identical test code"


def test_dedup_same_input_and_expected_is_duplicate_but_different_expected_is_conflict():
    a = _case("EP-01", "x();", inp="setSpeed(1)", expected="LOW")
    b = _case("BVA-01", "y();", inp="setSpeed(1)", expected="low", tech="BVA")
    c = _case("BVA-02", "z();", inp="setSpeed(1)", expected="HIGH", tech="BVA")
    suite, report = dedup.curate([a], [b, c])
    assert [x["id"] for x in suite] == ["EP-01", "BVA-02"]
    assert report["duplicates"][0]["rule"] == "same input and expected result"
    assert report["conflicts"][0]["new"] == "BVA-02"


def test_dedup_renames_colliding_ids():
    suite, report = dedup.merge([_case("COV-01", "a();")], [_case("COV-01", "b();", inp="other")], "COVERAGE")
    assert [c["id"] for c in suite] == ["COV-01", "COV-01-2"]


def test_junit_render_strips_method_wrappers_and_maps_lines():
    case = _case("EP-07", "@Test\nvoid t() {\n    assertTrue(true);\n}")
    source, line_map = junit.render("Fan", "", [case], 5)
    assert "void tc_EP_07() throws Exception {" in source
    assert "@Test\n    @DisplayName" in source and source.count("@Test") == 1
    assert "@Timeout(5)" in source
    first, last, test_id = line_map[0]
    assert test_id == "EP-07" and junit.blame(line_map, first + 3) == "EP-07"


def test_extract_json_tolerates_fences_and_bare_lists():
    raw = "Here you go:\n```json\n[{\"id\": \"EP-01\", \"junit\": \"a();\"}]\n```"
    draft = parse_model(raw, TestSuiteDraft)
    assert draft.test_cases[0].id == "EP-01" and draft.test_cases[0].technique == ""
    assert extract_json('noise {"a": 1} noise') == {"a": 1}
    plan = parse_model('{"plan": {"diagnosis": "x", "test_edits": [], "code_edits": []}}', RepairPlan)
    assert plan.diagnosis == "x"


def test_refactor_reply_parsing():
    reply = textwrap.dedent('''\
        Design: two states.

        ```java
        // File: State.java
        interface State { void go(Ctx c); }
        ```
        **Ctx.java**
        ```java
        public class Ctx { State s; }
        ```
        ```java
        class A implements State { public void go(Ctx c) {} }
        ```
        ''')
    files = refactor_parse.parse_files(reply)
    assert sorted(files) == ["A.java", "Ctx.java", "State.java"]
    assert files["State.java"].startswith("interface State")
    assert "Design: two states." in refactor_parse.design_notes(reply)


def test_javasrc_facts():
    src = "// package nope;\npackage edu.uta;\n/** class Fake */\npublic final class Season { public static void main(String[] a) {} }"
    assert javasrc.package_of(src) == "edu.uta"
    assert javasrc.primary_type(src) == "Season"
    assert javasrc.has_main(src)
    assert javasrc.numbered("a\nb", {2: "note"}) == "1 | a\n2 | b    // <-- note"


def test_compile_error_parsing():
    out = ("[ERROR] COMPILATION ERROR :\n"
           "[ERROR] /p/src/test/java/FanTest.java:[14,9] cannot find symbol\n"
           "[ERROR]   symbol:   method spin()\n"
           "[ERROR]   location: class FanSwitch\n")
    errors = maven.parse_compile_errors(out)
    assert errors == [{"file": "FanTest.java", "scope": "test", "line": 14,
                       "message": "cannot find symbol; symbol:   method spin(); location: class FanSwitch"}]


def test_jacoco_summary(tmp_path):
    xml = tmp_path / "jacoco.xml"
    xml.write_text('<report name="x"><package name="">'
                   '<class name="Fan" sourcefilename="Fan.java"><method name="pull" desc="()V" line="3">'
                   '<counter type="BRANCH" missed="1" covered="1"/><counter type="INSTRUCTION" missed="2" covered="5"/>'
                   '</method><counter type="BRANCH" missed="1" covered="1"/><counter type="LINE" missed="0" covered="4"/>'
                   '</class><sourcefile name="Fan.java"><line nr="4" mi="0" ci="3" mb="1" cb="1"/></sourcefile>'
                   '</package><counter type="BRANCH" missed="1" covered="1"/><counter type="LINE" missed="0" covered="4"/>'
                   '</report>')
    summary = jacoco.summarize(jacoco.parse(xml))
    assert summary["branch"] == {"covered": 1, "total": 2, "pct": 50.0}
    assert summary["missed_branches"] == [{"file": "Fan.java", "line": 4, "missed": 1, "covered": 1}]
    assert jacoco.marks_for_file(summary, "Fan.java") == {4: "1 of 2 branches never taken"}
    assert "Branch coverage: 1/2 (50.0%)" in jacoco.to_markdown(summary, "t")


def test_final_prompt_is_p3():
    final = prompts.load("refactor_state_pattern")
    p3 = prompts.PromptTemplate(prompts.PROMPTS_DIR / "refactor_versions" / "P3.md")
    assert (final.system, final.user) == (p3.system, p3.user)
    for version in ("P0", "P1", "P2", "P3"):
        t = prompts.PromptTemplate(prompts.PROMPTS_DIR / "refactor_versions" / f"{version}.md")
        assert "$source" in t.user


# ---------------------------------------------------------------- SeasonSwitch-specific analysis
import shutil
import subprocess

import pytest

from agent_system.tools import runjava, testability

SEASON = __import__("pathlib").Path(__file__).parent / "fixtures" / "SeasonSwitch.java"
needs_jdk = pytest.mark.skipif(shutil.which("javac") is None, reason="needs a JDK")


@pytest.fixture(scope="module")
def season_classes(tmp_path_factory):
    out = tmp_path_factory.mktemp("classes")
    subprocess.run(["javac", "-d", str(out), str(SEASON)], check=True, capture_output=True)
    return out


@needs_jdk
def test_testability_finds_what_makes_season_switch_hard_to_test(season_classes):
    t = testability.analyze(SEASON.read_text(), season_classes, "SeasonSwitch", "cse4392")
    assert t.static_fields == ["SeasonSwitch.state"]
    assert t.timers == ["SeasonSwitch.timer", "SeasonSwitch.Room.timer"]
    assert t.prints and t.exits and t.has_main and t.blocks == ["await()"]
    assert "AC ac" in t.members["SeasonSwitch"] and "static final int COOL = 1" in t.members["SeasonSwitch"]
    assert t.members["SeasonSwitch.Room"] == ["public int temp", "javax.swing.Timer timer",
                                              "public void actionPerformed(ActionEvent)"]
    notes = t.notes()
    assert "`s.room.actionPerformed(null)`" in notes and "TestSupport.runMain(SeasonSwitch.class" in notes
    assert testability.Testability.from_dict(t.as_dict()).notes() == notes


@needs_jdk
def test_observation_stops_a_program_that_never_ends(season_classes):
    runs = [runjava.run(season_classes, "cse4392.SeasonSwitch", args, 2.5) for args in ([], ["off"], ["warm"])]
    assert runs[0]["stopped"] and "Cooling started. RmTemp=70" in runs[0]["out"]
    assert runs[1]["exit_code"] == 0 and runs[1]["out"].endswith("System off. System exits.")
    assert runs[2]["exit_code"] == 1 and "Unknown command" in runs[2]["out"]
    text = runjava.describe(runs, "cse4392.SeasonSwitch", 2.5)
    assert "$ java SeasonSwitch (no arguments)   -> still running after 2.5 s, stopped" in text
    assert "$ java SeasonSwitch warm   -> exit code 1" in text


def test_track_new_wraps_every_construction_once():
    code = ("SeasonSwitch s = new SeasonSwitch();\nObject o = TestSupport.track(new SeasonSwitch());\n"
            "List<SeasonSwitch> l = List.of(new SeasonSwitch(), new SeasonSwitch() { });\nnew SeasonSwitchX();")
    out = junit.track_new(code, "SeasonSwitch")
    assert out.count("TestSupport.track(new SeasonSwitch())") == 3
    assert "TestSupport.track(TestSupport.track(" not in out
    assert "new SeasonSwitch() { }" in out and "new SeasonSwitchX();" in out


def test_rendered_class_has_fixture_and_helper(tmp_path):
    from agent_system.tools import workspace
    source, _ = junit.render("SeasonSwitch", "cse4392", [_case("EP-01", "SeasonSwitch s = new SeasonSwitch();")], 10)
    assert "TestSupport.restoreStatics(SeasonSwitch.class);" in source and "TestSupport.cleanUp();" in source
    path = workspace.write_test_class(tmp_path, "cse4392", "SeasonSwitch", source)
    helper = (path.parent / "TestSupport.java").read_text()
    assert helper.splitlines()[3] == "package cse4392;" and "static MainRun runMain(" in helper


def test_dedup_keeps_tests_that_differ_only_in_string_data():
    a = _case("EP-28", 'TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "cool");',
              method="main(String[])", inp="java SeasonSwitch cool")
    b = _case("EP-29", 'TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "heat");',
              method="main(String[])", inp="java SeasonSwitch heat")
    c = _case("BVA-17", 'TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "");',
              method="main(String[])", inp='java SeasonSwitch ""', expected="exit 1", tech="BVA")
    d = _case("BVA-15", 'TestSupport.MainRun run = TestSupport.runMain(SeasonSwitch.class, 3000);',
              method="main(String[])", inp="java SeasonSwitch", tech="BVA")
    e = _case("EP-27", "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000); // no args",
              method="main(String[])", inp="java SeasonSwitch")
    suite, report = dedup.curate([a, b, e], [c, d])
    assert [x["id"] for x in suite] == ["EP-28", "EP-29", "EP-27", "BVA-17"]
    assert report["duplicates"] == [{"kept": "EP-27", "removed": "BVA-15", "rule": "identical test code"}]
    assert report["conflicts"] == []
