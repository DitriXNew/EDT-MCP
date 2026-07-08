/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import org.eclipse.osgi.util.NLS;

/**
 * Messages for the MCP Server preference page (settings form). The English
 * {@code messages.properties} is the default/fallback; {@code messages_ru.properties}
 * provides the Russian translation. The locale is auto-selected by the Eclipse/EDT
 * runtime (Platform NL) - no manual locale handling.
 */
public class Messages extends NLS {

    private static final String BUNDLE_NAME = "com.ditrix.edt.mcp.server.preferences.messages"; //$NON-NLS-1$

    // McpServerPreferencePage
    public static String McpServerPreferencePage_Description; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpServerPreferencePage_TabGeneral; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpServerPreferencePage_TabTools; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String McpServerPreferencePage_TabPrivacy; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key

    // GeneralTab
    public static String GeneralTab_ServerPort; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_AutoStart; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_AllowRemote; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_AuthToken; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_ChecksFolder; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_Browse; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_SelectChecksFolder; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_PlainTextMode; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_PlainTextMode_Tooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_ShowTags; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_TagDecorationStyle; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_DestructiveOperations; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_DestructiveOperations_Tooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_CheckForUpdates; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_CheckNow; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_Checking; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_NewReleaseAvailable; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_UpToDate; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_ServerControl; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_Status; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_Start; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_Stop; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_Restart; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_Endpoint; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_RunningOnPort; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_Stopped; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_StartFailedTitle; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_StartFailedMessage; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_RestartFailedTitle; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_RestartFailedMessage; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_TagStyle_AllTagsSuffix; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_TagStyle_FirstTagOnly; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_TagStyle_TagCount; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_ConsentLevel_AskAlways; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_ConsentLevel_AllowAll; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_ConsentLevel_PerTool; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_UpdateInterval_OnStartup; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_UpdateInterval_Hourly; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_UpdateInterval_Daily; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String GeneralTab_UpdateInterval_Never; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key

    // ToolsTab
    public static String ToolsTab_EnableAllTools; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_AllFallback; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_DisableAllTools; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_NoneFallback; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_Preset; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_PresetTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_SelectHint; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_Settings; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_ParamTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_RestoreDefaults; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_AllowDestructive; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_AllowDestructive_Tooltip_Enabled; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_AllowDestructive_Tooltip_Disabled; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String ToolsTab_CountLabel; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key

    // PrivacyTab
    public static String PrivacyTab_MasterToggle; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_MasterToggle_Tooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_Salt; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_Salt_Tooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_RulesGroup; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_ColumnEnabled; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_ColumnRegex; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_ColumnSuffix; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_ColumnCountable; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_ColumnScope; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_Add; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_Edit; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_Remove; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_LoadDefaults; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_LoadDefaults_Tooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_UpdateFromRepo; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_UpdateFromRepo_Tooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_Updating; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_UpdateFailedMessage; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_UpdateStaged; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PrivacyTab_DefaultsLoaded; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key

    // PiiRuleDialog
    public static String PiiRuleDialog_TitleAdd; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PiiRuleDialog_TitleEdit; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PiiRuleDialog_Enabled; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PiiRuleDialog_Regex; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PiiRuleDialog_Suffix; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PiiRuleDialog_Countable; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PiiRuleDialog_Scope; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PiiRuleDialog_InvalidRegexTitle; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PiiRuleDialog_InvalidRegexMessage; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String PiiRuleDialog_EmptyRegexMessage; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key

    static {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages() {
    }
}
