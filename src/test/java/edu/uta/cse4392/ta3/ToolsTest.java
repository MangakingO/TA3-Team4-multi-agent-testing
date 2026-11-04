package edu.uta.cse4392.ta3;

import edu.uta.cse4392.ta3.nodes.Common;
import edu.uta.cse4392.ta3.tools.Dedup;
import edu.uta.cse4392.ta3.tools.Jacoco;
import edu.uta.cse4392.ta3.tools.JUnitWriter;
import edu.uta.cse4392.ta3.tools.JavaSrc;
import edu.uta.cse4392.ta3.tools.Maven;
import edu.uta.cse4392.ta3.tools.RefactorParse;
import edu.uta.cse4392.ta3.tools.RunJava;
import edu.uta.cse4392.ta3.tools.Testability;
import edu.uta.cse4392.ta3.tools.UnifiedDiff;
import edu.uta.cse4392.ta3.tools.Workspace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests of the deterministic tools (no model; javac and java only for the SeasonSwitch analysis). */
class ToolsTest {

    @TempDir
    static Path classes;

    private static Map<String, Object> c(String id, String junit) {
        return c(id, junit, "pull()", "pull once", "LOW", "EP");
    }

    private static Map<String, Object> c(String id, String junit, String method, String input, String expected,
                                         String technique) {
        return Fakes.testCase(id, technique, method, "c", input, expected, junit);
    }

