// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.utils.techlog;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

/** Bundled, sourced reference. No network access or runtime dependency on the research files. */
public final class TechLogKnowledge
{
    private static final JsonObject DATA = load();

    private TechLogKnowledge() { }

    private static JsonObject load()
    {
        try (var stream = TechLogKnowledge.class.getResourceAsStream("/techlog/knowledge.json"))
        {
            if (stream == null) throw new IllegalStateException("Missing bundled techlog/knowledge.json");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        catch (Exception e) { throw new IllegalStateException("Cannot load technological log knowledge", e); }
    }

    public static JsonObject entries(String category) { return DATA.getAsJsonObject(category).deepCopy(); }

    public static JsonObject entry(String category, String id)
    {
        for (var item : DATA.getAsJsonObject(category).entrySet())
            if (item.getKey().equalsIgnoreCase(id)) return item.getValue().getAsJsonObject().deepCopy();
        List<String> names = new ArrayList<>(DATA.getAsJsonObject(category).keySet());
        names.sort(Comparator.comparingInt(n -> distance(n.toLowerCase(Locale.ROOT), id.toLowerCase(Locale.ROOT))));
        throw new IllegalArgumentException("Unknown " + category + " '" + id + "'. Use describe; close matches: "
            + String.join(", ", names.subList(0, Math.min(5, names.size()))) + ". Available: "
            + String.join(", ", DATA.getAsJsonObject(category).keySet()));
    }

    public static List<String> events(String profile)
    {
        List<String> result = new ArrayList<>();
        entry("profiles", profile).getAsJsonArray("events").forEach(e -> result.add(e.getAsString()));
        return result;
    }

    public static String describe(String category, String id)
    {
        if (id == null || id.isBlank())
        {
            StringBuilder out = new StringBuilder("# Technological log reference\n\nModes: describe, summary (default), details, chain, events.\n\n"
                + "Workflow: profiling → profile → capture → summary → details → chain → fix → re-measure.\n\n"
                + "Use event, property, profile or problem with mode=describe.\n\n"
                + "Capture: manage_tech_log mode=preview, then start with consent. JSON/placement requires platform 8.3.25+.\n\n"
                + "Service-account clusters do not read your LOCALAPPDATA configuration. Collect every relevant node.\n\n");
            for (String name : List.of("events", "properties", "profiles", "problems", "elements", "gotchas"))
                out.append("**").append(name).append(":** ")
                    .append(MarkdownUtils.escapeMarkdown(String.join(", ", DATA.getAsJsonObject(name).keySet())))
                    .append("\n\n");
            return out.toString();
        }
        return render(id, entry(category, id));
    }

    private static String render(String title, JsonObject object)
    {
        StringBuilder out = new StringBuilder("## " + MarkdownUtils.escapeMarkdown(title) + "\n\n");
        String[][] fields = {
            {"meaning", "Meaning"}, {"read", "Read"}, {"appearsIn", "Appears in"},
            {"evidence", "Evidence"}, {"badSigns", "Bad signs"}, {"investigate", "Investigate"},
            {"fixes", "Fixes"}, {"traps", "Traps"}, {"profile", "Profile"}, {"cost", "Cost"},
            {"generatingElements", "Generating elements"}, {"events", "Events"},
            {"thresholdMs", "Minimum duration (ms)"}, {"historyHours", "Retention (hours)"},
            {"reason", "Reason"}, {"note", "Note"}
        };
        for (String[] field : fields)
        {
            JsonElement value = object.get(field[0]);
            if (value == null || value.isJsonNull()) continue;
            List<String> parts = new ArrayList<>();
            if (value.isJsonArray()) value.getAsJsonArray().forEach(item -> parts.add(item.getAsString()));
            else parts.add(value.getAsString());
            String text = String.join(", ", parts);
            if (!text.isBlank()) out.append("**").append(field[1]).append(":** ")
                .append(MarkdownUtils.escapeMarkdown(text)).append("\n\n");
        }
        if (object.has("sources") && !object.getAsJsonArray("sources").isEmpty())
        {
            out.append("**Sources:**\n\n");
            for (JsonElement source : object.getAsJsonArray("sources"))
            {
                JsonObject ref = source.getAsJsonObject();
                String tag = ref.get("tag").getAsString();
                if (tag.startsWith("[") && tag.endsWith("]")) tag = tag.substring(1, tag.length() - 1);
                String url = ref.get("url").getAsString().replace("(", "%28").replace(")", "%29");
                out.append("- [").append(MarkdownUtils.escapeMarkdown(tag)).append("](")
                    .append(url).append(")\n");
            }
            out.append('\n');
        }
        if (object.has("example"))
        {
            out.append("**Example:**\n\n");
            if (object.has("exampleNote")) out.append(MarkdownUtils.escapeMarkdown(
                object.get("exampleNote").getAsString())).append("\n\n");
            code(out, "json", object.get("example").getAsString());
        }
        if (object.has("thresholds"))
        {
            out.append("**Thresholds:**\n\n");
            for (JsonElement threshold : object.getAsJsonArray("thresholds"))
            {
                JsonObject value = threshold.getAsJsonObject();
                out.append("- ").append(MarkdownUtils.escapeMarkdown(value.get("text").getAsString()))
                    .append(" ([source](").append(value.get("source").getAsString()
                        .replace("(", "%28").replace(")", "%29")).append("))\n");
            }
            out.append('\n');
        }
        bullets(out, object, "causes", "Causes");
        if (object.has("fixExample"))
        {
            out.append("**Fix example:**\n\n");
            code(out, "bsl", object.get("fixExample").getAsString());
        }
        bullets(out, object, "nextSteps", "Next steps");
        if (object.has("related"))
        {
            out.append("**Related:**\n\n");
            for (JsonElement related : object.getAsJsonArray("related"))
            {
                String[] reference = related.getAsString().split(":", 2);
                out.append("- `get_tech_log mode=describe ").append(reference[0]).append('=')
                    .append(reference[1]).append("`\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void bullets(StringBuilder out, JsonObject object, String field, String label)
    {
        if (!object.has(field)) return;
        out.append("**").append(label).append(":**\n\n");
        for (JsonElement value : object.getAsJsonArray(field))
            out.append("- ").append(MarkdownUtils.escapeMarkdown(value.getAsString())).append('\n');
        out.append('\n');
    }

    private static void code(StringBuilder out, String language, String text)
    {
        String fence = "```";
        while (text.contains(fence)) fence += "`";
        out.append(fence).append(language).append('\n').append(text).append('\n').append(fence).append("\n\n");
    }

    private static int distance(String a, String b)
    {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++)
        {
            int previous = row[0]; row[0] = i;
            for (int j = 1; j <= b.length(); j++)
            {
                int old = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1),
                    previous + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                previous = old;
            }
        }
        return row[b.length()];
    }
}
