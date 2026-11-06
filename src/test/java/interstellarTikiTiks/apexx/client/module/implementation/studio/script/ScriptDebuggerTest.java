package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.Block;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.Handler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Snapshot breakpoints and the no-op behaviour of the HUD draw functions outside a render pass. */
class ScriptDebuggerTest {

    private static final class MockHost implements ScriptHost {
        Value[] settings = new Value[0];
        @Override public Value readSetting(int slot) { return settings[slot]; }
        @Override public void schedule(Block body, Value[] locals, double delayMs) { }
    }

    private record Run(ScriptCompiler.Resolved resolved, Evaluator evaluator, MockHost host) {
        boolean fire(String script, String event) {
            Handler h = resolved.handler(event);
            evaluator.setDebugContext(ScriptDebugger.session(script), event);
            try {
                return evaluator.runHandler(h.body, null, h.paramSlot);
            } finally {
                evaluator.setDebugContext(null, null);
            }
        }
    }

    private static Run load(String source, Value... settings) {
        ScriptCompiler.Resolved r = ScriptCompiler.parseAndResolve(source);
        MockHost host = new MockHost();
        host.settings = settings;
        return new Run(r, r.newEvaluator(host, HostApi.table()), host);
    }

    private static final String NAMED = """
            module "DbgA" {
              setting slider reach = 4 range 1..6
              var count = 0
              on tick {
                let a = 5
                let b = a * 2
                count = count + b
              }
            }
            """;

    @AfterEach
    void reset() {
        ScriptDebugger.setEnabled(false);
        for (String s : List.of("DbgA", "DbgB", "DbgC", "DbgD")) {
            ScriptDebugger.clearBreakpoints(s);
            ScriptDebugger.clearSnapshots(s);
        }
    }

    @Test
    void breakpointCapturesLocalsVarsAndSettings() {
        Run run = load(NAMED, Value.number(4));
        assertTrue(ScriptDebugger.toggleBreakpoint("DbgA", 7));
        ScriptDebugger.setEnabled(true);
        assertTrue(run.fire("DbgA", "tick"));

        List<ScriptDebugger.Snapshot> snaps = ScriptDebugger.snapshots("DbgA");
        assertEquals(1, snaps.size());
        ScriptDebugger.Snapshot s = snaps.get(0);
        assertEquals(7, s.line());
        assertEquals("tick", s.event());
        Map<String, String> locals = s.locals().stream().collect(Collectors.toMap(ScriptDebugger.Var::name, ScriptDebugger.Var::value));
        assertEquals(Map.of("a", "5", "b", "10"), locals);
        assertEquals(List.of(new ScriptDebugger.Var("count", "0")), s.vars()); // taken before line 7 ran
        assertEquals(List.of(new ScriptDebugger.Var("reach", "4")), s.settings());
        // The script itself still ran normally.
        assertEquals(10.0, run.evaluator().getModuleVar(0).asNumber());
    }

    @Test
    void noSnapshotsWhenDebuggingIsOffOrNoBreakpoint() {
        Run run = load(NAMED, Value.number(4));
        ScriptDebugger.toggleBreakpoint("DbgA", 7);
        assertNull(ScriptDebugger.session("DbgA")); // debugging off
        run.fire("DbgA", "tick");
        assertTrue(ScriptDebugger.snapshots("DbgA").isEmpty());

        ScriptDebugger.setEnabled(true);
        assertFalse(ScriptDebugger.toggleBreakpoint("DbgA", 7)); // removed again
        assertNull(ScriptDebugger.session("DbgA"));
        run.fire("DbgA", "tick");
        assertTrue(ScriptDebugger.snapshots("DbgA").isEmpty());
    }

    @Test
    void capturesAreCappedPerRunAndTheBufferIsBounded() {
        Run run = load("""
                module "DbgB" {
                  var n = 0
                  on tick {
                    for i in 0..100 {
                      n = n + i
                    }
                  }
                }
                """);
        ScriptDebugger.toggleBreakpoint("DbgB", 5);
        ScriptDebugger.setEnabled(true);
        assertTrue(run.fire("DbgB", "tick"));
        List<ScriptDebugger.Snapshot> first = ScriptDebugger.snapshots("DbgB");
        assertEquals(ScriptDebugger.PER_RUN_CAP, first.size());
        assertEquals("i", first.get(3).locals().get(0).name());
        assertEquals("3", first.get(3).locals().get(0).value());
        assertEquals(4950.0, run.evaluator().getModuleVar(0).asNumber()); // all 100 iterations still ran

        for (int k = 0; k < 20; k++) run.fire("DbgB", "tick");
        assertEquals(ScriptDebugger.CAPACITY, ScriptDebugger.snapshots("DbgB").size());
    }

