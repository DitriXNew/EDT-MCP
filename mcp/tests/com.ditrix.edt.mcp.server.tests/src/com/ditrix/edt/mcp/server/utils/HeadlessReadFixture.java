// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/** Real workspace files and metadata formatters, with a checked BM transaction executor. */
public final class HeadlessReadFixture implements AutoCloseable
{
    private final SearchFixtureProject files;
    private final Field plugin;
    private final Object savedPlugin;
    private final String application;
    public final IBmModel model = mock(IBmModel.class);
    public final Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
    public int depth;
    public int reads;

    public HeadlessReadFixture() throws Exception
    {
        files = SearchFixtureProject.create();
        plugin = Activator.class.getDeclaredField("plugin"); plugin.setAccessible(true);
        savedPlugin = plugin.get(null);
        application = System.getProperty("eclipse.application");
        System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
        Activator activator = mock(Activator.class);
        IBmModelManager models = mock(IBmModelManager.class);
        IConfigurationProvider configurations = mock(IConfigurationProvider.class);
        when(activator.getBmModelManager()).thenReturn(models);
        when(activator.getConfigurationProvider()).thenReturn(configurations);
        when(models.getModel(files.project())).thenReturn(model);
        when(configurations.getConfiguration(files.project())).thenAnswer(inv -> {
            assertTrue("Configuration lookup must be inside BmTransactions.read", depth > 0);
            return config;
        });
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            reads++; depth++;
            try
            {
                IBmTask<?> task = inv.getArgument(0);
                Object result = task.execute(null, null);
                ReadBoundaryAssertions.assertPreparationResult(task.getName(), result);
                return result;
            }
            finally { depth--; }
        });
        plugin.set(null, activator);
        Language language = spy(MdClassFactory.eINSTANCE.createLanguage());
        language.setName("English"); language.setLanguageCode("en");
        config.getLanguages().add(language); config.setDefaultLanguage(language);
        when(language.getLanguageCode()).thenAnswer(inv -> {
            assertTrue("Language code must be read inside BM", depth > 0); return "en";
        });
        for (String name : new String[] { "EnglishName", "Код" })
        {
            CommonModule module = spy(MdClassFactory.eINSTANCE.createCommonModule());
            module.setName(name); module.getSynonym().put("en", "Translated " + name);
            config.getCommonModules().add(module);
            when(module.getName()).thenAnswer(inv -> {
                assertTrue("Name must be read inside BM", depth > 0); return name;
            });
            files.file("src/CommonModules/" + name + "/Module.bsl",
                "Функция Маркер() Экспорт\n\tВозврат 1;\nКонецФункции\n");
        }
    }

    public String call(IMcpTool tool, Map<String, String> arguments)
    {
        Map<String, String> params = new HashMap<>(arguments);
        params.put("projectName", files.name());
        try { return tool.execute(params); }
        catch (RuntimeException failure) { throw new AssertionError("Core read failed without a workbench", failure); }
    }

    public String name() { return files.name(); }
    public org.eclipse.core.resources.IProject project() { return files.project(); }

    public String callWindowed(IMcpTool tool, Map<String, String> arguments) throws Exception
    {
        var provider = mock(com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider.class);
        var set = mock(org.eclipse.emf.ecore.resource.ResourceSet.class);
        var resource = mock(org.eclipse.emf.ecore.resource.Resource.class);
        when(resource.getContents()).thenReturn(new org.eclipse.emf.common.util.BasicEList<>());
        when(set.getResource(any(org.eclipse.emf.common.util.URI.class), anyBoolean())).thenReturn(resource);
        when(provider.get(files.project())).thenReturn(set);
        when(Activator.getDefault().getResourceSetProvider()).thenReturn(provider);
        String saved = System.getProperty("eclipse.application");
        try (var workbench = new ReadTestWorkbench())
        {
            System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
            return call(tool, arguments);
        }
        finally
        {
            if (saved == null) { System.clearProperty("eclipse.application"); }
            else { System.setProperty("eclipse.application", saved); }
        }
    }

    @Override public void close() throws Exception
    {
        plugin.set(null, savedPlugin);
        if (application == null) { System.clearProperty("eclipse.application"); }
        else { System.setProperty("eclipse.application", application); }
        files.close();
    }
}
