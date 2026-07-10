/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.ui;

import org.eclipse.osgi.util.NLS;

/**
 * Messages for the MCP request/response History view. The English
 * {@code messages.properties} is the default/fallback; {@code messages_ru.properties}
 * provides the Russian translation. The locale is auto-selected by the Eclipse/EDT
 * runtime (Platform NL) - no manual locale handling. Only this in-EDT view UI is
 * bilingual; the MCP tool/wire surface stays English-only.
 */
public class Messages extends NLS {

    private static final String BUNDLE_NAME = "com.ditrix.edt.mcp.server.ui.messages"; //$NON-NLS-1$

    public static String McpHistoryView_TabHistory; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_TabStatistics; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ColumnTime; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ColumnMethodTool; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ColumnDuration; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ColumnStatus; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatusOk; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatusError; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_DetailHeader; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_CopyJson; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_CopyJsonTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_DetailNone; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_RequestSection; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ResponseSection; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsToolMethod; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsCalls; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsShare; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsAvg; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsReqChars; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsRespChars; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsTokens; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsErrors; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_StatsContextWeight; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_PauseAction; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_PauseTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ClearAction; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ClearTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterAction; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterMethodLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterMethodTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterMethodPlaceholder; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterErrorsOnlyLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterErrorsOnlyTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterMinDurationLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterMinDurationTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterIntervalLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterIntervalTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_FilterIntervalTo; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_AutoScrollAction; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_AutoScrollTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_RecordingOn; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_RecordingOff; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_UpdatesPaused; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_UpdatesLive; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_ContentDescription; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_Summary; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_Top3None; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpHistoryView_Top3Prefix; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key

    static {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages() {
    }
}
