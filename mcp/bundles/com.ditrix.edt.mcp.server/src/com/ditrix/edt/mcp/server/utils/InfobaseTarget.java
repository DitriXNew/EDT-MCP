/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Path;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/** A file or cluster target for the common creation/registration pipeline. */
public record InfobaseTarget(Path file, ServerInfobaseTarget server)
{
    public InfobaseTarget
    {
        if ((file == null) == (server == null))
        {
            throw new IllegalArgumentException("Exactly one file or server target is required."); //$NON-NLS-1$
        }
    }

    public InfobaseReference reference(String name)
    {
        return server == null
            ? InfobaseReferenceFactory.named(
                InfobaseReferences.newFileInfobaseReference(file.toAbsolutePath().toString()), name)
            : server.reference(name);
    }

    public String location()
    {
        return server == null ? file.toAbsolutePath().toString() : server.presentation();
    }
}
