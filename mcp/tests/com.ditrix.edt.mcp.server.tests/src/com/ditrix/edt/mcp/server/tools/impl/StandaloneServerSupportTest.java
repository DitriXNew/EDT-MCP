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
import static org.junit.Assert.assertTrue;
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
        // #273: an object with NEITHER the 2025.2 shape (getInfobaseId()) NOR the 2026.1 fallback
        // chain (getStandaloneServerConfiguration()) -> both NoSuchMethodExceptions are swallowed ->
        // null, and the "both shapes missing" error is LOGGED, never thrown.
        assertNull(StandaloneServerSupport.infobaseIdOf(new Object()));
    }

    @Test
    public void testInfobaseIdOfUsesToStringOfNonStringId()
    {
        // A UUID-like non-String id is rendered via toString().
        Object module = new FakeInfobaseModule(Integer.valueOf(7));
        assertEquals("7", StandaloneServerSupport.infobaseIdOf(module)); //$NON-NLS-1$
    }

    @Test
    public void testInfobaseIdOfReadsUuidViaGetInfobaseId()
    {
        // The real 2025.2 shape: getInfobaseId() returns a java.util.UUID -> its toString() is
        // returned (the raw id string that keys the infobases.yaml registry entry).
        java.util.UUID uuid = java.util.UUID.randomUUID();
        Object module = new FakeInfobaseModule(uuid);
        assertEquals(uuid.toString(), StandaloneServerSupport.infobaseIdOf(module));
    }

    // ---- #273: 2026.1 fallback shape — getStandaloneServerConfiguration().getInfobase().getId() ----

    @Test
    public void testInfobaseIdOfFallsBackToConfigurationChainWhenGetInfobaseIdAbsent()
    {
        // 2026.1: getInfobaseId() is GONE (no replacement); the raw id lives instead at
        // getStandaloneServerConfiguration().getInfobase().getId(): String.
        Object module = new FakeInfobaseModule2026Only(
            new Fake2026Configuration(new Fake2026Infobase("ib-2026"))); //$NON-NLS-1$
        assertEquals("ib-2026", StandaloneServerSupport.infobaseIdOf(module)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInfobaseIdOfFallbackIsNullSafeWhenConfigurationIsNull()
    {
        // getStandaloneServerConfiguration() present but returns null -> null-safe, no NPE.
        Object module = new FakeInfobaseModule2026Only(null);
        assertNull(StandaloneServerSupport.infobaseIdOf(module));
    }

    @Test
    public void testInfobaseIdOfFallbackIsNullSafeWhenInfobaseIsNull()
    {
        // getInfobase() present but returns null -> null-safe, no NPE.
        Object module = new FakeInfobaseModule2026Only(new Fake2026Configuration(null));
        assertNull(StandaloneServerSupport.infobaseIdOf(module));
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
        // An RDBMS database has NEITHER getConfigDirectory() (2025.2) NOR getPath() (2026.1) -> null.
        Object module = new FakeServerInfobaseModule(
            new FakeConfiguration(new FakeRdbmsDatabase()));
        assertNull(StandaloneServerSupport.databaseDirOf(module));
    }

    @Test
    public void testDatabaseDirOfReadsGetPathOn2026Shape()
    {
        // #273: 2026.1 renamed FileDatabase.getConfigDirectory() -> getPath(); the read must accept
        // either accessor (this is why deleteDatabaseFiles resolved nothing on 2026.1).
        Object module = new FakeServerInfobaseModule(
            new FakeConfiguration(new Fake2026PathDatabase("C:/data/ib2026"))); //$NON-NLS-1$
        assertEquals("C:/data/ib2026", StandaloneServerSupport.databaseDirOf(module)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDatabaseDirOfReturnsNullWhenGetPathIsNotAString()
    {
        // #273: getPath() exists but returns a non-String -> null (same instanceof guard as 2025.2).
        Object module = new FakeServerInfobaseModule(
            new FakeConfiguration(new FakeNonStringPathDatabase()));
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
    public void testRemoveFromInfobaseRegistryNullModuleAndIdReturnsFailed()
    {
        // PURE branch (no OSGi): with NEITHER the module NOR the raw id, no entry can be targeted ->
        // FAILED (honest, not NOT_PRESENT).
        assertSame(RegistryCleanup.FAILED,
            StandaloneServerSupport.removeFromInfobaseRegistry(null, null, MONITOR));
    }

    @Test
    public void testRemoveFromInfobaseRegistryWithIdDegradesGracefully()
    {
        // The WST server-core bundle is intentionally NOT a host dependency, so in the headless test
        // runtime the cleanup cannot run: it must return a non-null RegistryCleanup (FAILED) and never
        // throw. (On a real EDT with the feature installed this is where REMOVED/NOT_PRESENT happen.)
        RegistryCleanup result =
            StandaloneServerSupport.removeFromInfobaseRegistry(null, "some-id", MONITOR); //$NON-NLS-1$
        assertNotNull(result);
    }

    @Test
    public void testRemoveFromInfobaseRegistryWithModuleOnlyDegradesGracefully()
    {
        // A module without a raw id (the 2026.1 shape when only the instance is known) must also
        // degrade gracefully in the headless runtime — non-null result, never a throw.
        RegistryCleanup result = StandaloneServerSupport.removeFromInfobaseRegistry(
            new FakeIdModule("prefixed#abc"), null, MONITOR); //$NON-NLS-1$
        assertNotNull(result);
    }

    // ==================== removeModuleEntries (#273: the version-proof map surgery) ====================
    // The delegate's modules-map KEY scheme differs per EDT version: 2025.2 keys by the raw uuid,
    // 2026.1 by the PREFIXED StandaloneServerInfobase.getId() module-id — so key-based removal by the
    // raw id silently misses on 2026.1. The extracted seam is tested here strategy by strategy; the
    // delegate/mapper/location plumbing around it stays live-verified.

    @Test
    public void testRemoveModuleEntriesByValueIdentity()
    {
        // Strategy 1: the map value IS the passed instance — removed regardless of the key scheme
        // (here the 2026.1-style prefixed key, which the raw id would miss).
        FakeIdModule module = new FakeIdModule("srv#raw-1"); //$NON-NLS-1$
        java.util.Map<Object, Object> modules = new java.util.HashMap<>();
        modules.put("srv#raw-1", module); //$NON-NLS-1$
        assertEquals(1, StandaloneServerSupport.removeModuleEntries(modules, module, "raw-1")); //$NON-NLS-1$
        assertTrue(modules.isEmpty());
    }

    @Test
    public void testRemoveModuleEntriesIdentityDoesNotTouchOtherEntries()
    {
        // Identity removal must be surgical: an unrelated entry (even one with the SAME getId) stays,
        // because strategy 2 only runs when strategy 1 removed nothing.
        FakeIdModule module = new FakeIdModule("srv#raw-1"); //$NON-NLS-1$
        FakeIdModule twin = new FakeIdModule("srv#raw-1"); //$NON-NLS-1$
        java.util.Map<Object, Object> modules = new java.util.HashMap<>();
        modules.put("k1", module); //$NON-NLS-1$
        modules.put("k2", twin); //$NON-NLS-1$
        assertEquals(1, StandaloneServerSupport.removeModuleEntries(modules, module, null));
        assertSame(twin, modules.get("k2")); //$NON-NLS-1$
        assertEquals(1, modules.size());
    }

    @Test
    public void testRemoveModuleEntriesByGetIdEquality()
    {
        // Strategy 2: a DIFFERENT instance with the SAME getId() (a re-created module) is matched by
        // reflective getId() String equality when identity misses.
        java.util.Map<Object, Object> modules = new java.util.HashMap<>();
        modules.put("srv#raw-2", new FakeIdModule("srv#raw-2")); //$NON-NLS-1$ //$NON-NLS-2$
        FakeIdModule sameIdOtherInstance = new FakeIdModule("srv#raw-2"); //$NON-NLS-1$
        assertEquals(1,
            StandaloneServerSupport.removeModuleEntries(modules, sameIdOtherInstance, null));
        assertTrue(modules.isEmpty());
    }

    @Test
    public void testRemoveModuleEntriesByRawIdKeyFallback()
    {
        // Strategy 3: identity and getId both miss (value has no matching id) -> the proven 2025.2
        // key-based removal by the raw id still fires.
        java.util.Map<Object, Object> modules = new java.util.HashMap<>();
        modules.put("raw-3", new Object()); //$NON-NLS-1$
        assertEquals(1, StandaloneServerSupport.removeModuleEntries(modules,
            new FakeIdModule("srv#other"), "raw-3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(modules.isEmpty());
    }

    @Test
    public void testRemoveModuleEntriesKeyFallbackWithNullModule()
    {
        // module == null (the pre-#273 caller shape): only the key-based strategy applies.
        java.util.Map<Object, Object> modules = new java.util.HashMap<>();
        modules.put("raw-4", new Object()); //$NON-NLS-1$
        assertEquals(1, StandaloneServerSupport.removeModuleEntries(modules, null, "raw-4")); //$NON-NLS-1$
        assertTrue(modules.isEmpty());
    }

    @Test
    public void testRemoveModuleEntriesFullMissReturnsZero()
    {
        // No identity, no id equality, no key match -> 0 (the caller maps this to NOT_PRESENT).
        java.util.Map<Object, Object> modules = new java.util.HashMap<>();
        modules.put("other-key", new FakeIdModule("srv#other")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, StandaloneServerSupport.removeModuleEntries(modules,
            new FakeIdModule("srv#mine"), "raw-mine")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, modules.size());
    }

    @Test
    public void testRemoveModuleEntriesToleratesValuesWithoutGetId()
    {
        // Strategy 2 must skip values that have no getId() (or are null) without throwing.
        java.util.Map<Object, Object> modules = new java.util.HashMap<>();
        modules.put("k1", new Object()); //$NON-NLS-1$
        modules.put("k2", null); //$NON-NLS-1$
        assertEquals(0, StandaloneServerSupport.removeModuleEntries(modules,
            new FakeIdModule("srv#raw-5"), null)); //$NON-NLS-1$
        assertEquals(2, modules.size());
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

    /**
     * #273: a registry module with a {@code getId()} — on 2026.1 that PREFIXED module-id string is the
     * delegate's map key. Used by the {@code removeModuleEntries} strategy tests.
     */
    public static final class FakeIdModule
    {
        private final String id;

        FakeIdModule(String id)
        {
            this.id = id;
        }

        public String getId()
        {
            return id;
        }
    }

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

    /**
     * #273: a module exposing ONLY the 2026.1 config-chain fallback for {@code infobaseIdOf} —
     * deliberately has NO {@code getInfobaseId()}, mirroring EDT 2026.1 where that method was removed.
     */
    public static final class FakeInfobaseModule2026Only
    {
        private final Object configuration;

        FakeInfobaseModule2026Only(Object configuration)
        {
            this.configuration = configuration;
        }

        public Object getStandaloneServerConfiguration()
        {
            return configuration;
        }
    }

    /** #273: stands in for the 2026.1 {@code StandaloneServerConfiguration}'s {@code getInfobase()} hop. */
    public static final class Fake2026Configuration
    {
        private final Object infobase;

        Fake2026Configuration(Object infobase)
        {
            this.infobase = infobase;
        }

        public Object getInfobase()
        {
            return infobase;
        }
    }

    /** #273: stands in for the 2026.1 {@code Infobase}, holding the raw id via {@code getId(): String}. */
    public static final class Fake2026Infobase
    {
        private final String id;

        Fake2026Infobase(String id)
        {
            this.id = id;
        }

        public String getId()
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

    /** RDBMS database: deliberately has NEITHER getConfigDirectory() (2025.2) nor getPath() (2026.1). */
    public static final class FakeRdbmsDatabase
    {
        // no getConfigDirectory() / getPath()
    }

    /** File-like database whose getConfigDirectory() returns a non-String (the instanceof guard). */
    public static final class FakeNonStringDirDatabase
    {
        public Object getConfigDirectory()
        {
            return new java.io.File("x"); //$NON-NLS-1$
        }
    }

    /** #273: a 2026.1-shaped file database — the directory accessor was RENAMED to getPath(). */
    public static final class Fake2026PathDatabase
    {
        private final String path;

        Fake2026PathDatabase(String path)
        {
            this.path = path;
        }

        public String getPath()
        {
            return path;
        }
    }

    /** #273: a 2026.1-shaped database whose getPath() returns a non-String (the instanceof guard). */
    public static final class FakeNonStringPathDatabase
    {
        public Object getPath()
        {
            return new java.io.File("y"); //$NON-NLS-1$
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
