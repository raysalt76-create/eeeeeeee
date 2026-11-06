package interstellarTikiTiks.apexx.client.gui.kit;

import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptReference;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.HostApi;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The IDE reference and starter must match what the script runtime actually accepts. */
class ScriptReferenceTest {

    @Test
    void everyListedHostFunctionExists() {
        for (ScriptReference.Entry e : ScriptReference.entries()) {
            if (e.hostFunction()) {
                assertTrue(HostApi.isHostFunction(e.name()), "HostApi has no function '" + e.name() + "'");
            }
        }
    }

    @Test
    void starterScriptCompiles() {
        assertNull(ScriptManager.check(ScriptReference.starter("Test Script")));
    }

    @Test
    void checkReportsTheLineOfAProblem() {
        ScriptManager.Problem p = ScriptManager.check("module \"X\" {\n  on tick {\n    nonexistent()\n  }\n}\n");
        assertTrue(p != null && p.line() == 3, "expected a problem on line 3, got " + p);
        assertEquals(null, ScriptManager.check("module \"Y\" {\n  on tick {\n  }\n}\n"));
    }
}
