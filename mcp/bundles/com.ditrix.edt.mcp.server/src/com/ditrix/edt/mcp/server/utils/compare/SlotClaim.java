/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * The answer to "may THIS launch have EDT's single comparison slot?", taken in one indivisible
 * step.
 *
 * <h2>The race this type ends</h2>
 * EDT runs one comparison per instance, and a launch used to CHECK the slot and then take it, with
 * the whole of the preparation in between: two revisions resolved through git, the project looked
 * up, the batch built. Two launches arriving together both read "nothing is running", both spent
 * that minute, and both registered a session before either reached the platform. EDT then refused
 * the second one - it asserts that no other comparison is running - but its registration was
 * already made, and a registration is what this server's own refusals are computed from. The
 * second launch left EDT's slot recorded as taken by a comparison that never existed.
 * <p>
 * A claim is the INTENT, staked before the preparation rather than after it. It is granted or
 * refused under the registry's monitor, so of two launches exactly one is granted, and the other
 * gets a sentence instead of a race.
 *
 * <h2>Why a refused claim carries a whole sentence</h2>
 * The two refusals are different situations with different remedies - a comparison that is OPEN has
 * to be released or cancelled by id, while a launch still STARTING only has to be waited for - and
 * a caller wording them itself is how two sites come to word one situation differently. The owner
 * therefore hands out the refusal it minted, and the caller publishes it.
 *
 * <h2>What a claim is NOT</h2>
 * <ul>
 * <li><b>Not a registration.</b> It holds no handle and names no comparison EDT knows about, so it
 * is not addressable by {@code get_comparison_node}, not listed among the ids a caller may quote,
 * and not something {@link SlotHandback} can be asked about. It becomes a session only through
 * {@link ComparisonSessionRegistry#adoptClaim}, in the same step as the registration.</li>
 * <li><b>Not a promise that the launch will succeed.</b> It is given up either way: adopted when the
 * batch is handed to EDT, withdrawn when the preparation fails. Withdrawing touches only the claim
 * - it can never drop the record of a comparison that may be running, which is the one thing
 * {@link SlotHandback} exists to protect.</li>
 * </ul>
 */
public final class SlotClaim
{
    private final String comparisonId;

    private final ToolResult refusal;

    private SlotClaim(String comparisonId, ToolResult refusal)
    {
        this.comparisonId = comparisonId;
        this.refusal = refusal;
    }

    /**
     * Package-scoped so that only the registry can grant a claim: an id minted anywhere else would
     * name a slot nobody reserved.
     *
     * @param comparisonId the id the launch must register under
     * @return the granted claim
     */
    static SlotClaim granted(String comparisonId)
    {
        return new SlotClaim(comparisonId, null);
    }

    /**
     * Package-scoped for the same reason {@link #granted(String)} is.
     *
     * @param refusal the owner's own sentence about what holds the slot
     * @return the refused claim
     */
    static SlotClaim refused(ToolResult refusal)
    {
        return new SlotClaim(null, refusal);
    }

    /** @return whether this launch may proceed */
    public boolean granted()
    {
        return comparisonId != null;
    }

    /**
     * @return the id the launch registers under, and the id the started comparison keeps - or
     *     {@code null} when the claim was refused
     */
    public String comparisonId()
    {
        return comparisonId;
    }

    /**
     * @return the owner's refusal, to be published as it stands - or {@code null} when the claim
     *     was granted
     */
    public ToolResult refusal()
    {
        return refusal;
    }
}
