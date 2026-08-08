/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

/**
 * Renders an EDT-log line for a git failure that may have come out of JGit's configuration parser:
 * what failed, and the exception TYPES behind it - never their messages.
 * <p>
 * A JGit configuration error can carry the configuration itself. An {@code [include]} entry whose key
 * is not {@code path} is reported as {@code Invalid line in config file: <ConfigLine>}, and
 * {@code ConfigLine.toString()} renders {@code section.subsection.name=value} - the VALUE included;
 * a broken section header is reported as {@code Bad section entry: <name>}. Opening a repository
 * loads the repository, user and system configuration, and when the failing file is the USER one
 * that exception arrives with the offending line still in its cause chain - and the EDT error log is
 * permanent: handing the throwable to it would move a credential leak from the MCP response into a
 * file rather than close it. (For this repository's own config JGit re-formats the failure into a
 * message naming the FILE and drops the cause - which is precisely why the guarantee may not rest on
 * which file happened to be at fault.)
 * <p>
 * A class name can carry nothing, so the cause chain is rendered by TYPE.
 * <p>
 * This bounds what THIS plug-in writes, and nothing more: JGit logs a malformed user configuration
 * through its own SLF4J logger before it throws, and where that entry ends up is the platform's
 * business. The point here is not to add a second copy of it.
 */
public final class GitFailureLog
{
    /** How many links of a cause chain {@link #typesOnly} names; a chain can be cyclic. */
    private static final int MAX_CAUSE_DEPTH = 5;

    private GitFailureLog()
    {
        // Utility class
    }

    /**
     * Builds the log line: {@code <what> (<type> <- <cause type> ...)} plus the note that the message
     * was withheld deliberately.
     *
     * @param what what failed, in the caller's own words - never platform text (may be {@code null})
     * @param failure the exception behind it (may be {@code null})
     * @return the message to log; it embeds no configuration content
     */
    public static String typesOnly(String what, Throwable failure)
    {
        StringBuilder types = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++)
        {
            if (depth > 0)
            {
                types.append(" <- "); //$NON-NLS-1$
            }
            types.append(current.getClass().getName());
            current = current.getCause();
        }
        return what + " (" + types //$NON-NLS-1$
            + "). The exception message is withheld on purpose: it can quote the configuration, " //$NON-NLS-1$
            + "credential values included."; //$NON-NLS-1$
    }
}
