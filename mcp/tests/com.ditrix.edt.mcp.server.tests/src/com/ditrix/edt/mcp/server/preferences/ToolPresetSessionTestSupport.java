/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.osgi.framework.FrameworkUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.tools.ToolsetState;
import com.ditrix.edt.mcp.server.tools.Toolsets;

/** Shared isolation and launch seam; never invokes earlyStartup or changes history singletons. */
public abstract class ToolPresetSessionTestSupport
{
    protected static final String ENV_PRESET = "EDT_MCP_TOOL_PRESET";
    protected final ToolSettingsService service = ToolSettingsService.getInstance();
    protected final McpToolRegistry registry = McpToolRegistry.getInstance();
    protected PreferenceStore store;
    protected final AtomicInteger environmentReads = new AtomicInteger();
    protected final List<String> environmentNames = Collections.synchronizedList(new ArrayList<>());

    private Field plugin;
    private Object previousPlugin;
    private Field sessionPreset;
    private Object previousSessionPreset;
    private Field environment;
    private Object previousEnvironment;
    private Collection<IMcpTool> previousTools;
    private Set<String> previousToolsets;
    private ILog log;
    private final List<IStatus> recorded = Collections.synchronizedList(new ArrayList<>());
    private final ILogListener listener = (status, pluginId) -> recorded.add(status);

    @Before
    public void setUpSession() throws Exception
    {
        store = new PreferenceStore();
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "ask_workmate,get_edt_version,git,list_projects");
        store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, "custom");
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION,
            PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
        store.setValue(PreferenceConstants.PREF_PROGRESSIVE_DISCLOSURE, false);
        ToolParameterSettings.getInstance().initializeDefaults(store);
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        plugin.set(null, activator);
        sessionPreset = optionalField("sessionPreset");
        if (sessionPreset != null)
        {
            previousSessionPreset = sessionPreset.get(service);
        }
        environment = optionalField("environment");
        if (environment != null)
        {
            previousEnvironment = environment.get(service);
        }
        configureLaunch(null);
        previousTools = registry.getAllTools();
        previousToolsets = ToolsetState.getInstance().getEnabledToolsetIds();
        BuiltInToolRegistrar.registerAll(registry);
        ToolsetState.getInstance().reset();
        assertFalse("these tests require full disclosure", Toolsets.isProgressiveDisclosureEnabled());
        log = Platform.getLog(FrameworkUtil.getBundle(ToolSettingsService.class));
        log.addLogListener(listener);
    }

    @After
    public void tearDownSession() throws Exception
    {
        if (log != null)
        {
            log.removeLogListener(listener);
        }
        if (environment != null)
        {
            environment.set(service, previousEnvironment);
        }
        if (sessionPreset != null)
        {
            sessionPreset.set(service, previousSessionPreset);
        }
        if (previousTools != null)
        {
            registry.replaceAll(previousTools);
        }
        if (previousToolsets != null)
        {
            ToolsetState.getInstance().reset();
            previousToolsets.forEach(ToolsetState.getInstance()::enable);
        }
        if (plugin != null)
        {
            plugin.set(null, previousPlugin);
        }
    }

    protected void configureLaunch(String value) throws Exception
    {
        setEnvironment(new Function<String, String>()
        {
            @Override
            public String apply(String name)
            {
                environmentReads.incrementAndGet();
                environmentNames.add(name);
                return ENV_PRESET.equals(name) ? value : null;
            }
        });
    }

    protected void setEnvironment(Function<String, String> reader) throws Exception
    {
        environmentReads.set(0);
        environmentNames.clear();
        if (environment != null)
        {
            environment.set(service, reader);
        }
        if (sessionPreset != null)
        {
            sessionPreset.set(service, null);
        }
    }

    /** Uses the direct round-one seam only when proving red against that saved implementation. */
    protected void launch(String value) throws Exception
    {
        configureLaunch(value);
        try
        {
            Method roundOne = ToolSettingsService.class.getMethod("applySessionPreset", String.class);
            roundOne.invoke(service, value);
        }
        catch (NoSuchMethodException e)
        {
            // Final code resolves through its normal first decision; HEAD has no launch seam.
        }
        service.getDisabledTools();
    }

    protected Object sessionHolder() throws Exception
    {
        return sessionPreset == null ? null : sessionPreset.get(service);
    }

    protected ToolPreset activePreset() throws Exception
    {
        try
        {
            return (ToolPreset) ToolSettingsService.class.getMethod("getSessionPreset").invoke(service);
        }
        catch (NoSuchMethodException e)
        {
            Object previous = sessionHolder();
            return previous == ToolPreset.CUSTOM ? null : (ToolPreset) previous;
        }
    }

    @SuppressWarnings("unchecked")
    protected Set<String> storedDisabledView() throws Exception
    {
        try
        {
            return (Set<String>) ToolSettingsService.class.getMethod("getStoredDisabledTools").invoke(service);
        }
        catch (NoSuchMethodException e)
        {
            return service.getDisabledTools();
        }
    }

    protected String storedPresetView() throws Exception
    {
        try
        {
            return (String) ToolSettingsService.class.getMethod("getStoredPresetId").invoke(service);
        }
        catch (NoSuchMethodException e)
        {
            return service.getPresetId();
        }
    }

    protected Set<String> storedDisabled()
    {
        return ToolSettingsService.parseDisabledTools(store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
    }

    protected Set<String> visibleNames()
    {
        return names(registry.getVisibleTools());
    }

    protected static Set<String> names(Collection<IMcpTool> tools)
    {
        return tools.stream().map(IMcpTool::getName).collect(Collectors.toSet());
    }

    protected List<IStatus> presetWarnings()
    {
        synchronized (recorded)
        {
            return recorded.stream().filter(status -> status.getMessage().contains(ENV_PRESET))
                .collect(Collectors.toList());
        }
    }

    protected Map<String, String> snapshot()
    {
        Map<String, String> values = new HashMap<>();
        for (String key : store.preferenceNames())
        {
            values.put(key, store.getString(key));
        }
        return values;
    }

    /** All persisted bytes, excluding only Properties.store's wall-clock comment header. */
    protected byte[] serializedStore() throws Exception
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.save(output, null);
        return output.toString(StandardCharsets.ISO_8859_1)
            .replaceAll("(?m)^#.*\\R", "").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Field optionalField(String name) throws Exception
    {
        try
        {
            Field field = ToolSettingsService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
        catch (NoSuchFieldException e)
        {
            return null;
        }
    }
}
