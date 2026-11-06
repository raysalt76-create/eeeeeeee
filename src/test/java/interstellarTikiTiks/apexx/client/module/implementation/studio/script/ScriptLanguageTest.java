package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptReference;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.Block;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.EventKind;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.Handler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser, resolver and evaluator coverage for events, functions, lists, records and `after`. */
class ScriptLanguageTest {

    /** A host without Minecraft: settings come from an array, `after` blocks are queued. */
    private static final class MockHost implements ScriptHost {
        final List<Object[]> scheduled = new ArrayList<>();
        Value[] settings = new Value[0];
        @Override public Value readSetting(int slot) { return settings[slot]; }
        @Override public void schedule(Block body, Value[] locals, double delayMs) {
            scheduled.add(new Object[]{body, locals, delayMs});
        }
    }

    private record Run(ScriptCompiler.Resolved resolved, Evaluator evaluator, MockHost host) {
        Value var(int slot) { return evaluator.getModuleVar(slot); }
        boolean fire(String event, Value ev) {
            Handler h = resolved.handler(event);
            assertNotNull(h, "no handler for " + event);
            return evaluator.runHandler(h.body, ev, h.paramSlot);
        }
    }

    private static Run load(String source) {
        ScriptCompiler.Resolved r = ScriptCompiler.parseAndResolve(source);
        MockHost host = new MockHost();
        Evaluator ev = new Evaluator(host, HostApi.table(), r.functions, r.moduleVarCount, r.maxLocals);
        return new Run(r, ev, host);
    }

    @Test
    void eventHandlerBindsTheEventRecord() {
        Run run = load("""
                module "T" {
                  var out = 0
                  var who = ""
                  on hurt(ev) {
                    out = ev.amount * 2
                    who = ev.source
                  }
                }
                """);
        assertTrue(run.fire("hurt", Value.rec().num("amount", 3.5).str("source", "player").build()));
        assertEquals(7.0, run.var(0).asNumber());
        assertEquals("player", run.var(1).asString());
    }

    @Test
    void missingFieldReadsAsNull() {
        Run run = load("""
                module "T" {
                  var out = 1
                  on chat(ev) {
                    out = ev.nope == null
                  }
                }
                """);
        run.fire("chat", Value.rec().str("message", "hi").build());
        assertTrue(run.var(0).asBool());
    }

    @Test
    void userFunctionsReturnValuesAndRecurse() {
        Run run = load("""
                module "T" {
                  var out = 0
                  fn fact(n) {
                    if n <= 1 { return 1 }
                    return n * fact(n - 1)
                  }
                  on tick {
                    out = fact(5)
                  }
                }
                """);
        assertTrue(run.fire("tick", null));
        assertEquals(120.0, run.var(0).asNumber());
    }

    @Test
    void listsIndexingAndForEach() {
        Run run = load("""
                module "T" {
                  var out = 0
                  var text = ""
                  on tick {
                    let xs = [3, 1, 2]
                    push(xs, 4)
                    xs[0] = 9
                    let total = 0
                    for x in xs {
                      total = total + x
                    }
                    out = total
                    text = join(sort(xs), "-") + "|" + str(len(xs)) + "|" + str(xs[-1])
                  }
                }
                """);
        assertTrue(run.fire("tick", null));
        assertEquals(16.0, run.var(0).asNumber());
        assertEquals("1-2-4-9|4|4", run.var(1).asString());
    }

    @Test
    void afterCapturesLocalsAtScheduleTime() {
        Run run = load("""
                module "T" {
                  var out = 0
                  on tick {
                    let a = 7
                    after 50 {
                      out = a + 1
                    }
                    a = 100
                  }
                }
                """);
        assertTrue(run.fire("tick", null));
        assertEquals(1, run.host().scheduled.size());
        Object[] s = run.host().scheduled.get(0);
        assertEquals(50.0, (Double) s[2]);
        assertEquals(0.0, run.var(0).asNumber(), "after must not run immediately");
        assertTrue(run.evaluator().runScheduled((Block) s[0], (Value[]) s[1]));
        assertEquals(8.0, run.var(0).asNumber());
    }

