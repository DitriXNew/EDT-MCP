/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/** Address of an existing cluster infobase; never creates or alters its database. */
public record ServerInfobaseTarget(String server, String infobase)
{
    /** Validate before touching EDT services, files, credentials, or the platform. */
    public static String validationError(String server, String infobase, String file,
            boolean register, boolean standalone)
    {
        if (server == null && infobase == null)
        {
            return null;
        }
        if (specified(file))
        {
            return "Choose either infobaseFile or infobaseServer + infobaseRef, not both."; //$NON-NLS-1$
        }
        if (!specified(server) || !specified(infobase))
        {
            return "infobaseServer and infobaseRef are required together for a server infobase."; //$NON-NLS-1$
        }
        if (!register)
        {
            return "Server infobases support mode='register' only. Create the database separately, " //$NON-NLS-1$
                + "then register its infobaseServer and infobaseRef; no server database is created here."; //$NON-NLS-1$
        }
        if (standalone)
        {
            return "infobaseServer + infobaseRef require applicationKind='infobase'; " //$NON-NLS-1$
                + "standaloneServer wraps a file infobase, not a cluster database."; //$NON-NLS-1$
        }
        if (!plainAddress(server) || !plainAddress(infobase)
                || server.contains("/") || server.contains("\\")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "Use a plain infobaseServer address (for example localhost:1541) and an " //$NON-NLS-1$
                + "infobaseRef name, not a URL or connection string; quotes, semicolons and " //$NON-NLS-1$
                + "control characters are not supported."; //$NON-NLS-1$
        }
        return null;
    }

    private static boolean specified(String value)
    {
        return value != null && !value.isBlank();
    }

    private static boolean plainAddress(String value)
    {
        return value.equals(value.strip()) && value.chars().noneMatch(c ->
            Character.isISOControl(c) || c == '"' || c == ';' || c == '=');
    }

    /** Use EDT's typed connection-string builder, not hand-written Srvr/Ref text. */
    public InfobaseReference reference(String displayName)
    {
        InfobaseReference reference = InfobaseReferences.newServerInfobaseReference(server, infobase);
        reference.setName(displayName);
        reference.setUuid(java.util.UUID.randomUUID());
        return reference;
    }

    /** Non-secret location for messages; contains no authentication or DBMS settings. */
    public String presentation()
    {
        return server + "/" + infobase; //$NON-NLS-1$
    }
}
