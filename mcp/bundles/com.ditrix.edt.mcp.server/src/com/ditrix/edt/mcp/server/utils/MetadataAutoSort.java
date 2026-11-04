/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.emf.ecore.EReference;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.md.sort.MdObjectByNameComparator;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonParser;

/** Reads optional autosort policy without changing preferences or linking to the plugin. */
public final class MetadataAutoSort
{
    public static final String BUNDLE_ID = "com.e1c.v8codestyle.autosort";
    public static final String PLUGIN = "1C:Code style V8 (com.e1c.v8codestyle.autosort)";
    public static final String MAINTAINED = "The plugin maintains this list in alphabetical order.";
    public static final String CREATED = "The plugin places the new object in its alphabetical position.";
    public static final String REFUSED = "The plugin would move the object anyway and revert a manual position or order.";
    public static final String OUTPUT_DESCRIPTION = "When governed: plugin, governs, setting (human scope), order, effect, userAction; omitted otherwise.";
    private static final String UI_PATH = "Project > Properties > V8 > Metadata objects sort > Auto sort";
    private static final String MD_BUNDLE_ID = "com._1c.g5.v8.dt.md";
    private static final Policy INACTIVE = new Policy(false, true, true, "");

    private MetadataAutoSort()
    {
    }

    @FunctionalInterface
    public interface Settings
    {
        Policy forCollection(EReference ref);
    }

    public static Settings disabled()
    {
        return ref -> INACTIVE;
    }

    public static boolean isInstalled()
    {
        Bundle bundle = Platform.getBundle(BUNDLE_ID);
        return bundle != null && bundle.getState() != Bundle.UNINSTALLED;
    }

    public static Settings forProject(IProject project)
    {
        Bundle bundle = Platform.getBundle(BUNDLE_ID);
        if (bundle == null || bundle.getState() == Bundle.UNINSTALLED)
        {
            return disabled();
        }
        return load(bundle, Platform.getPreferencesService(), new ProjectScope(project));
    }

    static Settings load(Bundle bundle, IPreferencesService service, IScopeContext projectScope)
    {
        if (bundle == null || bundle.getState() == Bundle.UNINSTALLED)
        {
            return disabled();
        }
        try
        {
            Class<?> lists = bundle.loadClass("com.e1c.v8codestyle.autosort.ListConstants");
            Set<?> top = (Set<?>)lists.getField("TOP_OPBJECT_LISTS").get(null);
            Set<?> subordinate = (Set<?>)lists.getField("SUBORDINATE_OBJECT_LISTS").get(null);
            IEclipsePreferences prefs = projectScope.getNode(BUNDLE_ID);
            IEclipsePreferences mdPrefs = projectScope.getNode(MD_BUNDLE_ID);
            boolean allTop = service.getBoolean(BUNDLE_ID, "topObjects", false,
                new IScopeContext[] {projectScope, InstanceScope.INSTANCE, ConfigurationScope.INSTANCE, DefaultScope.INSTANCE});
            boolean allSubordinate = prefs.getBoolean("subordinateObjects", false);
            // Current autosort delegates its comparator preferences to EDT's metadata bundle.
            boolean ascending = mdPrefs.getBoolean("ascendingSort", true);
            boolean natural = mdPrefs.getBoolean("naturalSortOrder", true);
            Map<EReference, Policy> policies = new LinkedHashMap<>();
            for (Object value : top)
            {
                EReference ref = (EReference)value;
                if (allTop || prefs.node("top").getBoolean(ref.getName(), false))
                {
                    policies.put(ref, new Policy(true, ascending, natural,
                        allTop ? "topObjects" : "top/" + ref.getName()));
                }
            }
            for (Object value : subordinate)
            {
                EReference ref = (EReference)value;
                if (allSubordinate || prefs.node("subordinate").getBoolean(ref.getName(), false))
                {
                    policies.put(ref, new Policy(true, ascending, natural,
                        allSubordinate ? "subordinateObjects" : "subordinate/" + ref.getName()));
                }
            }
            Map<EReference, Policy> snapshot = Map.copyOf(policies);
            return ref -> snapshot.getOrDefault(ref, INACTIVE);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            throw new IllegalStateException("Cannot inspect installed V8 code style autosort policy; "
                + "check the project's V8 code style settings and plugin compatibility before ordering metadata ("
                + e.getClass().getSimpleName() + ": " + e.getMessage() + ").");
        }
    }

