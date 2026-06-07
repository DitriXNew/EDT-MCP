/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Runtime state of which toolsets are currently revealed for progressive tool
 * disclosure. Server-scoped (this EDT MCP server serves one client at a time) and
 * in-memory (resets on restart): the client drives it through {@code enable_toolset}.
 * <p>
 * The {@link Toolsets#CORE} toolset is ALWAYS visible and is not stored here; this
 * set holds only the non-core toolsets the client has revealed. When progressive
 * disclosure is off the whole mechanism is bypassed (see
 * {@code McpToolRegistry.getVisibleTools}), so this state is irrelevant then.
 */
public final class ToolsetState
{
    private static final ToolsetState INSTANCE = new ToolsetState();

    /** Non-core toolset ids the client has revealed. Thread-safe (transport threads). */
    private final Set<String> enabled = new CopyOnWriteArraySet<>();

    private ToolsetState()
    {
        // Singleton
    }

    public static ToolsetState getInstance()
    {
        return INSTANCE;
    }

    /**
     * Reveals a toolset. {@link Toolsets#CORE} is always visible, so enabling it is a
     * no-op. Unknown ids are ignored (the caller validates and reports them).
     *
     * @param toolsetId the toolset id to reveal
     * @return {@code true} if this changed the visible set
     */
    public boolean enable(String toolsetId)
    {
        if (toolsetId == null || Toolsets.CORE.equals(toolsetId) || !Toolsets.exists(toolsetId))
        {
            return false;
        }
        return enabled.add(toolsetId);
    }

    /**
     * Hides a previously revealed toolset. {@link Toolsets#CORE} cannot be hidden.
     *
     * @param toolsetId the toolset id to hide
     * @return {@code true} if this changed the visible set
     */
    public boolean disable(String toolsetId)
    {
        if (toolsetId == null || Toolsets.CORE.equals(toolsetId))
        {
            return false;
        }
        return enabled.remove(toolsetId);
    }

    /**
     * Whether a toolset is currently visible: {@link Toolsets#CORE} always is; any
     * other toolset only after it has been enabled.
     *
     * @param toolsetId the toolset id
     * @return {@code true} if visible
     */
    public boolean isVisible(String toolsetId)
    {
        return Toolsets.CORE.equals(toolsetId) || enabled.contains(toolsetId);
    }

    /**
     * The non-core toolset ids currently revealed (a snapshot copy).
     *
     * @return the enabled non-core toolset ids
     */
    public Set<String> getEnabledToolsetIds()
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(enabled));
    }

    /**
     * Clears all revealed toolsets (back to core-only). Used by tests and on a fresh
     * disclosure session.
     */
    public void reset()
    {
        enabled.clear();
    }
}
