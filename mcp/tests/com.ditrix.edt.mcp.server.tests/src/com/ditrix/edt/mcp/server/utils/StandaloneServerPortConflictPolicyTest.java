/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link StandaloneServerPortConflictPolicy} — the unattended answer to EDT's
 * standalone-server port-conflict modal.
 *
 * <p>The load-bearing property is the DEFAULT: omitting the parameter must never re-address
 * someone's server. Everything else (parsing shape, accepted values) mirrors
 * {@link ExternalInfobaseChangesPolicy}, whose contract these tests keep this one aligned with.
 */
public class StandaloneServerPortConflictPolicyTest
{
    @Test
    public void testDefaultIsCancel()
    {
        // The whole safety argument of the feature: a caller who says nothing gets the answer
        // that writes nothing, never the one that rewrites the server's configuration.
        assertSame("the default must be the non-writing answer",
            StandaloneServerPortConflictPolicy.CANCEL,
            StandaloneServerPortConflictPolicy.DEFAULT);
    }

    @Test
    public void testBlankAndNullResolveToTheDefault()
    {
        assertSame(StandaloneServerPortConflictPolicy.DEFAULT,
            StandaloneServerPortConflictPolicy.parse(null));
        assertSame(StandaloneServerPortConflictPolicy.DEFAULT,
            StandaloneServerPortConflictPolicy.parse(""));
        assertSame(StandaloneServerPortConflictPolicy.DEFAULT,
            StandaloneServerPortConflictPolicy.parse("   "));
    }

    @Test
    public void testParsesBothTokensCaseInsensitivelyAndTrimmed()
    {
        assertSame(StandaloneServerPortConflictPolicy.CANCEL,
            StandaloneServerPortConflictPolicy.parse("cancel"));
        assertSame(StandaloneServerPortConflictPolicy.REASSIGN,
            StandaloneServerPortConflictPolicy.parse("reassign"));
        assertSame(StandaloneServerPortConflictPolicy.REASSIGN,
            StandaloneServerPortConflictPolicy.parse("  ReAssign "));
    }

    @Test
    public void testUnknownTokenIsRejectedRatherThanDefaulted()
    {
        // null means "the caller typed something we do not know" — the tool turns that into an
        // actionable error. Silently defaulting would hide a typo that changes behaviour.
        assertNull(StandaloneServerPortConflictPolicy.parse("free-ports"));
        assertNull(StandaloneServerPortConflictPolicy.parse("true"));
    }

    @Test
    public void testWireValuesAreTheLowercaseTokens()
    {
        assertEquals("cancel", StandaloneServerPortConflictPolicy.CANCEL.wireValue());
        assertEquals("reassign", StandaloneServerPortConflictPolicy.REASSIGN.wireValue());
    }

    @Test
    public void testAcceptedValuesListsEveryToken()
    {
        String accepted = StandaloneServerPortConflictPolicy.acceptedValues();
        for (StandaloneServerPortConflictPolicy policy : StandaloneServerPortConflictPolicy.values())
        {
            assertTrue("the error text must name " + policy.wireValue(),
                accepted.contains(policy.wireValue()));
        }
    }

    @Test
    public void testParameterDescriptionNamesBothTokensAndTheConsequence()
    {
        // The description is the only place a caller learns that one of the two answers changes
        // their stand; the shared constant is what keeps all three tools saying so.
        String description = StandaloneServerPortConflictPolicy.PARAMETER_DESCRIPTION;
        assertTrue(description.contains("cancel"));
        assertTrue(description.contains("reassign"));
        assertTrue("the description must state that reassign rewrites the configuration",
            description.contains("rewrites"));
    }
}
