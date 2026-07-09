/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.eventlog;

import java.util.HashMap;
import java.util.Map;

/**
 * The resolved reference tables loaded from {@code 1Cv8.lgf}. Every record in a
 * {@code *.lgp} partition refers to entries here by a sequential index (the trailing
 * integer of each {@code .lgf} record).
 *
 * <p>Type codes (the leading integer of each {@code .lgf} record):</p>
 * <ul>
 *   <li>1 = User: {@code {1, uuid, "name", seq}}</li>
 *   <li>2 = Computer: {@code {2, "name", seq}}</li>
 *   <li>3 = Application: {@code {3, "name", seq}} - e.g. {@code 1CV8C}, {@code Designer}, {@code BackgroundJob}</li>
 *   <li>4 = Event: {@code {4, "name", seq}} - e.g. {@code _$Session$_.Start}</li>
 *   <li>5 = Metadata: {@code {5, uuid, "fullName", seq}}</li>
 *   <li>6 = WorkServer: {@code {6, "name", seq}}</li>
 *   <li>7 = MainPort: {@code {7, port, seq}}</li>
 *   <li>8 = SecondaryPort: {@code {8, port, seq}}</li>
 *   <li>9-13 = data-separator / internal housekeeping indices - intentionally ignored</li>
 * </ul>
 *
 * <p>Zero EDT/OSGi dependencies - headless-unit-testable.</p>
 */
public final class EventLogReferences
{
    /** A user reference entry: display name plus the infobase-user UUID. */
    public static final class User
    {
        /** The infobase-user UUID (may be a zero UUID for the system/no user). */
        public final String uuid;

        /** The user display name (may be an empty string in real logs). */
        public final String name;

        public User(String uuid, String name)
        {
            this.uuid = uuid;
            this.name = name;
        }
    }

    /** A metadata reference entry: a full bilingual name plus its object UUID. */
    public static final class Metadata
    {
        /** The metadata object UUID. */
        public final String uuid;

        /** The full metadata name as stored in the log (e.g. {@code Document.Order}). */
        public final String fullName;

        public Metadata(String uuid, String fullName)
        {
            this.uuid = uuid;
            this.fullName = fullName;
        }
    }

    /** seq -&gt; user. */
    public final Map<Integer, User> users = new HashMap<>();

    /** seq -&gt; computer name. */
    public final Map<Integer, String> computers = new HashMap<>();

    /** seq -&gt; application name. */
    public final Map<Integer, String> applications = new HashMap<>();

    /** seq -&gt; event name. */
    public final Map<Integer, String> events = new HashMap<>();

    /** seq -&gt; metadata. */
    public final Map<Integer, Metadata> metadata = new HashMap<>();

    /** seq -&gt; work-server name. */
    public final Map<Integer, String> servers = new HashMap<>();

    /** seq -&gt; main port. */
    public final Map<Integer, Integer> mainPorts = new HashMap<>();

    /** seq -&gt; secondary port. */
    public final Map<Integer, Integer> secondaryPorts = new HashMap<>();

    /** @return the user display name for {@code seq}, or {@code null} if unknown. */
    public String userName(int seq)
    {
        User u = this.users.get(Integer.valueOf(seq));
        return u == null ? null : u.name;
    }

    /** @return the user UUID for {@code seq}, or {@code null} if unknown. */
    public String userUuid(int seq)
    {
        User u = this.users.get(Integer.valueOf(seq));
        return u == null ? null : u.uuid;
    }

    /** @return the metadata full name for {@code seq}, or {@code null} if unknown. */
    public String metadataName(int seq)
    {
        Metadata m = this.metadata.get(Integer.valueOf(seq));
        return m == null ? null : m.fullName;
    }

    /** @return the metadata UUID for {@code seq}, or {@code null} if unknown. */
    public String metadataUuid(int seq)
    {
        Metadata m = this.metadata.get(Integer.valueOf(seq));
        return m == null ? null : m.uuid;
    }
}
