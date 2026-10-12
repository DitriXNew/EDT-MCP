/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.UUID;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/** Initializes every fresh reference before EDT can lock it by UUID. */
public final class InfobaseReferenceFactory
{
    private InfobaseReferenceFactory()
    {
    }

    /** The UUID must exist before the creation operation's perform() obtains its lock. */
    public static InfobaseReference named(InfobaseReference reference, String name)
    {
        reference.setName(name);
        reference.setUuid(UUID.randomUUID());
        return reference;
    }
}
