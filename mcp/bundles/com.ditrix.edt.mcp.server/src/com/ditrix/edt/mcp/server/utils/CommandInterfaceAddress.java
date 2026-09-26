/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Locale;

/**
 * Parses the FQN of a command interface - the platform's own external-property address:
 * {@code Subsystem.<Name>[.Subsystem.<Child>...].CommandInterface} for a section,
 * {@code Configuration.MainSectionCommandInterface} for the desktop (main) section and
 * {@code Configuration.CommandInterface} for the sections panel. Type and tail tokens may be
 * English or Russian; the subsystem Names are programmatic Names.
 */
public final class CommandInterfaceAddress
{
    /** Which command interface an address names. */
    public enum Kind
    {
        /** A subsystem's section: its commands by panel group. */
        SUBSYSTEM,
        /** The configuration's main (desktop) section: its commands by panel group. */
        MAIN_SECTION,
        /** The configuration's sections panel: subsystem visibility and order, not commands. */
        SECTIONS_PANEL
    }

    /** The platform's tail token of a command interface external property. */
    public static final String COMMAND_INTERFACE_TOKEN = "CommandInterface"; //$NON-NLS-1$

    /** The platform's tail token of the main section's command interface. */
    public static final String MAIN_SECTION_TOKEN = "MainSectionCommandInterface"; //$NON-NLS-1$

    private static final String CONFIGURATION_TOKEN = "Configuration"; //$NON-NLS-1$

    private static final String RU_COMMAND_INTERFACE = MetadataLanguageUtils.cp(0x043a, 0x043e, 0x043c,
        0x0430, 0x043d, 0x0434, 0x043d, 0x044b, 0x0439, 0x0438, 0x043d, 0x0442, 0x0435, 0x0440, 0x0444, 0x0435,
        0x0439, 0x0441);

    private static final String RU_CONFIGURATION = MetadataLanguageUtils.cp(0x043a, 0x043e, 0x043d, 0x0444,
        0x0438, 0x0433, 0x0443, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f);

    private static final String RU_MAIN_SECTION = RU_COMMAND_INTERFACE + MetadataLanguageUtils.cp(0x043e,
        0x0441, 0x043d, 0x043e, 0x0432, 0x043d, 0x043e, 0x0433, 0x043e, 0x0440, 0x0430, 0x0437, 0x0434, 0x0435,
        0x043b, 0x0430);

    private final Kind kind;
    private final String[] subsystemChain;

    private CommandInterfaceAddress(Kind kind, String[] subsystemChain)
    {
        this.kind = kind;
        this.subsystemChain = subsystemChain;
    }

    /**
     * Parses a command-interface FQN.
     *
     * @param fqn the requested address, may be {@code null}
     * @return the parsed address, or {@code null} when {@code fqn} is not a command-interface address
     */
    public static CommandInterfaceAddress parse(String fqn)
    {
        if (fqn == null)
        {
            return null;
        }
        String trimmed = fqn.trim();
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == trimmed.length() - 1)
        {
            return null;
        }
        String head = trimmed.substring(0, lastDot);
        String tail = trimmed.substring(lastDot + 1).trim();
        if (head.indexOf('.') < 0 && isConfigurationToken(head))
        {
            if (isMainSectionToken(tail))
            {
                return new CommandInterfaceAddress(Kind.MAIN_SECTION, null);
            }
            return isCommandInterfaceToken(tail) ? new CommandInterfaceAddress(Kind.SECTIONS_PANEL, null) : null;
        }
        if (!isCommandInterfaceToken(tail))
        {
            return null;
        }
        String[] chain = SubsystemUtils.parseSubsystemPath(head);
        return chain == null ? null : new CommandInterfaceAddress(Kind.SUBSYSTEM, chain);
    }

    /**
     * Whether the last segment of {@code fqn} names a command interface, whatever its head: lets a
     * caller refuse {@code Catalog.X.CommandInterface} with a pointed message instead of "not found".
     *
     * @param fqn the requested address, may be {@code null}
     * @return {@code true} when the tail token is a command-interface token
     */
    public static boolean hasCommandInterfaceTail(String fqn)
    {
        if (fqn == null)
        {
            return false;
        }
        String trimmed = fqn.trim();
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot <= 0)
        {
            return false;
        }
        String tail = trimmed.substring(lastDot + 1).trim();
        return isCommandInterfaceToken(tail) || isMainSectionToken(tail);
    }

    /** @return which command interface this address names */
    public Kind kind()
    {
        return kind;
    }

    /** @return the subsystem Names from the root down for {@link Kind#SUBSYSTEM}, else {@code null} */
    public String[] subsystemChain()
    {
        return subsystemChain == null ? null : subsystemChain.clone();
    }

    /** @return the owner's canonical English FQN ({@code Subsystem.A.Subsystem.B} or {@code Configuration}) */
    public String ownerFqn()
    {
        return kind == Kind.SUBSYSTEM ? SubsystemUtils.chainFqn(subsystemChain, subsystemChain.length)
            : CONFIGURATION_TOKEN;
    }

    /** @return the canonical English FQN, e.g. {@code Subsystem.Sales.CommandInterface} */
    public String canonicalFqn()
    {
        return ownerFqn() + '.' + (kind == Kind.MAIN_SECTION ? MAIN_SECTION_TOKEN : COMMAND_INTERFACE_TOKEN);
    }

    private static boolean isConfigurationToken(String token)
    {
        String t = token.trim().toLowerCase(Locale.ROOT);
        return t.equals("configuration") || t.equals(RU_CONFIGURATION); //$NON-NLS-1$
    }

    private static boolean isCommandInterfaceToken(String token)
    {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("commandinterface") || t.equals(RU_COMMAND_INTERFACE); //$NON-NLS-1$
    }

    private static boolean isMainSectionToken(String token)
    {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("mainsectioncommandinterface") || t.equals(RU_MAIN_SECTION); //$NON-NLS-1$
    }
}