    public record Policy(boolean active, boolean ascending, boolean natural, String preferenceKey)
    {
        public Comparator<MdObject> comparator()
        {
            return new MdObjectByNameComparator(ascending, natural)::compare;
        }

        public Notice notice(String collection, String effect)
        {
            if (!active)
            {
                return null;
            }
            String scope = preferenceKey.equals("topObjects") ? "all top-level lists"
                : preferenceKey.equals("subordinateObjects") ? "all subordinate lists" : "this list";
            String path = UI_PATH;
            if (preferenceKey.equals("topObjects"))
            {
                path += " > All top metadata objects";
            }
            else if (preferenceKey.equals("subordinateObjects"))
            {
                path += " > All subordinate objects";
            }
            else if (preferenceKey.startsWith("top/"))
            {
                path += " > Select top objects";
            }
            else
            {
                path += " > Select subordinate objects";
            }
            String action = "Autosort (1C:Code style V8) keeps this list in alphabetical order. "
                + "If a manual order is needed, ask the user to turn it off for this list in " + path
                + "; do not change this setting yourself.";
            return new Notice(collection, scope, (ascending ? "ascending" : "descending")
                + ", " + (natural ? "natural" : "plain"), effect, action);
        }

        public void requireManualOrder(String collection)
        {
            if (active)
            {
                throw new ManualOrderRefusal(notice(collection, REFUSED));
            }
        }
    }

    public record Notice(String governs, String setting, String order, String effect, String userAction)
    {
        public Map<String, Object> block()
        {
            return Map.of("plugin", PLUGIN, "governs", governs, "setting", setting,
                "order", order, "effect", effect, "userAction", userAction);
        }

        public String message()
        {
            return effect + " " + userAction;
        }

        public String annotate(String result)
        {
            var body = JsonParser.parseString(result).getAsJsonObject();
            body.add("autosort", JsonParser.parseString(GsonProvider.toJson(block())));
            String existing = body.has("message") ? body.get("message").getAsString() + " " : "";
            body.addProperty("message", existing + message());
            return GsonProvider.toJson(body);
        }
    }

    public static final class ManualOrderRefusal extends IllegalArgumentException
    {
        private static final long serialVersionUID = 1L;
        private final Notice notice;

        ManualOrderRefusal(Notice notice)
        {
            super("Cannot set manual order for '" + notice.governs() + "'. " + notice.message());
            this.notice = notice;
        }
    }

    public static String refusalJson(Throwable error)
    {
        for (Throwable current = error; current != null; current = current.getCause())
        {
            if (current instanceof ManualOrderRefusal refusal)
            {
                return ToolResult.error(refusal.getMessage()).put("autosort", refusal.notice.block())
                    .put("message", refusal.notice.message()).toJson();
            }
        }
        return null;
    }

    public static void putNotice(Map<String, Object> row, Policy policy, String collection, String effect)
    {
        Notice notice = policy.notice(collection, effect);
        if (notice != null)
        {
            row.put("autosort", notice.block());
        }
    }

    public static ToolResult summarize(ToolResult result, List<Map<String, Object>> rows)
    {
        Set<String> messages = new LinkedHashSet<>();
        for (Map<String, Object> row : rows)
        {
            if (row.get("autosort") instanceof Map<?, ?> block)
            {
                messages.add(block.get("effect") + " " + block.get("userAction"));
            }
        }
        return messages.isEmpty() ? result : result.put("message", String.join(" ", messages));
    }
}
