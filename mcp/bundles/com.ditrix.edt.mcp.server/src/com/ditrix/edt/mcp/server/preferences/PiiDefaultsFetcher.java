/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.McpConstants;

/**
 * Unattended-safe "Update from repo" fetcher for the Privacy panel's PII rule table.
 * It downloads the maintainer-published default rule table from
 * {@code raw.githubusercontent.com/DitriXNew/EDT-MCP/<ref>/<pii-defaults path>},
 * validates the (untrusted) payload, and hands the parsed rule set back to the UI
 * for the user to <b>Apply explicitly</b>. It NEVER persists anything itself.
 *
 * <p>The network discipline mirrors {@code UpdateChecker}: a plain
 * {@link HttpURLConnection} GET with a bounded {@value #TIMEOUT_MS} ms connect/read
 * timeout, an explicit {@code User-Agent}, {@code disconnect()} in a {@code finally},
 * and the work runs off the UI thread on a daemon background thread. <b>Every</b>
 * failure — network, HTTP status, oversize, malformed JSON, an uncompilable regex, a
 * codec rejection — is caught and returned as a clear {@link FetchResult} error object;
 * nothing is ever thrown to the UI and the call never hangs.
 *
 * <p><b>Untrusted content is size-bounded and per-row {@link Pattern}-compiled before
 * being returned</b> (defence in depth over the codec): the raw payload is capped at
 * {@value #MAX_PAYLOAD_BYTES} bytes, the rule count at {@value #MAX_RULES}, and each
 * rule's {@code regex} is length-bounded ({@value #MAX_PATTERN_LENGTH} chars) and
 * {@code Pattern.compile}-validated, so a hostile mirror cannot smuggle a giant payload
 * or a broken/pathological regex into the live redactor.
 *
 * <p>The authoritative JSON&nbsp;&rarr;&nbsp;rule-set decode is delegated to a
 * {@link RulesetDecoder} the caller supplies — in production the Privacy panel passes
 * the S1 {@code PiiRuleCodec} (e.g. {@code PiiRuleCodec::decode}). Injecting the codec
 * (rather than referencing it directly) keeps this fetcher and its
 * {@code PiiDefaultsFetcherTest} headless and unit-testable without any network or the
 * codec, while the same {@code parseAndValidate} path runs in production.
 *
 * <h2>Expected wire format</h2>
 * The S1 codec shape: a JSON object with a {@code "rules"} array (a bare top-level array
 * is also accepted), each element an object with a required non-empty {@code "regex"}
 * string (the detection pattern, always a regular expression that this fetcher
 * compile-validates) plus the codec's optional {@code "enabled"}/{@code "scope"}/
 * {@code "representation"}/{@code "countable"} fields. The full structural/semantic
 * validation is the injected codec's job; this class only enforces the safety envelope
 * needed for untrusted network content.
 *
 * @param <R> the rule-set type produced by the injected {@link RulesetDecoder}
 *            (the S1 codec's rule-set in production)
 */
public final class PiiDefaultsFetcher
{
    /** Base of the raw GitHub content URL (the ref and path are appended). */
    static final String RAW_BASE_URL = "https://raw.githubusercontent.com/DitriXNew/EDT-MCP/"; //$NON-NLS-1$

    /** Default git ref (branch/tag) the defaults are fetched from when none is given. */
    public static final String DEFAULT_REF = "master"; //$NON-NLS-1$

    /**
     * Repository-relative path of the maintainer-published default rule table.
     * Must match wherever the defaults JSON is published in the repo.
     */
    static final String PII_DEFAULTS_PATH =
        "mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/utils/privacy/pii-defaults.json"; //$NON-NLS-1$

    /** HTTP connect/read timeout (ms) — mirrors {@code UpdateChecker}. */
    static final int TIMEOUT_MS = 10_000;

    /** Hard cap on the downloaded payload (bytes) — an untrusted server must not stream forever. */
    static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    /** Hard cap on the number of rules accepted from the payload. */
    static final int MAX_RULES = 5_000;

    /** Hard cap on a single regex pattern's length (chars) — guards against pathological patterns. */
    static final int MAX_PATTERN_LENGTH = 2_000;

    /** JSON field: the rules array on the root object. */
    private static final String KEY_RULES = "rules"; //$NON-NLS-1$