    @Test
    void everyHandlerKeepsItsInterval() {
        ScriptCompiler.Resolved r = ScriptCompiler.parseAndResolve("""
                module "T" {
                  on every(250) {
                  }
                }
                """);
        Handler h = r.handler("every");
        assertEquals(EventKind.EVERY, h.kind);
        assertEquals(250.0, h.intervalMs);
    }

    @Test
    void settingsJitterBindableColorAndHeaders() {
        ScriptCompiler.Resolved r = ScriptCompiler.parseAndResolve("""
                module "T" {
                  type trigger
                  key "MOUSE4"
                  setting slider delay = 50 range 0..500 jitter 10 "Delay in ms"
                  setting toggle web = true bindable if "delay"
                  setting color tint = 0xFF8800
                  on trigger {
                  }
                }
                """);
        assertEquals("trigger", r.decl.scriptType);
        assertEquals("MOUSE4", r.decl.defaultKey);
        assertEquals(10.0, r.decl.settings.get(0).jitter);
        assertEquals("Delay in ms", r.decl.settings.get(0).description);
        assertTrue(r.decl.settings.get(1).bindable);
        assertEquals("delay", r.decl.settings.get(1).dependsOn);
        assertEquals(0xFFFF8800, r.decl.settings.get(2).defaultColor);
    }

    @Test
    void compileErrorsCarryLines() {
        ScriptException unknownEvent = assertThrows(ScriptException.class, () -> ScriptCompiler.parseAndResolve(
                "module \"T\" {\n  on nonsense(ev) {\n  }\n}\n"));
        assertEquals(2, unknownEvent.line);

        ScriptException triggerInToggle = assertThrows(ScriptException.class, () -> ScriptCompiler.parseAndResolve(
                "module \"T\" {\n  on trigger {\n  }\n}\n"));
        assertEquals(2, triggerInToggle.line);

        ScriptException arity = assertThrows(ScriptException.class, () -> ScriptCompiler.parseAndResolve(
                "module \"T\" {\n  fn f(a, b) {\n    return a\n  }\n  on tick {\n    f(1)\n  }\n}\n"));
        assertEquals(6, arity.line);

        assertThrows(ScriptException.class, () -> ScriptCompiler.parseAndResolve(
                "module \"T\" {\n  on tick(ev) {\n  }\n}\n"));
        assertThrows(ScriptException.class, () -> ScriptCompiler.parseAndResolve(
                "module \"T\" {\n  fn len(x) {\n  }\n}\n"));
        assertThrows(ScriptException.class, () -> ScriptCompiler.parseAndResolve(
                "module \"T\" {\n  key \"NOT_A_KEY\"\n}\n"));
    }

    @Test
    void runawayRecursionIsARuntimeError() {
        Run run = load("""
                module "T" {
                  fn f(n) {
                    return f(n + 1)
                  }
                  on tick {
                    f(0)
                  }
                }
                """);
        Evaluator.ScriptRuntimeError e = assertThrows(Evaluator.ScriptRuntimeError.class, () -> run.fire("tick", null));
        assertTrue(e.line > 0);
    }

    @Test
    void infiniteLoopHitsTheBudget() {
        Run run = load("""
                module "T" {
                  on tick {
                    while true {
                    }
                  }
                }
                """);
        assertFalse(run.fire("tick", null));
    }

    @Test
    void stringsCompareAndIndex() {
        Run run = load("""
                module "T" {
                  var out = false
                  var ch = ""
                  on tick {
                    out = "apple" < "banana" and upper("ab") == "AB" and contains("hello", "ell")
                    ch = "xyz"[1]
                  }
                }
                """);
        run.fire("tick", null);
        assertTrue(run.var(0).asBool());
        assertEquals("y", run.var(1).asString());
    }

