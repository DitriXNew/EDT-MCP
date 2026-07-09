/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.eventlog.EventLogLocator;
import com.ditrix.edt.mcp.server.utils.eventlog.EventLogQuery;
import com.ditrix.edt.mcp.server.utils.eventlog.EventLogReader;
import com.ditrix.edt.mcp.server.utils.eventlog.EventLogReader.EventLogException;
import com.ditrix.edt.mcp.server.utils.eventlog.EventRecord;

/**
 * Reads a 1C infobase event log WITHOUT a running 1C session,
 * by parsing the raw legacy text log files (a {@code 1Cv8.lgf} references dictionary
 * plus dated {@code *.lgp} record partitions, "ver 2.0" text format).
 *
 * <p>The log directory is resolved reflectively from a project + (optional) application
 * id — a FILE infobase's {@code <db>/1Cv8Log} — or supplied directly via {@code logDir}
 * (an offline copy, or a SERVER-mode infobase whose cluster path is not model-derivable).
 * Results are filtered (period / user / event / severity / comment / metadata / session)
 * and paginated.
 *
 * <p>Read-only ({@code get_} prefix &rarr; {@code readOnlyHint} via
 * {@link com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier}); no model
 * transaction, no session, no UI — a pure file read.
 *
 * <p><b>PII:</b> the event log CONTAINS personal data (user names, data presentations).
 * This is a {@code returnsInfobaseData} tool for the future PII-redactor (#242); it does
 * NOT redact here.
 *
 * <h2>Seam contract (this tool is the shell over two collaborators)</h2>
 * <ul>
 * <li>{@link EventLogLocator#resolve(String, String, String)} &rarr;
 *     {@link EventLogLocator.Resolution} — the reflective log-directory resolver. On a
 *     resolution failure {@link EventLogLocator.Resolution#getError()} carries a ready
 *     {@link ToolResult} error JSON (SERVER without {@code logDir}, ambiguous infobase,
 *     no {@code 1Cv8Log}, closed/missing project) which this tool returns verbatim.</li>
 * <li>{@link EventLogReader#read(Path, EventLogQuery)} &rarr; {@link EventLogReader.Page}
 *     — the pure parser. It throws {@link EventLogException} with an actionable message
 *     for an unreadable / unsupported log (e.g. a modern SQLite {@code .lgd} with no
 *     {@code 1Cv8.lgf}); this tool converts that to a {@link ToolResult} error.</li>
 * </ul>
 */
