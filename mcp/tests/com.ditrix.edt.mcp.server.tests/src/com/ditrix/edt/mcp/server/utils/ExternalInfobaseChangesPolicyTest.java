/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link ExternalInfobaseChangesPolicy} - the wire contract of the
 * {@code externalInfobaseChanges} parameter that answers EDT's blocking "Infobase
 * configuration changes" modal in an unattended run.
 *
 * <p>The default matters: {@code override} is the only choice that means what
 * {@code updateBeforeLaunch} promises (apply the PROJECT configuration to the infobase).
 * {@code import} rewrites the caller's project sources, so it must never be reachable by
 * accident - only by an explicit, exactly-spelled value.
 */
public class ExternalInfobaseChangesPolicyTest
{
    @Test
    public void testDefaultIsOverride()
    {
        assertEquals(ExternalInfobaseChangesPolicy.OVERRIDE, ExternalInfobaseChangesPolicy.DEFAULT);
    }

    @Test
    public void testMissingValueYieldsDefault()
    {
        assertEquals(ExternalInfobaseChangesPolicy.DEFAULT, ExternalInfobaseChangesPolicy.parse(null));
        assertEquals(ExternalInfobaseChangesPolicy.DEFAULT, ExternalInfobaseChangesPolicy.parse("")); //$NON-NLS-1$
        assertEquals(ExternalInfobaseChangesPolicy.DEFAULT, ExternalInfobaseChangesPolicy.parse("   ")); //$NON-NLS-1$
    }

    @Test
    public void testParsesEveryWireValue()
    {
        assertEquals(ExternalInfobaseChangesPolicy.OVERRIDE,
            ExternalInfobaseChangesPolicy.parse("override")); //$NON-NLS-1$
        assertEquals(ExternalInfobaseChangesPolicy.IMPORT,
            ExternalInfobaseChangesPolicy.parse("import")); //$NON-NLS-1$
        assertEquals(ExternalInfobaseChangesPolicy.CANCEL,
            ExternalInfobaseChangesPolicy.parse("cancel")); //$NON-NLS-1$
    }

    @Test
    public void testParseIsCaseInsensitiveAndTrimmed()
    {
        assertEquals(ExternalInfobaseChangesPolicy.IMPORT,
            ExternalInfobaseChangesPolicy.parse("  Import ")); //$NON-NLS-1$
        assertEquals(ExternalInfobaseChangesPolicy.CANCEL,
            ExternalInfobaseChangesPolicy.parse("CANCEL")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownValueIsRejectedRatherThanSilentlyDefaulted()
    {
        // A typo must NOT silently fall back to override (which writes the infobase).
        assertNull(ExternalInfobaseChangesPolicy.parse("overwrite")); //$NON-NLS-1$
        assertNull(ExternalInfobaseChangesPolicy.parse("merge")); //$NON-NLS-1$
        assertNull(ExternalInfobaseChangesPolicy.parse("true")); //$NON-NLS-1$
    }

    @Test
    public void testWireValuesAreLowercaseTokens()
    {
        for (ExternalInfobaseChangesPolicy policy : ExternalInfobaseChangesPolicy.values())
        {
            assertEquals(policy.wireValue().toLowerCase(java.util.Locale.ROOT), policy.wireValue());
            assertEquals(policy, ExternalInfobaseChangesPolicy.parse(policy.wireValue()));
        }
    }

    @Test
    public void testAcceptedValuesNamesEveryPolicy()
    {
        String accepted = ExternalInfobaseChangesPolicy.acceptedValues();
        for (ExternalInfobaseChangesPolicy policy : ExternalInfobaseChangesPolicy.values())
        {
            assertTrue(accepted, accepted.contains(policy.wireValue()));
        }
    }
}