    @Test
    void gaussIsNeverNegativeAndZeroStdevIsExact() {
        Run run = load("""
                module "T" {
                  var lowest = 1000
                  var exact = 0
                  on tick {
                    for i in 0..500 {
                      lowest = min(lowest, gauss(5, 50))
                    }
                    exact = gauss(42, 0)
                  }
                }
                """);
        // The first call can trip the 8 ms time budget while the JVM loads classes; that only skips the run.
        boolean completed = false;
        for (int attempt = 0; attempt < 5 && !completed; attempt++) completed = run.fire("tick", null);
        assertTrue(completed);
        assertTrue(run.var(0).asNumber() >= 0);
        assertEquals(42.0, run.var(1).asNumber());
    }

    @Test
    void everyTemplateCompiles() {
        for (String[] t : ScriptReference.templates()) {
            assertNull(ScriptManager.check(t[2]), "template '" + t[0] + "' does not compile: " + ScriptManager.check(t[2]));
        }
    }

    @Test
    void everyCatalogEventParses() {
        for (ScriptEventCatalog.EventInfo info : ScriptEventCatalog.all().values()) {
            String header = info.name().equals("trigger") ? "  type trigger\n" : "";
            String handler = info.name().equals("every") ? "on every(100)"
                    : ScriptEventCatalog.isLifecycle(info.name()) ? "on " + info.name()
                    : "on " + info.name() + "(ev)";
            String src = "module \"T\" {\n" + header + "  " + handler + " {\n  }\n}\n";
            assertNull(ScriptManager.check(src), info.name() + ": " + ScriptManager.check(src));
        }
    }

    @Test
    void moduleHookEventsAreCatalogued() {
        for (String name : List.of("tick_post", "tick_end", "world_render", "world_render_end", "attack_input",
                "vanilla_attack", "mace_hit", "swap_lock", "explosion", "packet_receive", "packet_send", "use_input",
                "block_interact", "block_interact_post", "jump_input", "jump", "movement_input", "look_input",
                "mouse_sensitivity")) {
            assertTrue(ScriptEventCatalog.isEvent(name), "missing event " + name);
        }
        for (String respondable : ScriptEventCatalog.RESPONSES.keySet()) {
            assertTrue(ScriptEventCatalog.isEvent(respondable), "response for unknown event " + respondable);
        }
        assertTrue(ScriptEventCatalog.isLifecycle("tick_post"));
        assertNull(ScriptManager.check("""
                module "T" {
                  on attack_input(ev) {
                    if ev.target and ev.target.isFriend { cancel() }
                  }
                  on movement_input(ev) {
                    if ev.forward { setInput("sprint", true) }
                  }
                  on look_input(ev) {
                    setLook(ev.dx * 0.5, ev.dy * 0.5)
                  }
                  on swap_lock {
                    cancel("swap")
                  }
                  on jump_input(ev) {
                    setResult(false)
                  }
                }
                """));
    }

    @Test
    void responseCallsAreHarmlessOutsideTheGame() {
        Run run = load("""
                module "T" {
                  var out = 0
                  on attack_input(ev) {
                    cancel()
                    setResult(1)
                    setInput("forward", true)
                    setLook(1, 2)
                    out = 1
                  }
                }
                """);
        assertTrue(run.fire("attack_input", Value.rec().build()));
        assertEquals(1.0, run.var(0).asNumber());
    }

    @Test
    void keyNamesMapToModuleKeyCodes() {
        assertEquals(82, ScriptKeys.parse("r"));
        assertEquals(-103, ScriptKeys.parse("mouse4"));
        assertEquals(340, ScriptKeys.parse("left shift"));
        assertEquals(ScriptKeys.UNKNOWN, ScriptKeys.parse("nope"));
        assertEquals("SPACE", ScriptKeys.name(32));
    }
}
