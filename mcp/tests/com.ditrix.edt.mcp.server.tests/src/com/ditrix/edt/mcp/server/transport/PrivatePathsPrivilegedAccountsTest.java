// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.PrivatePaths;

public class PrivatePathsPrivilegedAccountsTest extends StdioTestSupport
{
    private static final String SYSTEM_SID = "S-1-5-18";
    private static final String ADMINISTRATORS_SID = "S-1-5-32-544";
    private static final String LOCAL_SERVICE_SID = "S-1-5-19";
    private static final String USERS_SID = "S-1-5-32-545";

    /** Mirrors the JDK's Windows group principal: identity and hash come from the SID string. */
    private static final class FakeGroup implements GroupPrincipal
    {
        private final String identity;
        private final int hash;

        FakeGroup(String sid) { this(sid, sid.hashCode()); }
        FakeGroup(String identity, int hash) { this.identity = identity; this.hash = hash; }
        @Override public String getName() { return identity; }
        @Override public boolean equals(Object other) { return other instanceof FakeGroup group && group.identity.equals(identity); }
        @Override public int hashCode() { return hash; }
    }

    private static final class FakeUser implements UserPrincipal
    {
        private final String sid;

        FakeUser(String sid) { this.sid = sid; }
        @Override public String getName() { return sid; }
        @Override public boolean equals(Object other) { return other instanceof FakeUser user && user.sid.equals(sid); }
        @Override public int hashCode() { return sid.hashCode(); }
    }

    /** Reports the requested hash, yet compares equal to every instance of itself. */
    private static final class IndistinctGroup implements GroupPrincipal
    {
        private final String sid;

        IndistinctGroup(String sid) { this.sid = sid; }
        @Override public String getName() { return sid; }
        @Override public boolean equals(Object other) { return other instanceof IndistinctGroup; }
        @Override public int hashCode() { return sid.hashCode(); }
    }

    private static AclEntry ace(UserPrincipal principal)
    {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
            .setPermissions(AclEntryPermission.READ_DATA).build();
    }

    private static List<AclEntry> acl(UserPrincipal... principals)
    {
        var entries = new ArrayList<AclEntry>();
        for (UserPrincipal principal : principals) { entries.add(ace(principal)); }
        return entries;
    }

    private static void assertRefused(UserPrincipal... principals)
    { assertThrows(IOException.class, () -> PrivatePaths.selectWindowsPrivilegedAccounts(acl(principals))); }

    @Test public void selectsBothWellKnownAccountsAmongOtherPrincipals() throws Exception
    {
        UserPrincipal system = new FakeGroup(SYSTEM_SID), administrators = new FakeGroup(ADMINISTRATORS_SID);
        UserPrincipal lambda = () -> SYSTEM_SID;
        assertEquals(Set.of(system, administrators), PrivatePaths.selectWindowsPrivilegedAccounts(acl(
            new FakeGroup(USERS_SID), system, new FakeUser(LOCAL_SERVICE_SID), lambda,
            new FakeGroup(LOCAL_SERVICE_SID), administrators)));
    }

    @Test public void twoEntriesForOnePrincipalCountOnce() throws Exception
    {
        UserPrincipal system = new FakeGroup(SYSTEM_SID), administrators = new FakeGroup(ADMINISTRATORS_SID);
        assertEquals(Set.of(system, administrators), PrivatePaths.selectWindowsPrivilegedAccounts(acl(
            system, new FakeGroup(SYSTEM_SID), administrators, new FakeGroup(ADMINISTRATORS_SID))));
    }

    @Test public void emptyAclIsRefused() { assertRefused(); }

    @Test public void missingSystemIsRefused()
    { assertRefused(new FakeGroup(ADMINISTRATORS_SID), new FakeGroup(USERS_SID)); }

    @Test public void missingAdministratorsIsRefused()
    { assertRefused(new FakeGroup(SYSTEM_SID), new FakeGroup(USERS_SID)); }

    @Test public void twoDistinctPrincipalsWithTheSameHashAreRefused()
    {
        assertRefused(new FakeGroup(SYSTEM_SID), new FakeGroup(ADMINISTRATORS_SID),
            new FakeGroup("not-the-system-account", SYSTEM_SID.hashCode()));
    }

    @Test public void nonGroupWithTheRightHashIsNeverSelected()
    { assertRefused(new FakeUser(SYSTEM_SID), new FakeGroup(ADMINISTRATORS_SID)); }

    @Test public void lambdaPrincipalNeverMatches()
    {
        UserPrincipal lambda = () -> SYSTEM_SID;
        assertRefused(lambda, new FakeGroup(ADMINISTRATORS_SID));
    }

    @Test public void indistinctResultsAreRefusedWithAnIoFailure()
    { assertRefused(new IndistinctGroup(SYSTEM_SID), new IndistinctGroup(ADMINISTRATORS_SID)); }

    @Test public void failureMessageKeepsTheStableValidationPrefix()
    {
        IOException failure = assertThrows(IOException.class,
            () -> PrivatePaths.selectWindowsPrivilegedAccounts(List.of()));
        assertTrue(failure.getMessage(), failure.getMessage().startsWith(
            "Cannot resolve privileged Windows accounts for IPC storage validation"));
    }

    @Test public void ownerOnlyDirectoryHasNoPrivilegedAccountsToRead() throws Exception
    {
        Path directory = PrivatePaths.directory(temporary.newFolder().toPath());
        assertThrows(IOException.class, () -> PrivatePaths.readWindowsPrivilegedAccounts(directory));
    }

    @Test public void missingPathIsRefused()
    {
        Path missing = temporary.getRoot().toPath().resolve("no-such-system-root");
        assertThrows(IOException.class, () -> PrivatePaths.readWindowsPrivilegedAccounts(missing));
    }

    @Test public void windowsReadsTheRealWellKnownAccountsFromTheSystemRoot() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var accounts = EndpointRegistryTest.windowsAccounts();
        assertEquals(Set.of(accounts.get("system"), accounts.get("administrators")),
            PrivatePaths.readWindowsPrivilegedAccounts(Path.of(System.getenv("SystemRoot"))));
    }

    @Test public void windowsAcceptsAFileOwnedByAdministrators() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path file = temporary.newFile().toPath();
        // Only an elevated process may hand a file over to the Administrators group.
        try { Files.setOwner(file, EndpointRegistryTest.windowsAccounts().get("administrators")); }
        catch (FileSystemException e) { Assume.assumeNoException(e); }
        PrivatePaths.verifyOwner(file);
    }
}
