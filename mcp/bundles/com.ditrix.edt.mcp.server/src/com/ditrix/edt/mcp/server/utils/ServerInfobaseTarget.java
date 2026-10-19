/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/** Address of an existing cluster infobase; never creates or alters its database. */
public record ServerInfobaseTarget(String server, String infobase)
{
    /** Validate before touching EDT services, files, credentials, or the platform. */
    public static String validationError(String server, String infobase, String file,
            boolean register, boolean standalone)
    {
        if (!specified(server) && !specified(infobase))
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
        String error = fieldError("infobaseServer", server); //$NON-NLS-1$
        if (error != null)
        {
            return error;
        }
        for (String address : server.split(",", -1)) //$NON-NLS-1$
        {
            if (!validServerAddress(address))
            {
                return "infobaseServer [masked] must be a comma-separated list of host[:port] " //$NON-NLS-1$
                    + "or [IPv6][:port] entries, with port 1-65535 and no whitespace; " //$NON-NLS-1$
                    + "use a plain address such as localhost:1541."; //$NON-NLS-1$
            }
        }
        return fieldError("infobaseRef", infobase); //$NON-NLS-1$
    }

    public static boolean specified(String value)
    {
        return value != null && !value.isBlank();
    }

    private static String fieldError(String field, String value)
    {
        if (value.chars().anyMatch(Character::isISOControl))
        {
            return field + " [masked] contains control characters; remove them and use a plain value."; //$NON-NLS-1$
        }
        if (!value.equals(value.strip()))
        {
            return field + " [masked] has leading or trailing whitespace; remove that whitespace."; //$NON-NLS-1$
        }
        for (char forbidden : new char[] {'"', ';', '=', '/', '\\'})
        {
            if (value.indexOf(forbidden) >= 0)
            {
                return field + " [masked] contains '" + forbidden //$NON-NLS-1$
                    + "'; use a plain value, not a URL, path or connection string."; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static boolean validServerAddress(String address)
    {
        String host;
        String suffix;
        if (address.startsWith("[")) //$NON-NLS-1$
        {
            int end = address.indexOf(']');
            if (end < 0)
            {
                return false;
            }
            host = address.substring(1, end);
            suffix = address.substring(end + 1);
            // Only numeric literals reach getByName: no DNS lookup or interface-zone resolution.
            if (!host.contains(":") || !host.matches("[0-9a-fA-F:.]+")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return false;
            }
            try
            {
                // Java represents valid IPv4-mapped IPv6 literals as Inet4Address.
                InetAddress.getByName(host);
            }
            catch (UnknownHostException e)
            {
                return false;
            }
        }
        else
        {
            int colon = address.indexOf(':');
            host = colon < 0 ? address : address.substring(0, colon);
            suffix = colon < 0 ? "" : address.substring(colon); //$NON-NLS-1$
            // Labels cannot consume dots; neither label nor group backtracking can help a match.
            if (!host.matches("[\\p{L}\\p{N}_-]++(?:\\.[\\p{L}\\p{N}_-]++)*+\\.?")) //$NON-NLS-1$
            {
                return false;
            }
        }
        if (suffix.isEmpty())
        {
            return true;
        }
        if (!suffix.matches(":[0-9]{1,5}")) //$NON-NLS-1$
        {
            return false;
        }
        int port = Integer.parseInt(suffix.substring(1));
        return port >= 1 && port <= 65535;
    }

    /** Use EDT's typed connection-string builder, not hand-written Srvr/Ref text. */
    public InfobaseReference reference(String displayName)
    {
        return InfobaseReferenceFactory.named(
            InfobaseReferences.newServerInfobaseReference(server, infobase), displayName);
    }

    /** Non-secret location for messages; contains no authentication or DBMS settings. */
    public String presentation()
    {
        return server + "/" + infobase; //$NON-NLS-1$
    }
}
