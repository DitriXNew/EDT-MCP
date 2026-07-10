/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import com.ditrix.edt.mcp.server.utils.privacy.PiiRuleCodec;

/**
 * Plugin preference constants.
 */
public final class PreferenceConstants
{
    /** MCP server port */
    public static final String PREF_PORT = "mcpServerPort"; //$NON-NLS-1$
    
    /** Auto-start on EDT startup */
    public static final String PREF_AUTO_START = "mcpServerAutoStart"; //$NON-NLS-1$
    
    /** Path to check descriptions folder */
    public static final String PREF_CHECKS_FOLDER = "mcpChecksFolder"; //$NON-NLS-1$
    
    /** Plain text mode (Cursor compatibility) - returns text instead of embedded resources */
    public static final String PREF_PLAIN_TEXT_MODE = "mcpPlainTextMode"; //$NON-NLS-1$

    /** Update check interval */
    public static final String PREF_UPDATE_CHECK_INTERVAL = "mcpUpdateCheckInterval"; //$NON-NLS-1$

    /** Update check interval values */
    public static final String UPDATE_CHECK_ON_STARTUP = "on_startup"; //$NON-NLS-1$
    public static final String UPDATE_CHECK_HOURLY    = "hourly"; //$NON-NLS-1$
    public static final String UPDATE_CHECK_DAILY     = "daily"; //$NON-NLS-1$
    public static final String UPDATE_CHECK_NEVER     = "never"; //$NON-NLS-1$

    /** Default update check interval */
    public static final String DEFAULT_UPDATE_CHECK_INTERVAL = UPDATE_CHECK_ON_STARTUP;
    
    /** Default port */
    public static final int DEFAULT_PORT = 8765;
    
    /** Default auto-start */
    public static final boolean DEFAULT_AUTO_START = false;
    
    /** Default checks folder (empty - feature disabled) */
    public static final String DEFAULT_CHECKS_FOLDER = ""; //$NON-NLS-1$
    
    /** Default plain text mode (disabled - use embedded resources by default) */
    public static final boolean DEFAULT_PLAIN_TEXT_MODE = false;
    
    // === Tag decoration preferences ===
    
    /** Show tags in navigator tree */
    public static final String PREF_TAGS_SHOW_IN_NAVIGATOR = "tags.showInNavigator"; //$NON-NLS-1$
    
    /** Tag decoration style */
    public static final String PREF_TAGS_DECORATION_STYLE = "tags.decorationStyle"; //$NON-NLS-1$
    
    /** Decoration style: show all tags as suffix */
    public static final String TAGS_STYLE_SUFFIX = "suffix"; //$NON-NLS-1$
    
    /** Decoration style: show only first tag */
    public static final String TAGS_STYLE_FIRST_TAG = "firstTag"; //$NON-NLS-1$
    
    /** Decoration style: show tag count */
    public static final String TAGS_STYLE_COUNT = "count"; //$NON-NLS-1$
    
    /** Default: show tags in navigator */
    public static final boolean DEFAULT_TAGS_SHOW_IN_NAVIGATOR = true;
    
    /** Default decoration style */
    public static final String DEFAULT_TAGS_DECORATION_STYLE = TAGS_STYLE_SUFFIX;

    // === Tool enablement preferences ===

    /** Comma-separated list of disabled tool names */
    public static final String PREF_DISABLED_TOOLS = "mcpDisabledTools"; //$NON-NLS-1$

    /** Default: all tools enabled (empty string = no disabled tools) */
    public static final String DEFAULT_DISABLED_TOOLS = ""; //$NON-NLS-1$

    // === Progressive tool disclosure (dynamic toolsets) ===

    /**
     * Progressive tool disclosure: when on, {@code tools/list} exposes only the
     * core toolset (plus toolsets enabled at runtime via {@code enable_toolset}),
     * shrinking the always-loaded surface. When off (default), every enabled tool
     * is listed exactly as before.
     */
    public static final String PREF_PROGRESSIVE_DISCLOSURE = "mcpProgressiveDisclosure"; //$NON-NLS-1$

    /** Default: progressive disclosure off (full tool list, no behavior change). */
    public static final boolean DEFAULT_PROGRESSIVE_DISCLOSURE = false;

    // === Security preferences ===

    /** Allow remote (non-loopback) access: bind to all interfaces instead of 127.0.0.1 */
    public static final String PREF_ALLOW_REMOTE_ACCESS = "mcpAllowRemoteAccess"; //$NON-NLS-1$

    /** Shared auth token; an empty value disables authentication */
    public static final String PREF_AUTH_TOKEN = "mcpAuthToken"; //$NON-NLS-1$

