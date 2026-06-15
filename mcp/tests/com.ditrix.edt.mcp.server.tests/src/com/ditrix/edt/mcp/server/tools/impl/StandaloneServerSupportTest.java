/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.StandaloneServerSupport.RegistryCleanup;

/**
 * Tests for {@link StandaloneServerSupport}.
 * <p>
 * {@code StandaloneServerSupport} reaches the EDT standalone-server feature REFLECTIVELY
 * (OSGi lookup by class name + {@code Method.invoke}) so the plugin loads even on a headless
 * EDT where that feature is absent. The reflective call wrappers that take a plain {@code Object}
 * / {@code String} ({@code infobaseIdOf}, {@code databaseDirOf}, {@code findServerByModuleName},
 * {@code deleteServer}) reflect on the PASSED object's class, so they can be exercised end-to-end
 * with hand-written fake classes — no live EDT model, OSGi service, SWT or workspace required.
 * <p>
 * These tests cover every such pure-reflective branch (method present / absent / wrong return /
 * throwing), the {@code RegistryCleanup} enum, and the deterministic "feature absent" outcomes of
 * the OSGi-bound entry points ({@code acquireService}, {@code removeFromInfobaseRegistry}) which in
 * the headless test runtime degrade gracefully (the WST bundles are intentionally NOT a dependency
 * of the host plugin). The {@code IApplication}-typed wrappers ({@code serverOfApplication},
 * {@code moduleOfApplication}) are covered only on their null/failure branch (returns {@code null},
 * never throws); their success branch needs a live wst-server {@code IApplication} and is left to
 * the e2e suite.
 */
public class StandaloneServerSupportTest
{
    private static final IProgressMonitor MONITOR = new NullProgressMonitor();

    // ==================== RegistryCleanup enum ====================

    @Test
    public void testRegistryCleanupHasThreeValues()
    {
        assertEquals(3, RegistryCleanup.values().length);
    }

    @Test
    public void testRegistryCleanupValueOfRemoved()
    {
        assertSame(RegistryCleanup.REMOVED, RegistryCleanup.valueOf("REMOVED")); //$NON-NLS-1$
    }

    @Test
    public void testRegistryCleanupValueOfNotPresent()
    {
        assertSame(RegistryCleanup.NOT_PRESENT, RegistryCleanup.valueOf("NOT_PRESENT")); //$NON-NLS-1$
    }

    @Test
    public void testRegistryCleanupValueOfFailed()
    {
        assertSame(RegistryCleanup.FAILED, RegistryCleanup.valueOf("FAILED")); //$NON-NLS-1$
    }

    // ==================== infobaseIdOf ====================

