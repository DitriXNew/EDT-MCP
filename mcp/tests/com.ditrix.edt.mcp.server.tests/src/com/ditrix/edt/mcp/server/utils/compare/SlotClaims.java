/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Builds {@link SlotClaim} values for tests that live outside this package.
 *
 * <h2>Why the factories are not simply public</h2>
 * The same reason {@code SlotHandbacks} exists: {@code SlotClaim.granted} and
 * {@code SlotClaim.refused} are package-scoped so that only {@link ComparisonSessionRegistry} can
 * hand out a claim on EDT's single comparison slot. An id minted anywhere else would name a slot
 * nobody reserved, and a launch would then register a session under it - which is the very state
 * the claim was introduced to make unrepresentable.
 * <p>
 * The test fragment shares the package with the bundle, so a helper here reaches the factories
 * without weakening them for production code.
 */
public final class SlotClaims
{
    private SlotClaims()
    {
        // Utility class
    }

    /**
     * @param comparisonId the id the launch may start under
     * @return a granted claim, as the registry would have produced it
     */
    public static SlotClaim granted(String comparisonId)
    {
        return SlotClaim.granted(comparisonId);
    }

    /**
     * @param refusal the owner's sentence about what holds the slot
     * @return a refused claim, as the registry would have produced it
     */
    public static SlotClaim refused(ToolResult refusal)
    {
        return SlotClaim.refused(refusal);
    }
}