    @Test
    void scopedLocalsOnlyShowWhatIsVisible() {
        Run run = load("""
                module "DbgC" {
                  on tick {
                    if true {
                      let inner = 1
                    }
                    let outer = 2
                    outer = outer + 1
                  }
                }
                """);
        ScriptDebugger.toggleBreakpoint("DbgC", 7);
        ScriptDebugger.setEnabled(true);
        run.fire("DbgC", "tick");
        List<ScriptDebugger.Snapshot> snaps = ScriptDebugger.snapshots("DbgC");
        assertEquals(1, snaps.size());
        assertEquals(List.of(new ScriptDebugger.Var("outer", "2")), snaps.get(0).locals());
    }

    @Test
    void formattingIsBounded() {
        List<Value> big = new ArrayList<>();
        for (int i = 0; i < 4000; i++) big.add(Value.string("item number " + i));
        String s = ScriptDebugger.format(Value.list(big));
        assertTrue(s.length() <= ScriptDebugger.MAX_VALUE_CHARS, "too long: " + s.length());
        assertEquals("\"hi\"", ScriptDebugger.format(Value.string("hi")));
        assertEquals("null", ScriptDebugger.format(Value.NULL));
    }

    @Test
    void drawFunctionsAreNoOpsOutsideRender() {
        Value[] text = {Value.string("hi"), Value.number(1), Value.number(2), Value.number(0xFFFFFF), Value.TRUE};
        Value[] rect = {Value.number(1), Value.number(2), Value.number(30), Value.number(40), Value.string("#FF8800")};
        assertEquals(Value.FALSE, call("drawText", text));
        assertEquals(Value.FALSE, call("drawRect", rect));
        assertEquals(Value.FALSE, call("drawOutline", rect));
        assertEquals(Value.FALSE, call("drawLine", rect));
        assertEquals(0.0, call("textWidth", new Value[]{Value.string("hello")}).asNumber());
        assertEquals(0.0, call("screenWidth", new Value[0]).asNumber());
        assertEquals(0.0, call("screenHeight", new Value[0]).asNumber());
    }

    @Test
    void drawScriptCompilesAndRunsWithoutARenderPass() {
        Run run = load("""
                module "DbgD" {
                  category render
                  var drawn = true
                  on render {
                    drawn = drawText("Hi", 4, 4, "#FFFFFF", true) or drawRect(0, 0, 10, 10, 0x80000000)
                    drawOutline(0, 0, 10, 10, 0xFF0000, 2)
                    drawLine(0, 0, screenWidth(), screenHeight(), 0x00FF00)
                    let w = textWidth("Hi")
                  }
                }
                """);
        assertTrue(run.fire("DbgD", "render"));
        assertFalse(run.evaluator().getModuleVar(0).asBool());
    }

    @Test
    void colourParsing() {
        assertEquals(0xFFFF8800, ScriptDraw.parseColor(Value.number(0xFF8800)));
        assertEquals(0x80FF8800, ScriptDraw.parseColor(Value.number(0x80FF8800L)));
        assertEquals(0xFFFF8800, ScriptDraw.parseColor(Value.string("#FF8800")));
        assertEquals(0x80FF8800, ScriptDraw.parseColor(Value.string("#80ff8800")));
        assertEquals(0xFFFF8800, ScriptDraw.parseColor(Value.string("0xff8800")));
        assertEquals(0xFFFFAA00, ScriptDraw.parseColor(Value.string("#FA0")));
        assertEquals(0xFFFFFFFF, ScriptDraw.parseColor(Value.string("nope")));
        assertEquals(0xFFFFFFFF, ScriptDraw.parseColor(Value.number(Double.NaN)));
    }

    private static Value call(String name, Value[] args) {
        int idx = HostApi.indexOf(name);
        assertTrue(idx >= 0, "missing host function " + name);
        return HostApi.table()[idx].call(args, null);
    }
}
