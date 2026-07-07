/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.eventlog;

/**
 * One decoded event-log record. Every seq-indexed field is pre-resolved to its display
 * name against the {@link EventLogReferences}; the raw seqs are kept alongside for
 * filtering and as a fallback when a reference row is missing.
 *
 * <p><b>Contains personal data.</b> {@link #user}, {@link #comment} and
 * {@link #dataPresentation} can carry infobase-user names and business-data
 * presentations. A future PII-redactor tool must treat this as sensitive output.</p>
 *
 * <p>Zero EDT/OSGi dependencies - headless-unit-testable.</p>
 */
public final class EventRecord
{
    /** Raw timestamp as stored: {@code YYYYMMDDhhmmss}. */
    public long dateRaw;

    /** ISO rendering of {@link #dateRaw}, e.g. {@code 2026-06-08T12:34:56}. */
    public String dateIso;

    /** Raw transaction status: {@code N}/{@code C}/{@code U}/{@code R}. */
    public String txStatus;

    /** Transaction id as {@code "<hex>/<hex>"}, or {@code null} for the {@code {0,0}} sentinel. */
    public String txId;

    /** Raw user reference index. */
    public int userSeq;

    /** Resolved user display name (may be an empty string), or {@code null} if unresolved. */
    public String user;

    /** Resolved user UUID, or {@code null} if unresolved. */
    public String userUuid;

    /** Raw computer reference index. */
    public int computerSeq;

    /** Resolved computer name. */
    public String computer;

    /** Raw application reference index. */
    public int applicationSeq;

    /** Resolved application name (e.g. {@code 1CV8C}, {@code Designer}). */
    public String application;

    /** Connection id (raw, not a reference index). */
    public long connectionId;

    /** Raw event reference index. */
    public int eventSeq;

    /** Resolved event name (e.g. {@code _$Session$_.Start}). */
    public String event;

    /** Decoded severity: {@code Information}/{@code Warning}/{@code Error}/{@code Note}. */
    public String severity;

    /** Raw one-letter severity code: {@code I}/{@code W}/{@code E}/{@code N}. */
    public String severityCode;

    /** Free-text comment (may carry personal data). */
    public String comment;

    /** Raw metadata reference index. */
    public int metadataSeq;

    /** Resolved metadata full name. */
    public String metadata;

    /** Resolved metadata UUID. */
    public String metadataUuid;

    /** Raw data type code from the {@code {"type", value}} field (e.g. {@code S}, {@code R}, {@code B}). */
    public String dataType;

    /** Raw scalar data value; {@code null} when the data field is nested or absent. */
    public String dataValue;

    /** Data presentation string (may carry personal data). */
    public String dataPresentation;

    /** Raw work-server reference index. */
    public int serverSeq;

    /** Resolved work-server name. */
    public String server;

    /** Raw main-port reference index. */
    public int mainPortSeq;

    /** Resolved main port, or {@code null} if unresolved. */
    public Integer mainPort;

    /** Raw secondary-port reference index. */
    public int secondaryPortSeq;

    /** Resolved secondary port, or {@code null} if unresolved. */
    public Integer secondaryPort;

    /** Session number. */
    public long session;
}
