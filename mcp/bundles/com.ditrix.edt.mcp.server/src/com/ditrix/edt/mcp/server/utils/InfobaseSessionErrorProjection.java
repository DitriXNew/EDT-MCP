/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;

/**
 * Projects only closed-grammar {@code ibcmd} session fields into error payloads.
 *
 * <p>Successful session-list results deliberately do not use this class: they preserve the
 * observed values for the wire-level PII redactor. Error payloads have a stricter contract and
 * omit any value that does not match the exact grammar of the field it claims to be.
 */
public final class InfobaseSessionErrorProjection
{
    private static final Pattern SESSION_ID = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" //$NON-NLS-1$
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"); //$NON-NLS-1$
    private static final Pattern SESSION_NUMBER = Pattern.compile("[0-9]+"); //$NON-NLS-1$
    private static final Pattern TIMESTAMP = Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"); //$NON-NLS-1$

    // These are 1C's documented application identifiers for app-id.
    private static final Set<String> APPLICATION_KINDS = Set.of(
        "1CV8", "1CV8C", "WebClient", "Designer", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "COMConnection", "WSConnection", "BackgroundJob", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "SystemBackgroundJob", "SrvrConsole", "COMConsole", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "JobScheduler", "Debugger", "RAS", "RAC"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private InfobaseSessionErrorProjection()
    {
    }

    /** Whether a caller-supplied selector has one of the two safe identifier grammars. */
    public static boolean isSessionSelector(String value)
    {
        return value != null && (SESSION_ID.matcher(value).matches()
            || SESSION_NUMBER.matcher(value).matches());
    }

    /** Returns the validated fields of one session, preserving the public field order. */
    public static Map<String, Object> fields(SessionInfo session)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        putIfMatches(result, "sessionId", session.sessionId(), SESSION_ID); //$NON-NLS-1$

        Long sessionNumber = session.sessionNumber();
        if (sessionNumber != null && session.sessionNumberText() != null
            && SESSION_NUMBER.matcher(session.sessionNumberText()).matches())
        {
            result.put("sessionNumber", sessionNumber); //$NON-NLS-1$
        }

        String applicationKind = session.applicationKind();
        if (isDocumentedApplicationKind(applicationKind))
        {
            result.put("applicationKind", applicationKind); //$NON-NLS-1$
        }
        putIfMatches(result, "startedAt", session.startedAt(), TIMESTAMP); //$NON-NLS-1$
        putIfMatches(result, "lastActiveAt", session.lastActiveAt(), TIMESTAMP); //$NON-NLS-1$
        return result;
    }

    /** Returns one validated field map per observed session. */
    public static List<Map<String, Object>> fields(List<SessionInfo> sessions)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionInfo session : sessions)
        {
            result.add(fields(session));
        }
        return result;
    }

    /** Formats every validated field as the existing comma-separated error detail. */
    public static Optional<String> details(SessionInfo session)
    {
        List<String> details = new ArrayList<>();
        for (Map.Entry<String, Object> field : fields(session).entrySet())
        {
            details.add(field.getKey() + "=" + field.getValue()); //$NON-NLS-1$
        }
        return details.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", details)); //$NON-NLS-1$
    }

    /** Formats only the UUID/numeric identifier pair used by terminate errors. */
    public static Optional<String> identifier(SessionInfo session)
    {
        Map<String, Object> fields = fields(session);
        Object sessionId = fields.get("sessionId"); //$NON-NLS-1$
        Object sessionNumber = fields.get("sessionNumber"); //$NON-NLS-1$
        if (sessionId != null && sessionNumber != null)
        {
            return Optional.of(sessionId + " (session-id " + sessionNumber + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (sessionId != null)
        {
            return Optional.of(sessionId.toString());
        }
        if (sessionNumber != null)
        {
            return Optional.of("session-id " + sessionNumber); //$NON-NLS-1$
        }
        return Optional.empty();
    }

    private static boolean isDocumentedApplicationKind(String value)
    {
        return value != null && value.chars().allMatch(character -> character < 128)
            && APPLICATION_KINDS.stream().anyMatch(value::equalsIgnoreCase);
    }

    private static void putIfMatches(Map<String, Object> target, String key, String value,
        Pattern grammar)
    {
        if (value != null && grammar.matcher(value).matches())
        {
            target.put(key, value);
        }
    }
}
