/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

/**
 * Builds {@link SlotHandback} values for tests that live outside this package.
 *
 * <h2>Why the factory is not simply public</h2>
 * {@code SlotHandback.of} is package-scoped so that only the ONE owner of the hand-back decision -
 * {@link ComparisonSessionRegistry} - can state what became of EDT's single comparison slot. That
 * is a compile-enforced half of the construction, and making the factory public to please a test
 * would delete it: any tool could then mint a verdict and publish its sentence, which is exactly
 * the family of defects the type exists to end.
 * <p>
 * The test fragment shares the package with the bundle, so a helper here reaches the factory
 * without weakening it for production code. A stub backend can therefore answer with any of the
 * five verdicts while the bundle still has exactly one place that produces them.
 */
public final class SlotHandbacks
{
    private SlotHandbacks()
    {
        // Utility class
    }

    /**
     * @param verdict what the hand-back is to report
     * @param comparisonId the comparison it names
     * @return the value, as the registry would have produced it
     */
    public static SlotHandback of(SlotHandback.Verdict verdict, String comparisonId)
    {
        return SlotHandback.of(verdict, comparisonId);
    }
}
