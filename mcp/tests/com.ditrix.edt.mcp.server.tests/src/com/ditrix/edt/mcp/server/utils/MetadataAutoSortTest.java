/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.emf.ecore.EReference;
import org.junit.Test;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

public class MetadataAutoSortTest
{
    public static final class Lists
    {
        public static final Set<EReference> TOP_OPBJECT_LISTS = Set.of(
            MdClassPackage.Literals.CONFIGURATION__CATALOGS, MdClassPackage.Literals.CONFIGURATION__DOCUMENTS,
            MdClassPackage.Literals.SUBSYSTEM__SUBSYSTEMS);
        public static final Set<EReference> SUBORDINATE_OBJECT_LISTS = Set.of(
            MdClassPackage.Literals.CATALOG__ATTRIBUTES, MdClassPackage.Literals.CATALOG__FORMS,
            MdClassPackage.Literals.CATALOG__TABULAR_SECTIONS, MdClassPackage.Literals.INFORMATION_REGISTER__RESOURCES);
    }

    private final List<IEclipsePreferences> observedNodes = new ArrayList<>();
    private final IEclipsePreferences prefs = memoryPreferences();
    private final IEclipsePreferences mdPrefs = memoryPreferences();
    private final IPreferencesService service = mock(IPreferencesService.class);
    private final IScopeContext scope = mock(IScopeContext.class);

