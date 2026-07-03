/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.ConsentSettingsService;
import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.Outcome;

/**
 * Headless ratchet for {@link DestructiveConsentGate}: asserts the full
 * env &gt; headless &gt; session &gt; level &gt; per-tool decision table using the
 * pure {@link DestructiveConsentGate#decide} / {@link DestructiveConsentGate#envForcesAllow}
 * seams — with NO SWT instantiation — plus the relationship between
 * {@link DestructiveConsentGate#GATED_TOOLS} and the destructive tools
 * {@link ToolAnnotationClassifier} advertises. The dialog itself is UI and is verified
 * manually in EDT.
 */
public class DestructiveConsentGateTest
{
    private static final String TOOL = "delete_metadata"; //$NON-NLS-1$

    @After
    public void tearDown()
    {
        // Keep the singleton's in-memory session-allow set clean between tests.
        DestructiveConsentGate.getInstance().clearSessionAllow();
    }

    // =====================================================================
    // Step 1 — env EDT_MCP_DESTRUCTIVE_CONSENT (pure classifier, no process env)
    // =====================================================================

    @Test
    public void envAllowForcesAllow()
    {
        assertTrue("'allow' must force the allow bypass", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("allow")); //$NON-NLS-1$
        assertTrue("env value is case-insensitive", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("ALLOW")); //$NON-NLS-1$
        assertTrue("env value is trimmed", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("  allow  ")); //$NON-NLS-1$
    }

    @Test
    public void envAskDoesNotForceAllow()
    {
        assertFalse("'ask' must NOT bypass — the normal decision order applies", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("ask")); //$NON-NLS-1$
        assertFalse("null (unset) must NOT bypass", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow(null));
        assertFalse("blank must NOT bypass", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("   ")); //$NON-NLS-1$
        assertFalse("an unrelated value must NOT bypass", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("yes")); //$NON-NLS-1$
    }

    // =====================================================================
    // Step 2 — headless: no active UI session -> ALLOW (never blocks)
    // =====================================================================

    @Test
    public void headlessAllowsWithoutPrompt()
    {
        // In the headless unit-test JVM there is no workbench display / active shell,
        // so requireConsent must take the headless path and ALLOW without any SWT.
        // (The e2e-launch env is set on the EDT process, not on this test run, so
        // step 1 does not mask this — but either way the verdict is ALLOW.)
        ConsentDecision decision =
            DestructiveConsentGate.getInstance().requireConsent(TOOL, null);
        assertEquals("Headless / unattended must ALLOW, never block", //$NON-NLS-1$
            ConsentDecision.ALLOW, decision);
    }

    // =====================================================================
    // Step 3 — in-memory per-tool session-allow
    // =====================================================================

    @Test
    public void sessionAllowShortCircuitsToAllow()
    {
        // Even at the default ASK_ALWAYS level, a session-allowed tool does not prompt.
        assertEquals(Outcome.ALLOW,
            DestructiveConsentGate.decide(true, ConsentSettingsService.Level.ASK_ALWAYS, false));
    }

    @Test
    public void sessionAllowSetIsTracked()
    {
        DestructiveConsentGate gate = DestructiveConsentGate.getInstance();
        assertFalse("clean gate has no session-allow entries", gate.isSessionAllowed(TOOL)); //$NON-NLS-1$
        gate.allowForSession(TOOL);
        assertTrue("allowForSession records the tool", gate.isSessionAllowed(TOOL)); //$NON-NLS-1$
        gate.clearSessionAllow();
        assertFalse("clearSessionAllow empties the set", gate.isSessionAllowed(TOOL)); //$NON-NLS-1$
    }

    // =====================================================================
    // Steps 4/5 — preference level via ConsentSettingsService.Level
    // =====================================================================

    @Test
    public void askAlwaysLevelPrompts()
    {
        assertEquals("ASK_ALWAYS with no session/per-tool allow must PROMPT", //$NON-NLS-1$
            Outcome.PROMPT,
            DestructiveConsentGate.decide(false, ConsentSettingsService.Level.ASK_ALWAYS, false));
    }

    @Test
    public void allowAllLevelAllows()
    {
        assertEquals("ALLOW_ALL must ALLOW without a dialog", //$NON-NLS-1$
            Outcome.ALLOW,
            DestructiveConsentGate.decide(false, ConsentSettingsService.Level.ALLOW_ALL, false));
    }

    @Test
    public void perToolLevelAllowsWhenListed()
    {
        assertEquals("PER_TOOL + tool in allow-set must ALLOW", //$NON-NLS-1$
            Outcome.ALLOW,
            DestructiveConsentGate.decide(false, ConsentSettingsService.Level.PER_TOOL, true));
    }

    @Test
    public void perToolLevelPromptsWhenNotListed()
    {
        assertEquals("PER_TOOL + tool NOT in allow-set must PROMPT", //$NON-NLS-1$
            Outcome.PROMPT,
            DestructiveConsentGate.decide(false, ConsentSettingsService.Level.PER_TOOL, false));
    }

    @Test
    public void perToolAllowFlagIsIgnoredAtOtherLevels()
    {
        // The per-tool allow-set only applies at PER_TOOL. A stale allow entry must
        // NOT weaken ASK_ALWAYS.
        assertEquals("ASK_ALWAYS ignores the per-tool allow flag", //$NON-NLS-1$
            Outcome.PROMPT,
            DestructiveConsentGate.decide(false, ConsentSettingsService.Level.ASK_ALWAYS, true));
    }

    // =====================================================================
    // GATED_TOOLS <-> ToolAnnotationClassifier.DESTRUCTIVE_TOOLS relationship
    // =====================================================================

    @Test
    public void gatedToolsAreTheFrozenSix()
    {
        assertEquals("GATED_TOOLS must be exactly the frozen six", //$NON-NLS-1$
            Set.of("delete_metadata", "rename_metadata_object", "delete_project", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "delete_infobase", "update_database", "modify_metadata"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            DestructiveConsentGate.GATED_TOOLS);
    }

    @Test
    public void everyGatedToolExceptModifyMetadataIsClassifiedDestructive()
    {
        // Every gated tool is a destructive MCP write EXCEPT modify_metadata, whose
        // destructiveness is conditional (only a type/composite-type change), so it is
        // deliberately NOT in the always-destructive classifier list.
        for (String tool : DestructiveConsentGate.GATED_TOOLS)
        {
            boolean classifiedDestructive =
                Boolean.TRUE.equals(ToolAnnotationClassifier.classify(tool).getDestructiveHint());
            if ("modify_metadata".equals(tool)) //$NON-NLS-1$
            {
                assertFalse("modify_metadata must NOT be an always-destructive tool", //$NON-NLS-1$
                    classifiedDestructive);
            }
            else
            {
                assertTrue("gated tool '" + tool //$NON-NLS-1$
                    + "' must be classified destructive by ToolAnnotationClassifier", //$NON-NLS-1$
                    classifiedDestructive);
            }
        }
    }

    @Test
    public void deleteLaunchConfigIsDestructiveButNotGated()
    {
        // The only always-destructive tool that is intentionally NOT gated: deleting a
        // launch config is cheap and recoverable (no data loss), so it does not prompt.
        assertTrue("delete_launch_config is an always-destructive tool", //$NON-NLS-1$
            Boolean.TRUE.equals(
                ToolAnnotationClassifier.classify("delete_launch_config").getDestructiveHint())); //$NON-NLS-1$
        assertFalse("delete_launch_config must NOT be gated", //$NON-NLS-1$
            DestructiveConsentGate.GATED_TOOLS.contains("delete_launch_config")); //$NON-NLS-1$
    }
}