    private static List<Object> ids(List<Map<String, Object>> suite) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> x : suite) {
            out.add(x.get("id"));
        }
        return out;
    }

    @Test
    void dedupSameCodeDifferentVariableNames() {
        var a = c("EP-01", "FanSwitch fan = new FanSwitch();\nfan.pull();\nassertEquals(\"LOW\", fan.getSetting());");
        var b = c("BVA-01", "FanSwitch f = new FanSwitch(); // one pull\n f.pull(); assertEquals(\"LOW\", f.getSetting());",
                "pull()", "one event", "LOW", "BVA");
        Dedup.Merged m = Dedup.curate(List.of(a), List.of(b));
        assertEquals(List.of("EP-01"), ids(m.suite()));
        assertEquals(List.of("BVA-01"), m.suite().get(0).get("also"));
        assertEquals("EP+BVA", m.suite().get(0).get("technique"));
        assertEquals("identical test code", ((Map<?, ?>) ((List<?>) m.report().get("duplicates")).get(0)).get("rule"));
    }

    @Test
    void dedupSameInputAndExpectedIsDuplicateButDifferentExpectedIsConflict() {
        var a = c("EP-01", "x();", "pull()", "setSpeed(1)", "LOW", "EP");
        var b = c("BVA-01", "y();", "pull()", "setSpeed(1)", "low", "BVA");
        var d = c("BVA-02", "z();", "pull()", "setSpeed(1)", "HIGH", "BVA");
        Dedup.Merged m = Dedup.curate(List.of(a), List.of(b, d));
        assertEquals(List.of("EP-01", "BVA-02"), ids(m.suite()));
        assertEquals("same input and expected result",
                ((Map<?, ?>) ((List<?>) m.report().get("duplicates")).get(0)).get("rule"));
        assertEquals("BVA-02", ((Map<?, ?>) ((List<?>) m.report().get("conflicts")).get(0)).get("new"));
    }

    @Test
    void dedupRenamesCollidingIds() {
        Dedup.Merged m = Dedup.merge(List.of(c("COV-01", "a();")),
                List.of(c("COV-01", "b();", "pull()", "other", "LOW", "COVERAGE")), "COVERAGE");
        assertEquals(List.of("COV-01", "COV-01-2"), ids(m.suite()));
    }

    @Test
    void dedupKeepsTestsThatDifferOnlyInStringData() {
        var a = c("EP-28", "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"cool\");",
                "main(String[])", "java SeasonSwitch cool", "LOW", "EP");
        var b = c("EP-29", "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"heat\");",
                "main(String[])", "java SeasonSwitch heat", "LOW", "EP");
        var d = c("BVA-17", "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"\");",
                "main(String[])", "java SeasonSwitch \"\"", "exit 1", "BVA");
        var e = c("BVA-15", "TestSupport.MainRun run = TestSupport.runMain(SeasonSwitch.class, 3000);",
                "main(String[])", "java SeasonSwitch", "LOW", "BVA");
        var f = c("EP-27", "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000); // no args",
                "main(String[])", "java SeasonSwitch", "LOW", "EP");
        Dedup.Merged m = Dedup.curate(List.of(a, b, f), List.of(d, e));
        assertEquals(List.of("EP-28", "EP-29", "EP-27", "BVA-17"), ids(m.suite()));
        assertEquals(List.of(Map.of("kept", "EP-27", "removed", "BVA-15", "rule", "identical test code")),
                m.report().get("duplicates"));
        assertEquals(List.of(), m.report().get("conflicts"));
    }

    @Test
    void junitRenderStripsMethodWrappersAndMapsLines() {
        var r = JUnitWriter.render("Fan", "", List.of(c("EP-07", "@Test\nvoid t() {\n    assertTrue(true);\n}")), 5);
        assertTrue(r.source().contains("void tc_EP_07() throws Exception {"));
        assertTrue(r.source().contains("@Test\n    @DisplayName"));
        assertEquals(1, r.source().split("@Test", -1).length - 1);
        assertTrue(r.source().contains("@Timeout(5)"));
        JUnitWriter.LineRange range = r.lineMap().get(0);
        assertEquals("EP-07", range.id());
        assertEquals("EP-07", JUnitWriter.blame(r.lineMap(), range.first() + 3));
    }

    @Test
    void extractJsonToleratesFencesAndBareLists() {
        String raw = "Here you go:\n```json\n[{\"id\": \"EP-01\", \"junit\": \"a();\"}]\n```";
        Schemas.TestSuiteDraft draft = Llm.parse(raw, Schemas.TEST_SUITE_DRAFT);
        assertEquals("EP-01", draft.testCases().get(0).id());
        assertEquals("", draft.testCases().get(0).technique());
        assertEquals(Map.of("a", 1), Llm.extractJson("noise {\"a\": 1} noise"));
        Schemas.RepairPlan plan = Llm.parse("{\"plan\": {\"diagnosis\": \"x\", \"test_edits\": [], \"code_edits\": []}}",
                Schemas.REPAIR_PLAN);
        assertEquals("x", plan.diagnosis());
        // raw line breaks inside strings, as small models write them
        assertEquals("a\nb", ((Map<?, ?>) Llm.extractJson("{\"junit\": \"a\nb\"}")).get("junit"));
        // a test edit must say modify or delete
        assertThrows(IllegalArgumentException.class, () -> Llm.parse(
                "{\"diagnosis\": \"d\", \"test_edits\": [{\"id\": \"EP-01\", \"action\": \"rewrite\", \"reason\": \"r\"}]}",
                Schemas.REPAIR_PLAN));
    }

    @Test
    void refactorReplyParsing() {
        String reply = """
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
                """;
        Map<String, String> files = RefactorParse.parseFiles(reply);
        assertEquals(List.of("A.java", "Ctx.java", "State.java"), files.keySet().stream().sorted().toList());
        assertTrue(files.get("State.java").startsWith("interface State"));
        assertTrue(RefactorParse.designNotes(reply).contains("Design: two states."));
    }

    @Test
    void javaSourceFacts() {
        String src = "// package nope;\npackage edu.uta;\n/** class Fake */\npublic final class Season { "
                + "public static void main(String[] a) {} }";
        assertEquals("edu.uta", JavaSrc.packageOf(src));
        assertEquals("Season", JavaSrc.primaryType(src));
        assertTrue(JavaSrc.hasMain(src));
        assertEquals("1 | a\n2 | b    // <-- note", JavaSrc.numbered("a\nb", Map.of(2, "note")));
    }

    @Test
    void compileErrorParsing() {
        String out = "[ERROR] COMPILATION ERROR :\n"
                + "[ERROR] /p/src/test/java/FanTest.java:[14,9] cannot find symbol\n"
                + "[ERROR]   symbol:   method spin()\n"
                + "[ERROR]   location: class FanSwitch\n";
        assertEquals(List.of(Map.of("file", "FanTest.java", "scope", "test", "line", 14,
                        "message", "cannot find symbol; symbol:   method spin(); location: class FanSwitch")),
                Maven.parseCompileErrors(out));
    }

    @Test
    void jacocoSummary(@TempDir Path tmp) throws Exception {
        Path xml = tmp.resolve("jacoco.xml");
        Files.writeString(xml, "<?xml version=\"1.0\"?><!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" "
                + "\"report.dtd\"><report name=\"x\"><package name=\"\">"
                + "<class name=\"Fan\" sourcefilename=\"Fan.java\"><method name=\"pull\" desc=\"()V\" line=\"3\">"
                + "<counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/><counter type=\"INSTRUCTION\" missed=\"2\" covered=\"5\"/>"
                + "</method><counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/><counter type=\"LINE\" missed=\"0\" covered=\"4\"/>"
                + "</class><sourcefile name=\"Fan.java\"><line nr=\"4\" mi=\"0\" ci=\"3\" mb=\"1\" cb=\"1\"/></sourcefile>"
                + "</package><counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/><counter type=\"LINE\" missed=\"0\" covered=\"4\"/>"
                + "</report>");
        Map<String, Object> summary = Jacoco.summarize(Jacoco.parse(xml));
        assertEquals(Map.of("covered", 1, "total", 2, "pct", 50.0), summary.get("branch"));
        assertEquals(List.of(Map.of("file", "Fan.java", "line", 4, "missed", 1, "covered", 1)),
                summary.get("missed_branches"));
        assertEquals(Map.of(4, "1 of 2 branches never taken"), Jacoco.marksForFile(summary, "Fan.java"));
        assertTrue(Jacoco.toMarkdown(summary, "t").contains("Branch coverage: 1/2 (50.0%)"));
    }

    @Test
    void finalPromptIsP3() {
        Path dir = Settings.fromEnv().promptsDir;
        Prompts.Template finalPrompt = Prompts.load("refactor_state_pattern", dir);
        Prompts.Template p3 = new Prompts.Template(dir.resolve("refactor_versions").resolve("P3.md"));
        assertEquals(p3.system, finalPrompt.system);
        assertEquals(p3.user, finalPrompt.user);
        for (String version : List.of("P0", "P1", "P2", "P3")) {
            assertTrue(new Prompts.Template(dir.resolve("refactor_versions").resolve(version + ".md")).user
                    .contains("$source"));
        }
    }

    @Test
    void promptPlaceholdersLikePythonTemplates() {
        assertEquals("a X ${b} $$ c", Prompts.substitute("a $x $${b} $$$$ ${c}", Map.of("x", "X", "c", "c")));
        assertEquals("cost: $5 and {json}", Prompts.substitute("cost: $$5 and {json}", Map.of()));
        assertEquals("keep $unknown", Prompts.substitute("keep $unknown", Map.of("other", 1)));
    }

    @Test
    void unifiedDiffLikeDifflib() {
        String diff = UnifiedDiff.diff("a\nb\nc\n", "a\nB\nc\nd\n", "a/x", "b/x");
        assertEquals("--- a/x\n+++ b/x\n@@ -1,3 +1,4 @@\n a\n-b\n+B\n c\n+d", diff);
        assertEquals("--- a/x\n+++ b/x\n@@ -0,0 +1,2 @@\n+one\n+two", UnifiedDiff.diff("", "one\ntwo", "a/x", "b/x"));
        assertEquals("", UnifiedDiff.diff("same\n", "same\n", "a/x", "b/x"));
        // far-apart changes become two hunks
        StringBuilder before = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            before.append("line ").append(i).append('\n');
        }
        String after = before.toString().replace("line 2\n", "line two\n").replace("line 19\n", "line nineteen\n");
        assertEquals(String.join("\n", "--- a/x", "+++ b/x", "@@ -1,5 +1,5 @@", " line 1", "-line 2", "+line two",
                        " line 3", " line 4", " line 5", "@@ -16,5 +16,5 @@", " line 16", " line 17", " line 18",
                        "-line 19", "+line nineteen", " line 20"),
                UnifiedDiff.diff(before.toString(), after, "a/x", "b/x"));
    }

    @Test
    void answersInWordsOrAsChoices() {
        Map<String, List<String>> actions = new java.util.LinkedHashMap<>();
        actions.put("accept", List.of("a", "accept"));
        actions.put("revise", List.of("r", "revise"));
        assertEquals(List.of("revise", "add a test for null"),
                List.of(Common.parseAnswer("revise: add a test for null", actions)));
        assertEquals(List.of("revise", "Add tests: x"), List.of(Common.parseAnswer("r\nAdd tests: x", actions)));
        assertEquals(List.of("accept", ""), List.of(Common.parseAnswer("accept", actions)));
        assertEquals(List.of("accept", ""), List.of(Common.parseAnswer("whatever", actions)));
        assertEquals(List.of("revise", "f"), List.of(Common.parseAnswer(Map.of("action", "revise", "feedback", "f"),
                actions)));
    }

    @Test
    void trackNewWrapsEveryConstructionOnce() {
        String code = "SeasonSwitch s = new SeasonSwitch();\nObject o = TestSupport.track(new SeasonSwitch());\n"
                + "List<SeasonSwitch> l = List.of(new SeasonSwitch(), new SeasonSwitch() { });\nnew SeasonSwitchX();";
        String out = JUnitWriter.trackNew(code, "SeasonSwitch");
        assertEquals(3, out.split("TestSupport\\.track\\(new SeasonSwitch\\(\\)\\)", -1).length - 1);
        assertFalse(out.contains("TestSupport.track(TestSupport.track("));
        assertTrue(out.contains("new SeasonSwitch() { }") && out.contains("new SeasonSwitchX();"));
    }

    @Test
    void renderedClassHasFixtureAndHelper(@TempDir Path tmp) {
        String source = JUnitWriter.render("SeasonSwitch", "cse4392",
                List.of(c("EP-01", "SeasonSwitch s = new SeasonSwitch();")), 10).source();
        assertTrue(source.contains("TestSupport.restoreStatics(SeasonSwitch.class);"));
        assertTrue(source.contains("TestSupport.cleanUp();"));
        Path path = Workspace.writeTestClass(tmp, "cse4392", "SeasonSwitch", source);
        List<String> helper = Text.lines(Text.read(path.resolveSibling("TestSupport.java")));
        assertEquals("package cse4392;", helper.get(3));
        assertTrue(String.join("\n", helper).contains("static MainRun runMain("));
    }

    // ---------------------------------------------------------------- SeasonSwitch-specific analysis
    @BeforeAll
    static void compileSeasonSwitch() throws Exception {
        Path src = classes.resolve("SeasonSwitch.java");
        Files.writeString(src, Fakes.fixture("SeasonSwitch.java"));
        String javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
        Process p = new ProcessBuilder(javac, "-d", classes.toString(), src.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }

    @Test
    void testabilityFindsWhatMakesSeasonSwitchHardToTest() {
        Testability t = Testability.analyze(Fakes.fixture("SeasonSwitch.java"), classes, "SeasonSwitch", "cse4392");
        assertEquals(List.of("SeasonSwitch.state"), t.staticFields);
        assertEquals(List.of("SeasonSwitch.timer", "SeasonSwitch.Room.timer"), t.timers);
        assertTrue(t.prints && t.exits && t.hasMain);
        assertEquals(List.of("await()"), t.blocks);
        assertTrue(t.members.get("SeasonSwitch").contains("AC ac"));
        assertTrue(t.members.get("SeasonSwitch").contains("static final int COOL = 1"));
        assertEquals(List.of("public int temp", "javax.swing.Timer timer", "public void actionPerformed(ActionEvent)"),
                t.members.get("SeasonSwitch.Room"));
        String notes = t.notes();
        assertTrue(notes.contains("`s.room.actionPerformed(null)`"));
        assertTrue(notes.contains("TestSupport.runMain(SeasonSwitch.class"));
        assertEquals(notes, Testability.fromMap(Json.parseMap(Json.compact(t.asMap()))).notes());
    }

    @Test
    void observationStopsAProgramThatNeverEnds() {
        List<Map<String, Object>> runs = new ArrayList<>();
        for (List<String> args : List.of(List.<String>of(), List.of("off"), List.of("warm"))) {
            runs.add(RunJava.run(classes, "cse4392.SeasonSwitch", args, 2.5));
        }
        assertEquals(true, runs.get(0).get("stopped"));
        assertTrue(runs.get(0).get("out").toString().contains("Cooling started. RmTemp=70"));
        assertEquals(0, runs.get(1).get("exit_code"));
        assertTrue(runs.get(1).get("out").toString().endsWith("System off. System exits."));
        assertEquals(1, runs.get(2).get("exit_code"));
        assertTrue(runs.get(2).get("out").toString().contains("Unknown command"));
        String text = RunJava.describe(runs, "cse4392.SeasonSwitch", 2.5);
        assertTrue(text.contains("$ java SeasonSwitch (no arguments)   -> still running after 2.5 s, stopped"));
        assertTrue(text.contains("$ java SeasonSwitch warm   -> exit code 1"));
    }
}
