/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;

import com._1c.g5.v8.dt.core.lifecycle.WorkspaceProjectStartRequest;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportLifecycle;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportRunner;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Shared local fixtures; no EDT import or platform process is run by these tests. */
public abstract class ImportFolderCaseTestSupport
{
    static final String CONFIGURATION_XML = "<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\">"
        + "<Configuration><Properties/></Configuration></MetaDataObject>";

    final List<IProject> projects = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    final List<Path> locations = new ArrayList<>();
    Path dump;
    Path binary;

    @Before
    public void setUpFiles() throws Exception
    {
        dump = Files.createTempDirectory("import-folder-678-");
        Files.writeString(dump.resolve("Configuration.xml"), CONFIGURATION_XML);
        binary = dump.resolve("Fixture.cf");
        Files.write(binary, new byte[] { 1 });
    }

    @After
    public void tearDownFiles() throws Exception
    {
        for (String name : names)
        {
            ImportConfigurationFromXmlTool.releaseImport(name);
            ImportConfigurationFromXmlTool.releaseImport(variant(name));
        }
        for (IProject project : projects)
        {
            if (project.exists())
            {
                project.delete(true, true, new NullProgressMonitor());
            }
        }
        for (Path location : locations)
        {
            assertTrue("remove the project location", BinaryToXmlConverter.deleteQuietly(location));
        }
        assertTrue("remove the scratch files", BinaryToXmlConverter.deleteQuietly(dump));
    }

    String name()
    {
        String name = "ZzImport678Sales" + UUID.randomUUID().toString().replace("-", "");
        names.add(name);
        return name;
    }

    static String variant(String name)
    {
        return name.toLowerCase(Locale.ROOT);
    }

    static boolean caseSensitive()
    {
        return EFS.getLocalFileSystem().isCaseSensitive();
    }

    IProject createProject(String name) throws Exception
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        projects.add(project);
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        return project;
    }

    /**
     * A project outside its default folder, as a project imported from a git clone is.
     *
     * @return the created and opened project; its location is {@code location()}
     */
    IProject createProjectElsewhere(String name) throws Exception
    {
        Path location = Files.createTempDirectory("import-folder-678-location-").resolve(name);
        locations.add(location.getParent());
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        projects.add(project);
        IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(name);
        description.setLocation(org.eclipse.core.runtime.Path.fromOSString(location.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        return project;
    }

    /** The folder a project of this name would get by default: {@code <workspace root>/<name>}. */
    static String defaultFolder(String name)
    {
        return ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(name).toString();
    }

    static String location(IProject project)
    {
        return project.getLocation().toFile().toPath().normalize().toString();
    }

    static String error(String result, String... fragments)
    {
        assertTrue("a refusal must be error JSON: " + result, result.startsWith("{"));
        JsonObject answer = JsonParser.parseString(result).getAsJsonObject();
        assertFalse(result, answer.get("success").getAsBoolean());
        String message = answer.get("error").getAsString();
        for (String fragment : fragments)
        {
            assertTrue(message, message.contains(fragment));
        }
        assertFalse("a pre-import refusal has no mutation marker", result.contains("mutationCommitted"));
        assertFalse("a pre-import refusal has no mutation marker", result.contains("mutationOutcomeUnknown"));
        return message;
    }

    String executeXml(String name, RecordingRunner runner)
    {
        return ImportConfigurationFromXmlTool.executeWith(
            Map.of("importPath", dump.toString(), "projectName", name), runner, new ReadyLifecycle(), 5_000);
    }

    static class RecordingRunner implements IImportRunner
    {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger apiChecks = new AtomicInteger();

        @Override
        public Object importApi() { apiChecks.incrementAndGet(); return this; }

        @Override
        public void runImport(Object api, Path path, String name, String version, String base) throws Exception
        {
            calls.incrementAndGet();
        }
    }

    static final class ReadyLifecycle implements IImportLifecycle
    {
        @Override
        public boolean projectExists(IProject project) { return true; }
        @Override
        public void refreshLocal(IProject project, IProgressMonitor monitor) { }
        @Override
        public void permitImport(IProject project) { fail("a ready import needs no recovery"); }
        @Override
        public boolean isStarted(IProject project) { return true; }
        @Override
        public void startWorkspaceProjects(Collection<WorkspaceProjectStartRequest> requests,
            IProgressMonitor monitor) { fail("a ready project needs no restart"); }
        @Override
        public boolean hasDtProject(IProject project) { return true; }
    }
}