    private IEclipsePreferences memoryPreferences()
    {
        IEclipsePreferences result = mock(IEclipsePreferences.class);
        observedNodes.add(result);
        var values = new java.util.HashMap<String, Boolean>();
        var children = new java.util.HashMap<String, IEclipsePreferences>();
        when(result.getBoolean(anyString(), anyBoolean())).thenAnswer(inv ->
            values.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        doAnswer(inv -> { values.put(inv.getArgument(0), inv.getArgument(1)); return null; })
            .when(result).putBoolean(anyString(), anyBoolean());
        when(result.node(anyString())).thenAnswer(inv ->
            children.computeIfAbsent(inv.getArgument(0), key -> memoryPreferences()));
        return result;
    }

    private static Bundle bundle(int state, boolean compatible)
    {
        return (Bundle)java.lang.reflect.Proxy.newProxyInstance(Bundle.class.getClassLoader(),
            new Class<?>[] {Bundle.class}, (proxy, method, args) -> {
                if (method.getName().equals("getState"))
                {
                    return state;
                }
                if (method.getName().equals("loadClass") && compatible)
                {
                    assertEquals("com.e1c.v8codestyle.autosort.ListConstants", args[0]);
                    return Lists.class;
                }
                if (method.getName().equals("loadClass"))
                {
                    throw new ClassNotFoundException("ListConstants");
                }
                throw new AssertionError("Unexpected bundle operation: " + method.getName());
            });
    }

    private MetadataAutoSort.Settings settings() throws Exception
    {
        Bundle bundle = bundle(Bundle.RESOLVED, true);
        when(scope.getNode(MetadataAutoSort.BUNDLE_ID)).thenReturn(prefs);
        when(scope.getNode("com._1c.g5.v8.dt.md")).thenReturn(mdPrefs);
        when(service.getBoolean(eq(MetadataAutoSort.BUNDLE_ID), eq("topObjects"), eq(false), any()))
            .thenAnswer(inv -> prefs.getBoolean("topObjects", false));
        return MetadataAutoSort.load(bundle, service, scope);
    }

    @Test
    public void absentOrUninstalledPluginIgnoresEvenEnabledFixturePreferences()
    {
        assertFalse(MetadataAutoSort.load(null, null, null)
            .forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS).active());
        Bundle bundle = bundle(Bundle.UNINSTALLED, false);
        assertFalse(MetadataAutoSort.load(bundle, null, null)
            .forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS).active());
    }

    @Test
    public void installedPluginUsesEffectivePreferencesRatherThanNewProjectDefaults() throws Exception
    {
        assertFalse(settings().forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS).active());
        prefs.putBoolean("topObjects", true);
        var policy = settings().forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS);
        assertTrue(policy.active());
        assertEquals("topObjects", policy.preferenceKey());
        assertTrue(policy.ascending());
        assertTrue(policy.natural());
    }

    @Test
    public void allTopUsesInheritedScopesAndIncludesNestedSubsystemLists() throws Exception
    {
        settings();
        when(service.getBoolean(eq(MetadataAutoSort.BUNDLE_ID), eq("topObjects"), eq(false), any()))
            .thenAnswer(inv -> {
                IScopeContext[] contexts = inv.getArgument(3);
                assertSame(scope, contexts[0]);
                assertEquals(4, contexts.length);
                return true;
            });
        Bundle bundle = bundle(Bundle.RESOLVED, true);
        var settings = MetadataAutoSort.load(bundle, service, scope);
        assertTrue(settings.forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS).active());
        assertTrue(settings.forCollection(MdClassPackage.Literals.SUBSYSTEM__SUBSYSTEMS).active());
        assertFalse(settings.forCollection(MdClassPackage.Literals.CATALOG__ATTRIBUTES).active());
    }

    @Test
    public void listKeysDoNotEnableOtherListsAndAllTopOverridesAnIndividualFalse() throws Exception
    {
        prefs.node("top").putBoolean("catalogs", true);
        var settings = settings();
        assertEquals("top/catalogs", settings.forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS).preferenceKey());
        assertFalse(settings.forCollection(MdClassPackage.Literals.CONFIGURATION__DOCUMENTS).active());
        prefs.putBoolean("topObjects", true);
        prefs.node("top").putBoolean("catalogs", false);
        assertTrue(settings().forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS).active());
    }

    @Test
    public void subordinateKeysUseThePluginsExactReferenceMembership() throws Exception
    {
        prefs.node("subordinate").putBoolean("attributes", true);
        var settings = settings();
        assertEquals("subordinate/attributes", settings.forCollection(MdClassPackage.Literals.CATALOG__ATTRIBUTES).preferenceKey());
        assertFalse(settings.forCollection(MdClassPackage.Literals.CATALOG__FORMS).active());
        prefs.putBoolean("subordinateObjects", true);
        settings = settings();
        assertTrue(settings.forCollection(MdClassPackage.Literals.INFORMATION_REGISTER__RESOURCES).active());
        assertTrue(settings.forCollection(MdClassPackage.Literals.CATALOG__TABULAR_SECTIONS).active());
        assertFalse(settings.forCollection(MdClassPackage.Literals.INFORMATION_REGISTER__DIMENSIONS).active());
        assertFalse(settings.forCollection(MdClassPackage.Literals.ENUM__ENUM_VALUES).active());
    }

    @Test
    public void currentComparatorSettingsOverrideDeprecatedAutosortKeysAndAreSnapshotted() throws Exception
    {
        prefs.putBoolean("topObjects", true);
        prefs.putBoolean("sortAscending", true);
        prefs.putBoolean("naturalSortOrder", true);
        mdPrefs.putBoolean("ascendingSort", false);
        mdPrefs.putBoolean("naturalSortOrder", false);
        var policy = settings().forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS);
        assertFalse(policy.ascending());
        assertFalse(policy.natural());
        assertEquals(List.of("A2", "A10", "A1", "A_"), sorted(policy));
        mdPrefs.putBoolean("ascendingSort", true);
        assertFalse(policy.ascending());
        assertEquals(List.of("A_", "A1", "A10", "A2"), sorted(
            settings().forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS)));
        mdPrefs.putBoolean("naturalSortOrder", true);
        assertEquals(List.of("A1", "A10", "A2", "A_"), sorted(
            settings().forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS)));
    }

    private List<String> sorted(MetadataAutoSort.Policy policy)
    {
        var list = new ArrayList<>(List.of("A_", "A2", "A10", "A1").stream().map(name -> {
            var object = MdClassFactory.eINSTANCE.createCatalog();
            object.setName(name);
            return object;
        }).toList());
        list.sort(policy.comparator());
        return list.stream().map(object -> object.getName()).toList();
    }

    @Test
    public void refusalNamesCollectionAndAsksTheUserThroughTheVerifiedUiPath() throws Exception
    {
        prefs.node("top").putBoolean("catalogs", true);
        var policy = settings().forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS);
        String message = assertThrows(IllegalArgumentException.class,
            () -> policy.requireManualOrder("Configuration.catalogs")).getMessage();
        for (String part : List.of("Configuration.catalogs", "revert", "ask the user", "Project > Properties > V8",
            "Metadata objects sort > Auto sort > Select top objects", "do not change this setting yourself"))
        {
            assertTrue(message, message.contains(part));
        }
        String json = MetadataAutoSort.refusalJson(new RuntimeException(new MetadataAutoSort.ManualOrderRefusal(
            policy.notice("Configuration.catalogs", MetadataAutoSort.REFUSED))));
        var block = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("autosort");
        assertEquals(Set.of("plugin", "governs", "setting", "order", "effect", "userAction"), block.keySet());
        assertEquals("this list", block.get("setting").getAsString());
        assertEquals("ascending, natural", block.get("order").getAsString());
        assertFalse(json, json.contains("top/catalogs"));
        assertFalse(json, json.contains(".prefs"));
        assertNull(MetadataAutoSort.disabled().forCollection(null).notice("Configuration.catalogs", MetadataAutoSort.MAINTAINED));
        MetadataAutoSort.disabled().forCollection(null).requireManualOrder("Configuration.catalogs");
    }

    @Test
    public void noticesExposeOnlyHumanScopesAndVerifiedUiSections()
    {
        String[][] cases = {{"topObjects", "all top-level lists", "All top metadata objects"},
            {"subordinateObjects", "all subordinate lists", "All subordinate objects"},
            {"top/catalogs", "this list", "Select top objects"},
            {"subordinate/attributes", "this list", "Select subordinate objects"}};
        for (String[] item : cases)
        {
            for (boolean ascending : List.of(false, true))
            {
                for (boolean natural : List.of(false, true))
                {
                    var notice = new MetadataAutoSort.Policy(true, ascending, natural, item[0])
                        .notice("Configuration.catalogs", MetadataAutoSort.MAINTAINED);
                    assertEquals(item[1], notice.setting());
                    assertEquals((ascending ? "ascending" : "descending") + ", " + (natural ? "natural" : "plain"), notice.order());
                    assertTrue(notice.userAction(), notice.userAction().contains("Auto sort > " + item[2]));
                    assertFalse(notice.block().toString().contains(item[0]));
                }
            }
        }
    }

    @Test
    public void allPublicPolicyPathsLeaveAutosortPreferencesReadOnly() throws Exception
    {
        prefs.putBoolean("topObjects", true);
        prefs.node("subordinate").putBoolean("attributes", true);
        observedNodes.forEach(org.mockito.Mockito::clearInvocations);
        var settings = settings();
        var policy = settings.forCollection(MdClassPackage.Literals.CONFIGURATION__CATALOGS);
        sorted(policy);
        policy.notice("Configuration.catalogs", MetadataAutoSort.CREATED).annotate("{\"success\":true}");
        policy.notice("Configuration.catalogs", MetadataAutoSort.MAINTAINED).block();
        assertThrows(MetadataAutoSort.ManualOrderRefusal.class, () -> policy.requireManualOrder("Configuration.catalogs"));
        Set<String> writes = Set.of("put", "putBoolean", "putInt", "putLong", "putFloat", "putDouble", "putByteArray",
            "remove", "removeNode", "clear", "flush", "sync");
        for (var node : observedNodes)
        {
            for (var invocation : mockingDetails(node).getInvocations())
            {
                assertFalse("Autosort preference write: " + invocation, writes.contains(invocation.getMethod().getName()));
            }
        }
    }

    @Test
    public void incompatibleInstalledPluginDoesNotSilentlyDisableTheGuard() throws Exception
    {
        Bundle bundle = bundle(Bundle.RESOLVED, false);
        var error = assertThrows(IllegalStateException.class, () -> MetadataAutoSort.load(bundle, service, scope));
        assertTrue(error.getMessage(), error.getMessage().contains("plugin compatibility"));
    }
}