    /** JSON field: a rule's detection pattern — always a regular expression (the S1 codec schema). */
    private static final String KEY_REGEX = "regex"; //$NON-NLS-1$

    /** Safe character set for a git ref appended into the URL (no path traversal / injection). */
    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9._/-]{1,200}"); //$NON-NLS-1$

    private PiiDefaultsFetcher()
    {
        // Utility class
    }

    /**
     * Decodes a validated JSON rule table into the caller's rule-set type. The seam to
     * the S1 {@code PiiRuleCodec}; production passes {@code PiiRuleCodec::decode}. May
     * throw anything on a structurally/semantically invalid table — the fetcher catches
     * it and shapes it into a {@link FetchResult} error.
     *
     * @param <R> the produced rule-set type
     */
    @FunctionalInterface
    public interface RulesetDecoder<R>
    {
        /**
         * @param json the size-bounded, regex-validated JSON rule table
         * @return the decoded rule set (never {@code null})
         * @throws Exception if the table is structurally or semantically invalid
         */
        R decode(String json) throws Exception; // NOSONAR the codec may throw any checked exception; the fetcher shapes it
    }

    /**
     * Fetches and validates the default rule table off the UI thread on a daemon
     * background thread, then delivers a {@link FetchResult} to {@code onResult}.
     * Never blocks the caller and never throws: the callback always receives a result
     * object (success or a clear error).
     *
     * <p>The callback runs on the background thread; a UI caller must marshal back to the
     * SWT thread itself (e.g. {@code Display.getDefault().asyncExec(...)}).
     *
     * @param ref the git ref (branch/tag) to fetch from; {@code null}/blank ⇒ {@link #DEFAULT_REF}
     * @param decoder the rule-set decoder (the S1 codec in production); may be {@code null}
     *            to validate only
     * @param onResult the result sink, invoked exactly once on the background thread
     * @param <R> the produced rule-set type
     */
    public static <R> void fetchDefaultsAsync(String ref, RulesetDecoder<R> decoder, Consumer<FetchResult<R>> onResult)
    {
        Thread worker = new Thread(() -> {
            FetchResult<R> result;
            try
            {
                result = fetchDefaults(ref, decoder);
            }
            catch (RuntimeException e)
            {
                // Defensive: fetchDefaults already catches everything, but never let the
                // background thread die with an exception the UI never hears about.
                result = FetchResult.error("PII defaults update failed: " //$NON-NLS-1$
                    + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$
            }
            deliver(onResult, result);
        }, "MCP-PII-Defaults-Fetcher"); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Synchronously fetches and validates the default rule table. Safe to call from any
     * background thread (never from the UI thread — it does blocking I/O). All failures
     * are caught and returned as a {@link FetchResult} error; this method never throws.
     *
     * @param ref the git ref (branch/tag); {@code null}/blank ⇒ {@link #DEFAULT_REF}
     * @param decoder the rule-set decoder (may be {@code null} to validate only)
     * @param <R> the produced rule-set type
     * @return the fetched-and-validated result, or a clear error result
     */
    public static <R> FetchResult<R> fetchDefaults(String ref, RulesetDecoder<R> decoder)
    {
        String effectiveRef = (ref == null || ref.trim().isEmpty()) ? DEFAULT_REF : ref.trim();
        if (!SAFE_REF.matcher(effectiveRef).matches())
        {
            return FetchResult.error("Rejected the git ref '" + effectiveRef //$NON-NLS-1$
                + "': it must contain only letters, digits and ._/- characters."); //$NON-NLS-1$
        }

        String spec = RAW_BASE_URL + effectiveRef + "/" + PII_DEFAULTS_PATH; //$NON-NLS-1$
        HttpURLConnection connection = null;
        try
        {
            URL url = new URL(spec); // NOSONAR no non-deprecated equivalent is available on this platform
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET"); //$NON-NLS-1$
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
            connection.setRequestProperty("User-Agent", "EDT-MCP-Plugin/" + McpConstants.PLUGIN_VERSION); //$NON-NLS-1$ //$NON-NLS-2$

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK)
            {
                return FetchResult.error("The repository returned HTTP " + responseCode //$NON-NLS-1$
                    + " for the PII defaults at '" + effectiveRef + "'."); //$NON-NLS-1$ //$NON-NLS-2$
            }

            String body = readBounded(connection);
            if (body == null)
            {
                return FetchResult.error("Rejected: the PII defaults payload exceeds the " //$NON-NLS-1$
                    + MAX_PAYLOAD_BYTES + "-byte limit."); //$NON-NLS-1$
            }
            return parseAndValidate(body, decoder);
        }
        catch (Exception e)
        {
            // Network / protocol errors are never fatal — log meta only and shape an error.
            Activator.logInfo("PII defaults update failed: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
            return FetchResult.error("Could not fetch the PII defaults: " //$NON-NLS-1$
                + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$
        }
        finally
        {
            if (connection != null)
            {
                connection.disconnect();
            }
        }
    }

    /**
     * The pure, headless validation + decode core (unit-tested directly). Enforces the
     * untrusted-content safety envelope on {@code rawJson} — size, shape, rule count, and
     * a per-row regex compile-check — then delegates the authoritative decode to
     * {@code decoder}. Returns a clear {@link FetchResult} error for any problem and
     * never throws.
     *
     * @param rawJson the downloaded (untrusted) JSON rule table
     * @param decoder the rule-set decoder (may be {@code null} to validate only)
     * @param <R> the produced rule-set type
     * @return the validated-and-decoded result, or a clear error result
     */
    static <R> FetchResult<R> parseAndValidate(String rawJson, RulesetDecoder<R> decoder)
    {
        if (rawJson == null || rawJson.trim().isEmpty())
        {
            return FetchResult.error("Rejected: the PII defaults payload was empty."); //$NON-NLS-1$
        }
        if (rawJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES)
        {
            return FetchResult.error("Rejected: the PII defaults payload exceeds the " //$NON-NLS-1$
                + MAX_PAYLOAD_BYTES + "-byte limit."); //$NON-NLS-1$
        }

        JsonElement root;
        try
        {
            root = JsonParser.parseString(rawJson);
        }
        catch (RuntimeException e)
        {
            return FetchResult.error("Rejected: the PII defaults payload is not valid JSON."); //$NON-NLS-1$
        }

        JsonArray rules = extractRules(root);
        if (rules == null)
        {
            return FetchResult.error("Rejected: the PII defaults must be a JSON object with a '" //$NON-NLS-1$
                + KEY_RULES + "' array (or a bare array of rules)."); //$NON-NLS-1$
        }
        if (rules.size() > MAX_RULES)
        {
            return FetchResult.error("Rejected: the PII defaults declare " + rules.size() //$NON-NLS-1$
                + " rules, more than the limit of " + MAX_RULES + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String rowError = validateRows(rules);
        if (rowError != null)
        {
            return FetchResult.error(rowError);
        }

        R ruleset = null;
        if (decoder != null)
        {
            try
            {
                ruleset = decoder.decode(rawJson);
            }
            catch (Exception e) // NOSONAR the codec may throw anything; we must shape it into a result, never rethrow
            {
                return FetchResult.error("Rejected: the PII rule codec could not parse the payload: " //$NON-NLS-1$
                    + e.getMessage());
            }
        }
        return FetchResult.ok(rawJson, ruleset, rules.size());
    }

    /**
     * Extracts the rules array from either a root object with a {@code "rules"} array or a
     * bare top-level array.
     *
     * @return the rules array, or {@code null} when the shape is unexpected
     */
    private static JsonArray extractRules(JsonElement root)
    {
        if (root != null && root.isJsonArray())
        {
            return root.getAsJsonArray();
        }
        if (root != null && root.isJsonObject())
        {
            JsonElement rulesEl = root.getAsJsonObject().get(KEY_RULES);
            if (rulesEl != null && rulesEl.isJsonArray())
            {
                return rulesEl.getAsJsonArray();
            }
        }
        return null; // NOSONAR null is a deliberate "unexpected shape" signal, not an empty collection
    }

    /**
     * Validates every rule row against the S1 codec schema: it must be an object with a
     * non-empty, length-bounded {@code "regex"} string that {@code Pattern.compile}s
     * cleanly. Every rule's {@code regex} is a regular expression (the redactor compiles
     * it regardless of {@code scope}), so it is always compile-checked here.
     *
     * @return an error message for the first offending row, or {@code null} if all rows are safe
     */
    /** Stem of every row-validation rejection message (java:S1192). */
    private static final String ERR_REJECTED_RULE = "Rejected: rule #"; //$NON-NLS-1$

    private static String validateRows(JsonArray rules)
    {
        for (int i = 0; i < rules.size(); i++)
        {
            JsonElement el = rules.get(i);
            if (el == null || !el.isJsonObject())
            {
                return ERR_REJECTED_RULE + (i + 1) + " is not a JSON object."; //$NON-NLS-1$
            }
            JsonObject rule = el.getAsJsonObject();
            String regex = stringField(rule, KEY_REGEX);
            if (regex == null || regex.isEmpty())
            {
                return ERR_REJECTED_RULE + (i + 1) + " has a missing or empty '" //$NON-NLS-1$
                    + KEY_REGEX + "'."; //$NON-NLS-1$
            }
            if (regex.length() > MAX_PATTERN_LENGTH)
            {
                return ERR_REJECTED_RULE + (i + 1) + " has a pattern longer than " //$NON-NLS-1$
                    + MAX_PATTERN_LENGTH + " characters."; //$NON-NLS-1$
            }
            try
            {
                Pattern.compile(regex);
            }
            catch (PatternSyntaxException e)
            {
                return ERR_REJECTED_RULE + (i + 1) + " has an invalid regular expression: " //$NON-NLS-1$
                    + e.getDescription() + "."; //$NON-NLS-1$
            }
        }
        return null; // NOSONAR null means "all rows valid"; not a collection
    }

    /** Reads a string field, or {@code null} when absent / not a string. */
    private static String stringField(JsonObject obj, String key)
    {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
            ? el.getAsString()
            : null;
    }

    /** Reads the connection body into memory, capped at {@link #MAX_PAYLOAD_BYTES}. */
    private static String readBounded(HttpURLConnection connection) throws java.io.IOException
    {
        try (InputStream in = connection.getInputStream())
        {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(chunk)) != -1)
            {
                total += n;
                if (total > MAX_PAYLOAD_BYTES)
                {
                    return null; // NOSONAR null signals "over the size limit" to the caller
                }
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static <R> void deliver(Consumer<FetchResult<R>> onResult, FetchResult<R> result)
    {
        if (onResult == null)
        {
            return;
        }
        try
        {
            onResult.accept(result);
        }
        catch (RuntimeException e)
        {
            Activator.logInfo("PII defaults update result callback failed: " //$NON-NLS-1$
                + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * The immutable outcome of a fetch/validate: either a success carrying the validated
     * raw JSON, the decoded rule set (when a decoder was supplied) and the rule count, or a
     * failure carrying a clear, human-readable message. Never carries an exception.
     *
     * @param <R> the decoded rule-set type
     */
    public static final class FetchResult<R>
    {
        private final boolean ok;
        private final String rawJson;
        private final R ruleset;
        private final int ruleCount;
        private final String errorMessage;

        private FetchResult(boolean ok, String rawJson, R ruleset, int ruleCount, String errorMessage)
        {
            this.ok = ok;
            this.rawJson = rawJson;
            this.ruleset = ruleset;
            this.ruleCount = ruleCount;
            this.errorMessage = errorMessage;
        }

        /**
         * @param rawJson the validated raw JSON payload
         * @param ruleset the decoded rule set (may be {@code null} if validated only)
         * @param ruleCount the number of rules validated
         * @param <R> the decoded rule-set type
         * @return a success result
         */
        static <R> FetchResult<R> ok(String rawJson, R ruleset, int ruleCount)
        {
            return new FetchResult<>(true, rawJson, ruleset, ruleCount, null);
        }

        /**
         * @param message a clear, actionable error description
         * @param <R> the decoded rule-set type
         * @return a failure result
         */
        static <R> FetchResult<R> error(String message)
        {
            return new FetchResult<>(false, null, null, 0, message);
        }

        /** @return {@code true} if the fetch/validate succeeded. */
        public boolean isOk()
        {
            return ok;
        }

        /** @return the validated raw JSON payload, or {@code null} on failure. */
        public String getRawJson()
        {
            return rawJson;
        }

        /**
         * @return the decoded rule set for the caller to Apply explicitly, or {@code null}
         *         on failure or when no decoder was supplied
         */
        public R getRuleset()
        {
            return ruleset;
        }

        /** @return the number of validated rules (0 on failure). */
        public int getRuleCount()
        {
            return ruleCount;
        }

        /** @return the error message on failure, or {@code null} on success. */
        public String getErrorMessage()
        {
            return errorMessage;
        }
    }
}