    @Test
    public void testInfobaseIdOfReadsValueAsString()
    {
        // getInfobaseId() returns a non-null value -> its toString() is returned.
        Object module = new FakeInfobaseModule("ib-42"); //$NON-NLS-1$
        assertEquals("ib-42", StandaloneServerSupport.infobaseIdOf(module)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInfobaseIdOfReturnsNullWhenIdIsNull()
    {
        // getInfobaseId() returns null -> the method must return null (not "null").
        Object module = new FakeInfobaseModule(null);
        assertNull(StandaloneServerSupport.infobaseIdOf(module));
    }

    @Test
    public void testInfobaseIdOfReturnsNullWhenMethodAbsent()
    {
        // An object without getInfobaseId() -> NoSuchMethodException is swallowed -> null.
        assertNull(StandaloneServerSupport.infobaseIdOf(new Object()));
    }

    @Test
    public void testInfobaseIdOfUsesToStringOfNonStringId()
    {
        // A UUID-like non-String id is rendered via toString().
        Object module = new FakeInfobaseModule(Integer.valueOf(7));
        assertEquals("7", StandaloneServerSupport.infobaseIdOf(module)); //$NON-NLS-1$
    }

    // ==================== databaseDirOf ====================

    @Test
    public void testDatabaseDirOfReturnsConfigDirectoryForFileDatabase()
    {
        // Full happy chain: configuration -> file database -> getConfigDirectory() (a String).
        Object module = new FakeServerInfobaseModule(
            new FakeConfiguration(new FakeFileDatabase("C:/data/ib"))); //$NON-NLS-1$
        assertEquals("C:/data/ib", StandaloneServerSupport.databaseDirOf(module)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDatabaseDirOfReturnsNullWhenConfigurationIsNull()
    {
        // getStandaloneServerConfiguration() returns null -> null.
        Object module = new FakeServerInfobaseModule(null);
        assertNull(StandaloneServerSupport.databaseDirOf(module));
    }

    @Test
    public void testDatabaseDirOfReturnsNullWhenDatabaseIsNull()
    {
        // getDatabase() returns null -> null.
        Object module = new FakeServerInfobaseModule(new FakeConfiguration(null));
        assertNull(StandaloneServerSupport.databaseDirOf(module));
    }

    @Test
    public void testDatabaseDirOfReturnsNullForRdbmsDatabaseWithoutConfigDirectory()
    {
        // An RDBMS database has no getConfigDirectory() -> NoSuchMethodException -> null.
        Object module = new FakeServerInfobaseModule(
            new FakeConfiguration(new FakeRdbmsDatabase()));
        assertNull(StandaloneServerSupport.databaseDirOf(module));
    }

    @Test
    public void testDatabaseDirOfReturnsNullWhenConfigDirectoryIsNotAString()
    {
        // getConfigDirectory() exists but returns a non-String -> null (instanceof guard).
        Object module = new FakeServerInfobaseModule(
            new FakeConfiguration(new FakeNonStringDirDatabase()));
        assertNull(StandaloneServerSupport.databaseDirOf(module));
    }

    @Test
    public void testDatabaseDirOfReturnsNullWhenModuleHasNoConfigurationMethod()
    {
        // The outer getStandaloneServerConfiguration() is absent -> Throwable caught -> null.
        assertNull(StandaloneServerSupport.databaseDirOf(new Object()));
    }

    // ==================== findServerByModuleName ====================

    @Test
    public void testFindServerByModuleNameReturnsMatchingServer()
    {
        FakeServer match = new FakeServer(new FakeModule("MyServer")); //$NON-NLS-1$
        FakeServer other = new FakeServer(new FakeModule("OtherServer")); //$NON-NLS-1$
        FakeStandaloneServerService service =
            new FakeStandaloneServerService(Arrays.asList(other, match));
        Object found = StandaloneServerSupport.findServerByModuleName(service, "MyServer"); //$NON-NLS-1$
        assertSame(match, found);
    }

    @Test
    public void testFindServerByModuleNameReturnsNullWhenNoModuleMatches()
    {
        FakeServer s1 = new FakeServer(new FakeModule("A")); //$NON-NLS-1$
        FakeServer s2 = new FakeServer(new FakeModule("B")); //$NON-NLS-1$
        FakeStandaloneServerService service =
            new FakeStandaloneServerService(Arrays.asList(s1, s2));
        assertNull(StandaloneServerSupport.findServerByModuleName(service, "Missing")); //$NON-NLS-1$
    }

    @Test
    public void testFindServerByModuleNameReturnsNullWhenGetServersAbsent()
    {
        // A service object without getServers() -> findMethod returns null -> null.
        assertNull(StandaloneServerSupport.findServerByModuleName(new Object(), "Any")); //$NON-NLS-1$
    }

    @Test
    public void testFindServerByModuleNameReturnsNullWhenGetServersReturnsNonList()
    {
        // getServers() returns something that is not a List -> null.
        assertNull(StandaloneServerSupport.findServerByModuleName(
            new FakeServiceWithNonListServers(), "Any")); //$NON-NLS-1$
    }

    @Test
    public void testFindServerByModuleNameSkipsNullServersInList()
    {
        // A null entry in the servers list must be skipped without NPE; match still found after it.
        FakeServer match = new FakeServer(new FakeModule("Target")); //$NON-NLS-1$
        List<Object> servers = new ArrayList<>();
        servers.add(null);
        servers.add(match);
        FakeStandaloneServerService service = new FakeStandaloneServerService(servers);
        assertSame(match, StandaloneServerSupport.findServerByModuleName(service, "Target")); //$NON-NLS-1$
    }

    @Test
    public void testFindServerByModuleNameReturnsNullForEmptyServerList()
    {
        FakeStandaloneServerService service =
            new FakeStandaloneServerService(Collections.emptyList());
        assertNull(StandaloneServerSupport.findServerByModuleName(service, "Any")); //$NON-NLS-1$
    }

    // ==================== deleteServer ====================

    @Test
    public void testDeleteServerReturnsErrorStatusWhenMethodAbsent()
    {
        // A service without deleteServer(2 args) -> a synthesized ERROR status (NOT mistaken success).
        try
        {
            IStatus status = StandaloneServerSupport.deleteServer(new Object(), new Object(), MONITOR);
            assertNotNull(status);
            assertEquals(IStatus.ERROR, status.getSeverity());
            assertFalse(status.isOK());
        }
        catch (Exception e)
        {
            fail("deleteServer must not throw when the method is simply absent: " + e); //$NON-NLS-1$
        }
    }

    @Test
    public void testDeleteServerReturnsStatusFromInvokedMethod()
    {
        // A service whose deleteServer(Object, Object) returns an OK IStatus -> that status is returned.
        IStatus ok = org.eclipse.core.runtime.Status.OK_STATUS;
        FakeDeleteService service = new FakeDeleteService(ok, null);
        try
        {
            IStatus status = StandaloneServerSupport.deleteServer(service, new Object(), MONITOR);
            assertSame(ok, status);
        }
        catch (Exception e)
        {
            fail("deleteServer must not throw on a clean OK return: " + e); //$NON-NLS-1$
        }
    }

    @Test
    public void testDeleteServerReturnsNullWhenInvokedMethodReturnsNonStatus()
    {
        // deleteServer() exists but returns a non-IStatus -> the wrapper returns null.
        FakeDeleteService service = new FakeDeleteService("not-a-status", null); //$NON-NLS-1$
        try
        {
            assertNull(StandaloneServerSupport.deleteServer(service, new Object(), MONITOR));
        }
        catch (Exception e)
        {
            fail("deleteServer must not throw when the return type is unexpected: " + e); //$NON-NLS-1$
        }
    }

    @Test
    public void testDeleteServerPropagatesInvocationException()
    {
        // The invoked deleteServer() throws a checked Exception -> it is unwrapped and rethrown.
        Exception boom = new java.io.IOException("delete failed"); //$NON-NLS-1$
        FakeDeleteService service = new FakeDeleteService(null, boom);
        try
        {
            StandaloneServerSupport.deleteServer(service, new Object(), MONITOR);
            fail("deleteServer must rethrow the underlying invocation failure"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertSame("the original cause must be unwrapped from InvocationTargetException", //$NON-NLS-1$
                boom, e);
        }
    }

    // ==================== serverOfApplication / moduleOfApplication (null/failure branch) ====================

    @Test
    public void testServerOfApplicationReturnsNullOnFailure()
    {
        // Passing null triggers the catch(Throwable) branch -> returns null, never throws.
        assertNull(StandaloneServerSupport.serverOfApplication(null));
    }

    @Test
    public void testModuleOfApplicationReturnsNullOnFailure()
    {
        assertNull(StandaloneServerSupport.moduleOfApplication(null));
    }

    // ==================== removeFromInfobaseRegistry ====================

    @Test
    public void testRemoveFromInfobaseRegistryNullIdReturnsFailed()
    {
        // PURE branch (no OSGi): a null infobaseId can target no entry -> FAILED (honest, not NOT_PRESENT).
        assertSame(RegistryCleanup.FAILED,
            StandaloneServerSupport.removeFromInfobaseRegistry(null, MONITOR));
    }

    @Test
    public void testRemoveFromInfobaseRegistryWithIdDegradesGracefully()
    {
        // The WST server-core bundle is intentionally NOT a host dependency, so in the headless test
        // runtime the cleanup cannot run: it must return a non-null RegistryCleanup (FAILED) and never
        // throw. (On a real EDT with the feature installed this is where REMOVED/NOT_PRESENT happen.)
        RegistryCleanup result =
            StandaloneServerSupport.removeFromInfobaseRegistry("some-id", MONITOR); //$NON-NLS-1$
        assertNotNull(result);
    }

    // ==================== acquireService ====================

    @Test
    public void testAcquireServiceDegradesGracefullyWhenFeatureAbsent()
    {
        // The standalone-server WST bundle is not a host dependency; in the headless test runtime
        // acquireService() must degrade gracefully (return null, never throw).
        try
        {
            assertNull(StandaloneServerSupport.acquireService());
        }
        catch (Throwable t)
        {
            fail("acquireService must never throw, even when the feature is absent: " + t); //$NON-NLS-1$
        }
    }

    // ==================== Fakes (plain classes the reflective wrappers introspect) ====================

    /** Stands in for {@code StandaloneServerInfobase} for {@code infobaseIdOf}. */
    public static final class FakeInfobaseModule
    {
        private final Object id;

        FakeInfobaseModule(Object id)
        {
            this.id = id;
        }

        public Object getInfobaseId()
        {
            return id;
        }
    }

    /** Stands in for the wst-server infobase module for {@code databaseDirOf}. */
    public static final class FakeServerInfobaseModule
    {
        private final Object configuration;

        FakeServerInfobaseModule(Object configuration)
        {
            this.configuration = configuration;
        }

        public Object getStandaloneServerConfiguration()
        {
            return configuration;
        }
    }

    /** Standalone-server configuration holding the database object. */
    public static final class FakeConfiguration
    {
        private final Object database;

        FakeConfiguration(Object database)
        {
            this.database = database;
        }

        public Object getDatabase()
        {
            return database;
        }
    }

    /** File-backed database: exposes getConfigDirectory() returning a String. */
    public static final class FakeFileDatabase
    {
        private final String dir;

        FakeFileDatabase(String dir)
        {
            this.dir = dir;
        }

        public Object getConfigDirectory()
        {
            return dir;
        }
    }

    /** RDBMS database: deliberately has NO getConfigDirectory() method. */
    public static final class FakeRdbmsDatabase
    {
        // no getConfigDirectory()
    }

    /** File-like database whose getConfigDirectory() returns a non-String (the instanceof guard). */
    public static final class FakeNonStringDirDatabase
    {
        public Object getConfigDirectory()
        {
            return new java.io.File("x"); //$NON-NLS-1$
        }
    }

    /** A WST module with a display name, scanned by {@code findServerByModuleName}. */
    public static final class FakeModule
    {
        private final String name;

        FakeModule(String name)
        {
            this.name = name;
        }

        public String getName()
        {
            return name;
        }
    }

    /** A WST {@code IServer} stand-in exposing getModules():Object[]. */
    public static final class FakeServer
    {
        private final FakeModule[] modules;

        FakeServer(FakeModule... modules)
        {
            this.modules = modules;
        }

        public Object getModules()
        {
            return modules;
        }
    }

    /** The standalone-server service stand-in for {@code findServerByModuleName}. */
    public static final class FakeStandaloneServerService
    {
        private final List<?> servers;

        FakeStandaloneServerService(List<?> servers)
        {
            this.servers = servers;
        }

        public List<?> getServers()
        {
            return servers;
        }
    }

    /** A service whose getServers() returns a non-List (the type guard branch). */
    public static final class FakeServiceWithNonListServers
    {
        public Object getServers()
        {
            return "not a list"; //$NON-NLS-1$
        }
    }

    /**
     * A service stand-in for {@code deleteServer}: a 2-arg deleteServer(Object, Object) that either
     * returns {@code result} or, when {@code toThrow} is set, throws it (to exercise the
     * InvocationTargetException-unwrap path).
     */
    public static final class FakeDeleteService
    {
        private final Object result;
        private final Exception toThrow;

        FakeDeleteService(Object result, Exception toThrow)
        {
            this.result = result;
            this.toThrow = toThrow;
        }

        public Object deleteServer(Object server, Object monitor) throws Exception
        {
            if (toThrow != null)
            {
                throw toThrow;
            }
            return result;
        }
    }
}