public class GetEventLogTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "get_event_log"; //$NON-NLS-1$

    // --- Input parameter names (lowerCamelCase; every one is declared in getInputSchema) ---

    /** Input param: absolute path to a {@code 1Cv8Log} directory (override / SERVER / offline). */
    private static final String KEY_LOG_DIR = "logDir"; //$NON-NLS-1$

    /** Input param: period lower bound (ISO-8601). */
    private static final String KEY_FROM = "from"; //$NON-NLS-1$

    /** Input param: period upper bound (ISO-8601). */
    private static final String KEY_TO = "to"; //$NON-NLS-1$

    /** Input param: user-name filter (array: JSON array or CSV). */
    private static final String KEY_USER = "user"; //$NON-NLS-1$

    /** Input param: exact event id/name filter (scalar). */
    private static final String KEY_EVENT = "event"; //$NON-NLS-1$

    /** Input param: case-insensitive substring match on the event name (scalar). */
    private static final String KEY_EVENT_CONTAINS = "eventContains"; //$NON-NLS-1$

    /** Input param: severity filter (array: I/W/E/N or their en/ru words). */
    private static final String KEY_SEVERITY = "severity"; //$NON-NLS-1$

    /** Input param: case-insensitive substring match on the comment (scalar). */
    private static final String KEY_COMMENT_CONTAINS = "commentContains"; //$NON-NLS-1$

    /** Input param: case-insensitive substring match on the metadata full name (scalar). */
    private static final String KEY_METADATA_CONTAINS = "metadataContains"; //$NON-NLS-1$

    /** Input param: session-number filter (scalar). */
    private static final String KEY_SESSION = "session"; //$NON-NLS-1$

    /** Input param: number of matching events to skip (pagination). */
    private static final String KEY_OFFSET = "offset"; //$NON-NLS-1$

    /** Input param: result order by date. */
    private static final String KEY_ORDER = "order"; //$NON-NLS-1$

    /** {@code order} value: oldest first (default). */
    private static final String ORDER_DATE_ASC = "date_asc"; //$NON-NLS-1$

    /** {@code order} value: newest first. */
    private static final String ORDER_DATE_DESC = "date_desc"; //$NON-NLS-1$

    // --- Output result keys ---

    /** Output key: the {@code 1Cv8Log} directory that was actually read. */
    private static final String KEY_RESOLVED_LOG_DIR = "resolvedLogDir"; //$NON-NLS-1$

    /** Output key: how the log directory was located ({@code FILE}/{@code SERVER}/{@code OVERRIDE}). */
    private static final String KEY_INFOBASE_TYPE = "infobaseType"; //$NON-NLS-1$

    /** Output key: the detected log format (e.g. {@code text-2.0}). */
    private static final String KEY_FORMAT = "format"; //$NON-NLS-1$

    /**
     * The only log format {@link EventLogReader} can read: the legacy text "ver 2.0"
     * ({@code 1Cv8.lgf} + {@code *.lgp}). A successful read implies this format - the reader
     * throws an {@link EventLogException} for anything else (e.g. the SQLite {@code .lgd}).
     */
    private static final String FORMAT_TEXT_2_0 = "text-2.0"; //$NON-NLS-1$

    /** Output key: total number of records that matched the filters across all scanned partitions. */
    private static final String KEY_MATCHED = "matched"; //$NON-NLS-1$

    /** Output key: total number of records scanned (before filtering / cap). */
    private static final String KEY_SCANNED = "scanned"; //$NON-NLS-1$

    /** Output key: number of events actually returned on this page. */
    private static final String KEY_RETURNED = "returned"; //$NON-NLS-1$

    /** Output key: whether the scan hit its cap and stopped early (matched/scanned are then lower bounds). */
    private static final String KEY_TRUNCATED = "truncated"; //$NON-NLS-1$

    /** Output key: the page of parsed events. */
    private static final String KEY_EVENTS = "events"; //$NON-NLS-1$

    // --- Per-event output keys (structuredContent events[]) ---

    private static final String EV_DATE = "date"; //$NON-NLS-1$
    private static final String EV_SEVERITY = "severity"; //$NON-NLS-1$
    private static final String EV_SEVERITY_PRESENTATION = "severityPresentation"; //$NON-NLS-1$
    private static final String EV_USER = "user"; //$NON-NLS-1$
    private static final String EV_COMPUTER = "computer"; //$NON-NLS-1$
    private static final String EV_APPLICATION = "application"; //$NON-NLS-1$
    private static final String EV_EVENT = "event"; //$NON-NLS-1$
    private static final String EV_COMMENT = "comment"; //$NON-NLS-1$
    private static final String EV_METADATA = "metadata"; //$NON-NLS-1$
    private static final String EV_METADATA_UUID = "metadataUuid"; //$NON-NLS-1$
    private static final String EV_SESSION = "session"; //$NON-NLS-1$
    private static final String EV_DATA = "data"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read a 1C infobase event log WITHOUT a running 1C session by " //$NON-NLS-1$
            + "parsing the raw log files (legacy text ver 2.0: a 1Cv8.lgf dictionary + dated *.lgp " //$NON-NLS-1$
            + "partitions). High diagnostic value: errors, user actions, posting, authentication. Locate " //$NON-NLS-1$
            + "the log by projectName (+ optional applicationId from get_applications for a FILE " //$NON-NLS-1$
            + "infobase), or pass logDir directly for an offline copy or a SERVER-mode infobase. Filters: " //$NON-NLS-1$
            + "from/to period, user, event, eventContains, severity, commentContains, metadataContains, " //$NON-NLS-1$
            + "session; paginated (limit/offset/order). Returns infobase PII (user names, data " //$NON-NLS-1$
            + "presentations). Full parameters and examples: call get_tool_guide('get_event_log')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT configuration project whose infobase event log to read. Provide this (optionally " //$NON-NLS-1$
                + "with applicationId) OR logDir.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application id from get_applications, to pick ONE infobase when the project has " //$NON-NLS-1$
                + "several. Optional; ignored when logDir is given.") //$NON-NLS-1$
            .stringProperty(KEY_LOG_DIR,
                "Absolute path to a 1Cv8Log directory to read directly (an offline copy, or a " //$NON-NLS-1$
                + "SERVER-mode infobase whose cluster path is not model-derivable). Overrides " //$NON-NLS-1$
                + "projectName/applicationId resolution.") //$NON-NLS-1$
            .stringProperty(KEY_FROM,
                "Period lower bound, ISO-8601 (e.g. 2026-04-01 or 2026-04-01T09:00:00). Omit for no " //$NON-NLS-1$
                + "lower bound.") //$NON-NLS-1$
            .stringProperty(KEY_TO,
                "Period upper bound, ISO-8601. Omit for no upper bound.") //$NON-NLS-1$
            .stringArrayProperty(KEY_USER,
                "Filter by user name(s) (exact). JSON array [\"Ivanov\"] or comma-separated.") //$NON-NLS-1$
            .stringProperty(KEY_EVENT,
                "Filter by exact event id/name (e.g. _$Session$_.Authentication). Use eventContains " //$NON-NLS-1$
                + "for a substring match instead.") //$NON-NLS-1$
            .stringProperty(KEY_EVENT_CONTAINS,
                "Case-insensitive substring match on the event name.") //$NON-NLS-1$
            .stringArrayProperty(KEY_SEVERITY,
                "Filter by severity. Accepts I/W/E/N, the English words Information/Warning/Error/Note, " //$NON-NLS-1$
                + "or their Russian equivalents. JSON array or comma-separated.") //$NON-NLS-1$
            .stringProperty(KEY_COMMENT_CONTAINS,
                "Case-insensitive substring match on the event comment.") //$NON-NLS-1$
            .stringProperty(KEY_METADATA_CONTAINS,
                "Case-insensitive substring match on the metadata full name as stored in the log " //$NON-NLS-1$
                + "(e.g. Document.SalesOrder), not a localized synonym.") //$NON-NLS-1$
            .stringProperty(KEY_SESSION,
                "Filter by session number.") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT,
                "Max events to return (default " + Pagination.DEFAULT_LIMIT + ", max " //$NON-NLS-1$ //$NON-NLS-2$
                + Pagination.MAX_LIMIT + ").") //$NON-NLS-1$
            .integerProperty(KEY_OFFSET,
                "Number of matching events to skip before this page (pagination; default 0).") //$NON-NLS-1$
            .enumProperty(KEY_ORDER,
                "Result order by date: date_asc (oldest first, default) or date_desc (newest first).", //$NON-NLS-1$
                ORDER_DATE_ASC, ORDER_DATE_DESC)
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the read succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_RESOLVED_LOG_DIR, "The 1Cv8Log directory that was actually read.") //$NON-NLS-1$
            .stringProperty(KEY_INFOBASE_TYPE,
                "How the log was located: FILE (resolved from the project), SERVER, or OVERRIDE (logDir).") //$NON-NLS-1$
            .stringProperty(KEY_FORMAT, "The detected log format (e.g. text-2.0).") //$NON-NLS-1$
            .integerProperty(KEY_MATCHED, "Total records matching the filters across scanned partitions.") //$NON-NLS-1$
            .integerProperty(KEY_SCANNED, "Total records scanned before filtering.") //$NON-NLS-1$
            .integerProperty(KEY_RETURNED, "Number of events returned on this page.") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT, "The effective page size (after clamping to [1, max]).") //$NON-NLS-1$
            .integerProperty(KEY_OFFSET, "The offset applied.") //$NON-NLS-1$
            .booleanProperty(KEY_TRUNCATED,
                "True if the scan hit its cap and stopped early (matched/scanned are lower bounds).") //$NON-NLS-1$
            .objectArrayProperty(KEY_EVENTS,
                "The page of events: {date, severity, severityPresentation, user, computer, application, " //$NON-NLS-1$
                + "event, comment, metadata, metadataUuid, session, data}.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        String logDir = JsonUtils.extractStringArgument(params, KEY_LOG_DIR);

        // Early validation (headless-testable: returns before any EDT / file access).
        boolean hasProject = projectName != null && !projectName.isBlank();
        boolean hasLogDir = logDir != null && !logDir.isBlank();
        if (!hasProject && !hasLogDir)
        {
            return ToolResult.error("Provide projectName (an EDT configuration project - use " //$NON-NLS-1$
                + "list_projects) or logDir (a path to a 1Cv8Log directory) to locate the event log.") //$NON-NLS-1$
                .toJson();
        }

        String order = normalizeOrder(JsonUtils.extractStringArgument(params, KEY_ORDER));
        if (order == null)
        {
            String bad = JsonUtils.extractStringArgument(params, KEY_ORDER);
            return ToolResult.error("Invalid order: '" + bad + "'. Must be one of: " //$NON-NLS-1$ //$NON-NLS-2$
                + ORDER_DATE_ASC + ", " + ORDER_DATE_DESC + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Parse the scalar session filter up front so a malformed value fails fast (before any file
        // access) with an actionable error instead of silently matching every session. EventLogQuery
        // keeps a List<Long> (a future multi-session filter); the tool exposes a single value.
        String sessionRaw = JsonUtils.extractStringArgument(params, KEY_SESSION);
        List<Long> sessionFilter = null;
        if (sessionRaw != null && !sessionRaw.isBlank())
        {
            try
            {
                sessionFilter = List.of(Long.valueOf(sessionRaw.trim()));
            }
            catch (NumberFormatException e)
            {
                return ToolResult.error("Invalid session: '" + sessionRaw + "'. It must be an " //$NON-NLS-1$ //$NON-NLS-2$
                    + "integer session number.").toJson(); //$NON-NLS-1$
            }
        }

        int limit = Pagination.clampLimit(
            JsonUtils.extractIntArgument(params, McpKeys.LIMIT, Pagination.DEFAULT_LIMIT),
            Pagination.MAX_LIMIT);
        int offset = Math.max(0, JsonUtils.extractIntArgument(params, KEY_OFFSET, 0));

        // Build the filter/pagination query - Dev A. EventLogQuery exposes fluent setters for the
        // collection filters (user/event/severity/session take lists) and public fields for the scalar
        // substring / pagination knobs. The tool's event filter is a single exact value, wrapped here
        // into the one-element list the query expects. Everything is validated BEFORE the reflective
        // resolver runs, so a bad argument fails fast (headless) without touching EDT or the filesystem.
        String eventRaw = JsonUtils.extractStringArgument(params, KEY_EVENT);
        EventLogQuery query = new EventLogQuery()
            .user(JsonUtils.extractArrayArgument(params, KEY_USER))
            .event(eventRaw == null || eventRaw.isBlank() ? null : List.of(eventRaw))
            .severity(JsonUtils.extractArrayArgument(params, KEY_SEVERITY))
            .session(sessionFilter);
        query.eventContains = JsonUtils.extractStringArgument(params, KEY_EVENT_CONTAINS);
        query.commentContains = JsonUtils.extractStringArgument(params, KEY_COMMENT_CONTAINS);
        query.metadataContains = JsonUtils.extractStringArgument(params, KEY_METADATA_CONTAINS);
        query.descending = ORDER_DATE_DESC.equals(order);
        query.limit = limit;
        query.offset = offset;

        // Period bounds are optional: skip an omitted bound (the query keeps its unbounded default)
        // and turn a malformed ISO value into an actionable error rather than letting the ISO parse
        // throw an uncaught NumberFormatException / NPE (unattended-safety, CLAUDE.md #8).
        String fromRaw = JsonUtils.extractStringArgument(params, KEY_FROM);
        if (fromRaw != null && !fromRaw.isBlank())
        {
            try
            {
                query.from(fromRaw);
            }
            catch (RuntimeException e)
            {
                return badPeriod(KEY_FROM, fromRaw);
            }
        }
        String toRaw = JsonUtils.extractStringArgument(params, KEY_TO);
        if (toRaw != null && !toRaw.isBlank())
        {
            try
            {
                query.to(toRaw);
            }
            catch (RuntimeException e)
            {
                return badPeriod(KEY_TO, toRaw);
            }
        }

        // Resolve the 1Cv8Log directory (reflective, or the logDir override) - Dev B.
        EventLogLocator.Resolution resolution =
            EventLogLocator.resolve(projectName, applicationId, logDir);
        if (resolution.getError() != null)
        {
            return resolution.getError();
        }
        Path resolvedDir = resolution.getLogDir();
        String infobaseType = resolution.getInfobaseType();

        // Read + parse the log - Dev A. An unreadable / unsupported log (e.g. a modern SQLite .lgd
        // with no 1Cv8.lgf) surfaces as an actionable EventLogException message.
        EventLogReader.Page page;
        try
        {
            page = new EventLogReader().read(resolvedDir, query);
        }
        catch (EventLogException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }

        List<Map<String, Object>> events = new ArrayList<>();
        for (EventRecord record : page.records)
        {
            events.add(toEventMap(record));
        }

        return ToolResult.success()
            .put(KEY_RESOLVED_LOG_DIR, resolvedDir.toString())
            .put(KEY_INFOBASE_TYPE, infobaseType)
            .put(KEY_FORMAT, FORMAT_TEXT_2_0)
            .put(KEY_MATCHED, page.matchedTotal)
            .put(KEY_SCANNED, page.scanned)
            .put(KEY_RETURNED, events.size())
            .put(McpKeys.LIMIT, limit)
            .put(KEY_OFFSET, offset)
            .put(KEY_TRUNCATED, page.truncated)
            .put(KEY_EVENTS, events)
            .toJson();
    }

    /**
     * Validates the {@code order} parameter. Returns {@link #ORDER_DATE_ASC} when absent/blank
     * (the default), the trimmed value when it is one of the two accepted tokens, or {@code null}
     * when it is an out-of-set value (the caller then returns an actionable error).
     *
     * @param raw the raw {@code order} argument (may be {@code null})
     * @return the canonical order, or {@code null} when the value is invalid
     */
    private static String normalizeOrder(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return ORDER_DATE_ASC;
        }
        String v = raw.trim();
        if (ORDER_DATE_ASC.equals(v) || ORDER_DATE_DESC.equals(v))
        {
            return v;
        }
        return null;
    }

    /**
     * Builds the actionable error for a {@code from}/{@code to} bound that is not a valid
     * ISO-8601 date/datetime (the ISO parse would otherwise throw an uncaught exception).
     *
     * @param which the offending parameter name ({@code from} / {@code to})
     * @param value the offending raw value (echoed so the caller can fix it)
     * @return the {@link ToolResult} error JSON
     */
    private static String badPeriod(String which, String value)
    {
        return ToolResult.error("Invalid " + which + ": '" + value //$NON-NLS-1$ //$NON-NLS-2$
            + "'. Use ISO-8601: YYYY-MM-DD (e.g. 2026-04-01) or YYYY-MM-DDThh:mm:ss " //$NON-NLS-1$
            + "(e.g. 2026-04-01T09:00:00).").toJson(); //$NON-NLS-1$
    }

    /**
     * Maps one parsed {@link EventRecord} to its {@code structuredContent} shape. A {@code null}
     * field is carried as a {@code null} map value (Gson omits it from the JSON), so a partially
     * decoded record still renders cleanly.
     *
     * @param record the parsed record
     * @return an ordered map with the per-event output keys
     */
    private static Map<String, Object> toEventMap(EventRecord record)
    {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put(EV_DATE, record.dateIso);
        ev.put(EV_SEVERITY, record.severityCode);
        ev.put(EV_SEVERITY_PRESENTATION, record.severity);
        ev.put(EV_USER, record.user);
        ev.put(EV_COMPUTER, record.computer);
        ev.put(EV_APPLICATION, record.application);
        ev.put(EV_EVENT, record.event);
        ev.put(EV_COMMENT, record.comment);
        ev.put(EV_METADATA, record.metadata);
        ev.put(EV_METADATA_UUID, record.metadataUuid);
        ev.put(EV_SESSION, record.session);
        ev.put(EV_DATA, formatData(record));
        return ev;
    }

    /**
     * Renders the record's data field to a single string. The decoded scalar value is preferred
     * (e.g. an authentication error message stored as {@code {"S","bad password"}} surfaces as
     * {@code bad password}); a nested reference - whose scalar value is {@code null} - falls back
     * to its data presentation (e.g. an object caption like {@code Order #1}). Returns {@code null}
     * when the record carried no data (the {@code {0}} "no data" sentinel), so Gson omits the key
     * rather than emitting an empty string.
     *
     * @param record the parsed record
     * @return the data value / presentation, or {@code null} when there is no data
     */
    private static String formatData(EventRecord record)
    {
        if (record.dataValue != null)
        {
            return record.dataValue;
        }
        if (record.dataPresentation != null && !record.dataPresentation.isEmpty())
        {
            return record.dataPresentation;
        }
        return null;
    }
}
