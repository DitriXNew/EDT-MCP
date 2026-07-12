/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PiiRedactionSettings;
import com.ditrix.edt.mcp.server.preferences.PiiRuleSettings;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * The PII redactor invoked at the single wire-serialization choke point
 * ({@code McpProtocolHandler}) on every tool result. It is a strict no-op unless
 * redaction is enabled AND the tool is flagged {@link IMcpTool#returnsInfobaseData()};
 * in every skip case it returns the SAME {@link String} reference it was given, so an
 * OFF / not-flagged / error / no-PII result stays byte-identical (the golden and the
 * flagged tools' {@code assert_no_diff} e2e stay green when redaction is off).
 * <p>
 * Detection is entirely data-driven: the hard-coded v1 name dictionary and content
 * patterns are gone, replaced by a user-configurable {@link PiiRuleSet}. The engine
 * walks the JSON tree and evaluates the enabled rules over each string leaf:
 * <ul>
 * <li>a {@link PiiRuleScope#NAME} (or {@link PiiRuleScope#BOTH}) rule whose regex
 * matches the enclosing JSON KEY - or, for the canonical {@code value} field of a
 * variable/evaluate DTO, its sibling {@code name} - replaces the WHOLE value with the
 * rule's token;</li>
 * <li>a {@link PiiRuleScope#VALUE} (or {@code BOTH}) rule regex-replaces every matching
 * SPAN inside the string content.</li>
 * </ul>
 * The token per rule comes from {@link Pseudonymizer#token}: a {@code countable} rule
 * emits a stable {@code representation#hmac} pseudonym, a non-countable rule emits the
 * {@code representation} verbatim (a flat mask such as {@code [redacted]}). Every rule's
 * pattern is compiled once per pass and GUARDED: a syntactically invalid row is skipped,
 * never thrown, so one bad user rule cannot break redaction of the others.
 * <p>
 * The package is pure and headless: {@link #apply(String, PiiRuleSet, String)} takes an
 * explicit rule set and salt so the whole engine is unit-testable without OSGi; the
 * production entry {@link #redactIfEnabled} resolves the enabled decision from
 * {@link PiiRedactionSettings} and the active rule table and salt from
 * {@link PiiRuleSettings} (the user-edited set, cached, with a bundled-default fallback).
 */
public final class PiiRedactor
{
    /** Canonical ToolResult success/error flag. */
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$

    /** The DTO field holding a variable's / evaluation's serialized value. */
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$

    /** The DTO field holding a variable's name (sibling classifier for {@code value}). */
    private static final String KEY_NAME = "name"; //$NON-NLS-1$

    /** DTO field: value was truncated (describes the ORIGINAL value length). */
    private static final String KEY_TRUNCATED = "truncated"; //$NON-NLS-1$

    /** DTO field: original length before truncation. */
    private static final String KEY_FULL_LENGTH = "fullLength"; //$NON-NLS-1$

    /** Fallback tool name for the audit line when a tool reports none. */
    private static final String UNKNOWN_TOOL = "<unknown>"; //$NON-NLS-1$

    private PiiRedactor()
    {
        // Utility class
    }

    /**
     * The choke-point entry: redacts {@code result} when redaction is enabled and
     * {@code tool} is flagged {@link IMcpTool#returnsInfobaseData()}, otherwise returns
     * the same reference. The enabled decision (preference plus the
     * {@code EDT_MCP_PII_REDACTION} env kill-switch) is resolved by
     * {@link PiiRedactionSettings#isEnabled()}; the active rule table and the pseudonym
     * salt are resolved (user-edited value, cached, with a bundled-default fallback) by
     * {@link PiiRuleSettings}, so an operator's edits in the Privacy panel take effect on
     * the next tool result.
     *
     * @param tool the tool that produced the result (may be {@code null})
     * @param params the tool call parameters (reserved for the deferred per-infobase PII
     *            flag; unused in v1)
     * @param result the tool's serialized result (may be {@code null})
     * @return the redacted JSON, or the original {@code result} reference when nothing
     *         is redacted
     */
    public static String redactIfEnabled(IMcpTool tool, Map<String, String> params, String result) // NOSONAR params is reserved for the deferred per-infobase PII flag; the wire contract passes it
    {
        if (result == null || !PiiRedactionSettings.isEnabled())
        {
            return result;
        }
        if (tool == null || !tool.returnsInfobaseData())
        {
            return result;
        }
        if (isJsonErrorPayload(result))
        {
            return result;
        }
        Outcome outcome = redact(result, PiiRuleSettings.currentRuleSet(), PiiRuleSettings.currentSalt());
        if (outcome.count > 0)
        {
            logAudit(toolName(tool), outcome.count);
        }
        return outcome.text;
    }

    /**
     * The pure, headless-testable core. Evaluates {@code ruleSet} (with an HMAC key
     * derived from {@code salt}) over the JSON tree, redacting personal data. Returns the
     * SAME {@code result} reference whenever nothing is masked - an OFF / empty rule set,
     * a non-JSON body, or a zero-match walk all short-circuit to the original string
     * (never a no-op re-serialization); otherwise returns the re-serialized, redacted tree.
     *
     * @param result the tool's serialized result (may be {@code null})
     * @param ruleSet the detection rules (may be {@code null} / empty - then a no-op)
     * @param salt the pseudonym salt (blank / {@code null} = per-pass random key)
     * @return the redacted JSON, or the original {@code result} reference
     */
    public static String apply(String result, PiiRuleSet ruleSet, String salt)
    {
        return redact(result, ruleSet, salt).text;
    }

    /**
     * The redaction pass behind {@link #apply}, additionally reporting the match count so
     * the production entry can emit a meta-only audit line.
     */
    static Outcome redact(String result, PiiRuleSet ruleSet, String salt)
    {
        if (result == null || ruleSet == null || ruleSet.isEmpty())
        {
            return new Outcome(result, 0);
        }
        JsonElement root;
        try
        {
            root = JsonParser.parseString(result);
        }
        catch (RuntimeException e)
        {
            return new Outcome(result, 0); // not JSON: leave the tool's text untouched
        }
        if (root == null || !(root.isJsonObject() || root.isJsonArray()))
        {
            return new Outcome(result, 0);
        }
        RuleEngine engine = new RuleEngine(ruleSet, new Pseudonymizer(salt));
        Counter counter = new Counter();
        redactElement(root, null, engine, counter);
        if (counter.count == 0)
        {
            // Nothing masked: return the ORIGINAL string so a zero-PII result stays
            // byte-identical (a Gson round-trip would reformat whitespace).
            return new Outcome(result, 0);
        }
        return new Outcome(GsonProvider.toJson(root), counter.count);
    }

    /**
     * Whether {@code result} is a canonical ToolResult error payload
     * ({@code {"success":false,...}}). Mirrors the protocol handler's own detection: only
     * an explicit boolean {@code success==false} counts, so a successful result that
     * merely carries an {@code error} field is still redacted. Error payloads are skipped
     * because our errors are English tool text, not infobase data.
     *
     * @param result the serialized result (may be {@code null})
     * @return {@code true} if it is an error payload
     */
    static boolean isJsonErrorPayload(String result)
    {
        if (result == null)
        {
            return false;
        }
        try
        {
            JsonElement el = JsonParser.parseString(result);
            if (!el.isJsonObject())
            {
                return false;
            }
            JsonElement success = el.getAsJsonObject().get(KEY_SUCCESS);
            return success != null && success.isJsonPrimitive() && success.getAsJsonPrimitive().isBoolean()
                && !success.getAsBoolean();
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /**
     * Recursively redacts string leaves of {@code node}, mutating the tree in place. For
     * an object, each string field is evaluated by its key (and the canonical
     * {@code value} field additionally by the sibling {@code name}); when a {@code value}
     * field is replaced its now-stale {@code truncated}/{@code fullLength} siblings are
     * dropped. Array string elements are evaluated by the array's enclosing key.
     */
    private static void redactElement(JsonElement node, String enclosingKey, RuleEngine engine, Counter counter)
    {
        if (node.isJsonObject())
        {
            JsonObject obj = node.getAsJsonObject();
            String siblingName = stringField(obj, KEY_NAME);
            List<String> keys = new ArrayList<>(obj.keySet());
            for (String key : keys)
            {
                JsonElement child = obj.get(key);
                if (isJsonString(child))
                {
                    String original = child.getAsString();
                    String redacted = redactString(key, original, siblingName, engine, counter);
                    if (!redacted.equals(original))
                    {
                        obj.addProperty(key, redacted);
                        if (KEY_VALUE.equals(key))
                        {
                            // the truncated and fullLength markers describe the ORIGINAL value length -
                            // a pseudonym/mask changes it, so drop them or they lie.
                            obj.remove(KEY_TRUNCATED);
                            obj.remove(KEY_FULL_LENGTH);
                        }
                    }
                }
                else if (isContainer(child))
                {
                    redactElement(child, key, engine, counter);
                }
            }
        }
        else if (node.isJsonArray())
        {
            JsonArray arr = node.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++)
            {
                JsonElement el = arr.get(i);
                if (isJsonString(el))
                {
                    String original = el.getAsString();
                    String redacted = redactString(enclosingKey, original, null, engine, counter);
                    if (!redacted.equals(original))
                    {
                        arr.set(i, new JsonPrimitive(redacted));
                    }
                }
                else if (isContainer(el))
                {
                    redactElement(el, enclosingKey, engine, counter);
                }
            }
        }
    }

    /**
     * Redacts a single string value: a NAME-scope rule matching the key (or, for the
     * {@code value} field, the sibling name) replaces the WHOLE value with its token;
     * otherwise VALUE-scope rules regex-replace matching spans. Returns the same
     * {@code value} reference when nothing matches.
     */
    private static String redactString(String key, String value, String siblingName, RuleEngine engine,
        Counter counter)
    {
        if (value == null || value.isEmpty())
        {
            return value;
        }
        CompiledRule nameHit = engine.matchName(key);
        if (nameHit == null && KEY_VALUE.equals(key))
        {
            nameHit = engine.matchName(siblingName);
        }
        if (nameHit != null)
        {
            counter.count++;
            return engine.pseudonymizer().token(nameHit.representation, nameHit.countable, value);
        }
        SpanResult spans = engine.redactValueSpans(value);
        if (spans.count > 0)
        {
            counter.count += spans.count;
            return spans.text;
        }
        return value;
    }

    private static String stringField(JsonObject obj, String key)
    {
        JsonElement el = obj.get(key);
        return isJsonString(el) ? el.getAsString() : null;
    }

    private static boolean isJsonString(JsonElement el)
    {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString();
    }

    private static boolean isContainer(JsonElement el)
    {
        return el != null && (el.isJsonObject() || el.isJsonArray());
    }

    private static String toolName(IMcpTool tool)
    {
        try
        {
            String name = tool.getName();
            return name != null ? name : UNKNOWN_TOOL;
        }
        catch (RuntimeException e)
        {
            return UNKNOWN_TOOL;
        }
    }

    private static void logAudit(String toolName, int redactionCount)
    {
        Activator.logInfo("PII redaction applied on tool '" + toolName + "': " + redactionCount //$NON-NLS-1$ //$NON-NLS-2$
            + " value(s) masked."); //$NON-NLS-1$
    }

    /**
     * A rule set compiled once for one redaction pass: the enabled rules split by surface
     * (name vs value content), each guarded so a bad regex is dropped, plus the pass's
     * pseudonymiser.
     */
    private static final class RuleEngine
    {
        private final List<CompiledRule> nameRules = new ArrayList<>();
        private final List<CompiledRule> valueRules = new ArrayList<>();
        private final Pseudonymizer pseudonymizer;

        RuleEngine(PiiRuleSet ruleSet, Pseudonymizer pseudonymizer)
        {
            this.pseudonymizer = pseudonymizer;
            for (PiiRule rule : ruleSet.getRules())
            {
                if (!rule.isEnabled())
                {
                    continue;
                }
                Pattern pattern = safeCompile(rule.getRegex());
                if (pattern == null)
                {
                    continue; // a bad regex is skipped, never thrown - one bad row cannot break the rest
                }
                CompiledRule compiled = new CompiledRule(pattern, rule.isCountable(), rule.getRepresentation());
                if (rule.getScope().appliesToName())
                {
                    nameRules.add(compiled);
                }
                if (rule.getScope().appliesToValue())
                {
                    valueRules.add(compiled);
                }
            }
        }

        Pseudonymizer pseudonymizer()
        {
            return pseudonymizer;
        }

        /** The first NAME-scope rule whose regex is found in {@code name}, or {@code null}. */
        CompiledRule matchName(String name)
        {
            if (name == null || name.isEmpty())
            {
                return null;
            }
            for (CompiledRule rule : nameRules)
            {
                if (rule.pattern.matcher(name).find())
                {
                    return rule;
                }
            }
            return null;
        }

        /**
         * Replaces every VALUE-scope match span in {@code value} with its rule's token, in
         * rule order (a later rule sees the already-rewritten text). Returns the rewritten
         * text and the number of spans replaced (0 = untouched).
         */
        SpanResult redactValueSpans(String value)
        {
            int[] count = {0};
            String out = value;
            for (CompiledRule rule : valueRules)
            {
                Matcher matcher = rule.pattern.matcher(out);
                // The Function overload appends the replacement LITERALLY (no $/\ group
                // interpretation), so a representation with regex metacharacters is safe.
                out = matcher.replaceAll(mr -> {
                    count[0]++;
                    return pseudonymizer.token(rule.representation, rule.countable, mr.group());
                });
            }
            return new SpanResult(out, count[0]);
        }

        private static Pattern safeCompile(String regex)
        {
            if (regex == null)
            {
                return null;
            }
            try
            {
                return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            }
            catch (RuntimeException e)
            {
                return null; // PatternSyntaxException (or any compile failure): isolate this row
            }
        }
    }

    /** One enabled rule compiled for a pass: its pattern plus how a hit is rendered. */
    private static final class CompiledRule
    {
        final Pattern pattern;
        final boolean countable;
        final String representation;

        CompiledRule(Pattern pattern, boolean countable, String representation)
        {
            this.pattern = pattern;
            this.countable = countable;
            this.representation = representation;
        }
    }

    /** The rewritten value and how many VALUE-scope spans were replaced. */
    private static final class SpanResult
    {
        final String text;
        final int count;

        SpanResult(String text, int count)
        {
            this.text = text;
            this.count = count;
        }
    }

    /** The redaction pass outcome: the (possibly rewritten) JSON and the total match count. */
    static final class Outcome
    {
        final String text;
        final int count;

        Outcome(String text, int count)
        {
            this.text = text;
            this.count = count;
        }
    }

    /** Mutable match counter threaded through one redaction pass. */
    private static final class Counter
    {
        private int count;
    }
}