    /** Default: loopback-only bind (secure) */
    public static final boolean DEFAULT_ALLOW_REMOTE_ACCESS = false;

    /** Default auth token (empty = authentication disabled) */
    public static final String DEFAULT_AUTH_TOKEN = ""; //$NON-NLS-1$

    // === Destructive-operation consent preferences ===

    /**
     * Consent level for destructive MCP write operations. One of the level tokens
     * below; controls whether the human is asked for consent before a destructive
     * write (see {@code DestructiveConsentGate}).
     */
    public static final String PREF_DESTRUCTIVE_CONSENT_LEVEL = "mcpDestructiveConsentLevel"; //$NON-NLS-1$

    /** Consent level: always ask for consent before any destructive write (default). */
    public static final String CONSENT_LEVEL_ASK_ALWAYS = "ask_always"; //$NON-NLS-1$

    /** Consent level: allow every destructive write without asking. */
    public static final String CONSENT_LEVEL_ALLOW_ALL = "allow_all"; //$NON-NLS-1$

    /** Consent level: ask, except for tools explicitly allowed in {@link #PREF_DESTRUCTIVE_ALLOWED_TOOLS}. */
    public static final String CONSENT_LEVEL_PER_TOOL = "per_tool"; //$NON-NLS-1$

    /** Default consent level: always ask. */
    public static final String DEFAULT_DESTRUCTIVE_CONSENT_LEVEL = CONSENT_LEVEL_ASK_ALWAYS;

    /**
     * Comma-separated list of tool names allowed to run destructively without consent
     * at the {@link #CONSENT_LEVEL_PER_TOOL} level (cloned from {@link #PREF_DISABLED_TOOLS}).
     */
    public static final String PREF_DESTRUCTIVE_ALLOWED_TOOLS = "mcpDestructiveAllowedTools"; //$NON-NLS-1$

    /** Default: no tools allowed without consent (empty string). */
    public static final String DEFAULT_DESTRUCTIVE_ALLOWED_TOOLS = ""; //$NON-NLS-1$

    // === PII redaction preferences ===

    /**
     * Master toggle for the PII redactor: when on, the result of every tool
     * flagged {@code returnsInfobaseData()} is passed through the redactor before it
     * leaves the server. The env kill-switch {@code EDT_MCP_PII_REDACTION}
     * (on/off) overrides this store value (see {@code PiiRedactionSettings}).
     */
    public static final String PREF_PII_REDACTION_ENABLED = "mcpPiiRedactionEnabled"; //$NON-NLS-1$

    /**
     * Default: PII redaction OFF, so infobase tool output is byte-identical to a
     * build without this feature until the user (or the env switch) turns it on.
     */
    public static final boolean DEFAULT_PII_REDACTION_ENABLED = false;

    /**
     * The user-configurable PII rule table, serialized as JSON. It replaces the old
     * hard-coded detection: the attribute-name dictionary and the content-regex
     * backstop are now driven by this table so an operator can add, remove or retune
     * rules from the Privacy preferences panel without a rebuild. The value is parsed
     * by {@code PiiRuleCodec}; {@link PiiRuleSettings} resolves it (with caching and a
     * headless default fallback) for the redactor.
     */
    public static final String PREF_PII_RULES_JSON = "mcpPiiRules"; //$NON-NLS-1$

    /**
     * Default rule table: the bundled default rules serialized to JSON
     * ({@link PiiRuleCodec#loadBundledDefaults()} re-encoded via {@code PiiRuleCodec.encode}),
     * which reproduce the previous hard-coded
     * PII detection. The packaged {@code pii-defaults.json} is the single source of
     * truth; this constant is its serialized form so an untouched store, and a headless
     * context with no store, both resolve to the same bundled rule set.
     */
    public static final String DEFAULT_PII_RULES_JSON =
        PiiRuleCodec.encode(PiiRuleCodec.loadBundledDefaults());

    /**
     * Salt for the PII pseudonymiser HMAC. When non-empty, the pseudonym tokens become
     * stable across server runs (deterministic key); the empty default keeps the
     * per-run random key, so tokens are stable within a run but NOT linkable across
     * runs. Resolved by {@link PiiRuleSettings#currentSalt()}.
     */
    public static final String PREF_PII_SALT = "mcpPiiSalt"; //$NON-NLS-1$

    /** Default salt (empty): keep the per-run random pseudonymiser key. */
    public static final String DEFAULT_PII_SALT = ""; //$NON-NLS-1$

    private PreferenceConstants()
    {
        // Utility class
    }
}