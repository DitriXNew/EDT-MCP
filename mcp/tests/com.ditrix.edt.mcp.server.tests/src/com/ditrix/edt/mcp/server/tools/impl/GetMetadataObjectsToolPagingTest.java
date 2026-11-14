/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.InternalEList;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Issue #932: the roles of an ERP-size configuration (2,000+) could not all be listed - {@code limit}
 * stops at 1000, and the only way past it was guessing names for a filter.
 *
 * <p>{@code offset} now pages the listing. A configuration collection holds REFERENCES to BM top
 * objects and reading an element resolves it, so a page without a filter must read only the objects
 * it shows: the Total is the collection's size, not a walk over every element. These tests model the
 * collection with EMF proxies whose resolution is counted.</p>
 */
public class GetMetadataObjectsToolPagingTest
{
    private static final String ROLE = "Role"; //$NON-NLS-1$

    /** Resolves {@code test:/<Name>} proxies to a fresh Role named {@code Name}, counting each resolution. */
    private static final class CountingResourceSet extends ResourceSetImpl
    {
        int resolutions;

        @Override
        public EObject getEObject(URI uri, boolean loadOnDemand)
        {
            resolutions++;
            String name = uri.fragment();
            if (name.startsWith("Catalog")) //$NON-NLS-1$
            {
                Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
                catalog.setName(name);
                return catalog;
            }
            Role role = MdClassFactory.eINSTANCE.createRole();
            role.setName(name);
            role.getSynonym().put("en", "Synonym of " + name); //$NON-NLS-1$ //$NON-NLS-2$
            return role;
        }
    }

    private final CountingResourceSet resources = new CountingResourceSet();

    /** A configuration whose roles are {@code roles} unresolved proxies named Role0..Role(n-1). */
    private Configuration configurationWithRoleProxies(int roles)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Resource resource = new ResourceImpl(URI.createURI("test:/configuration")); //$NON-NLS-1$
        resources.getResources().add(resource);
        resource.getContents().add(config);
        @SuppressWarnings("unchecked")
        InternalEList<Role> list = (InternalEList<Role>)config.getRoles();
        for (int i = 0; i < roles; i++)
        {
            list.addUnique(proxy(MdClassFactory.eINSTANCE.createRole(), "Role" + i)); //$NON-NLS-1$
        }
        return config;
    }

    private static <T extends EObject> T proxy(T object, String name)
    {
        ((InternalEObject)object).eSetProxyURI(URI.createURI("test:/objects#" + name)); //$NON-NLS-1$
        return object;
    }

    private static List<String> names(List<GetMetadataObjectsTool.MetadataInfo> rows)
    {
        List<String> names = new ArrayList<>();
        for (GetMetadataObjectsTool.MetadataInfo row : rows)
        {
            names.add(row.name);
        }
        return names;
    }

    @Test
    public void testAPageWithoutAFilterLoadsOnlyTheRolesItShows()
    {
        Configuration config = configurationWithRoleProxies(500);
        List<GetMetadataObjectsTool.MetadataInfo> rows = new ArrayList<>();

        int total = new GetMetadataObjectsTool().collectMetadataObjects(config,
            MetadataTypeUtils.resolve(ROLE), rows, null, GetMetadataObjectsTool.FilterMode.NAME, 10,
            "en"); //$NON-NLS-1$

        assertEquals("the Total still counts every role", 500, total); //$NON-NLS-1$
        assertEquals(10, rows.size());
        assertEquals("Role0", rows.get(0).name); //$NON-NLS-1$
        assertEquals("Synonym of Role9", rows.get(9).synonyms.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("only the 10 roles on the page may be loaded, not all 500", 10, //$NON-NLS-1$
            resources.resolutions);
    }

    @Test
    public void testAnOffsetPageLoadsOnlyItsOwnRoles()
    {
        Configuration config = configurationWithRoleProxies(500);
        GetMetadataObjectsTool.PageWindow page = new GetMetadataObjectsTool.PageWindow(250, 3);

        int total = new GetMetadataObjectsTool().collectConfiguration(config, ROLE, page, null,
            GetMetadataObjectsTool.FilterMode.NAME, "en"); //$NON-NLS-1$

        assertEquals(500, total);
        assertEquals(List.of("Role250", "Role251", "Role252"), names(page.rows)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(3, resources.resolutions);
    }

    @Test
    public void testAnOffsetPastTheEndLoadsNothingAndKeepsTheTotal()
    {
        Configuration config = configurationWithRoleProxies(40);
        GetMetadataObjectsTool.PageWindow page = new GetMetadataObjectsTool.PageWindow(40, 10);

        int total = new GetMetadataObjectsTool().collectConfiguration(config, ROLE, page, null,
            GetMetadataObjectsTool.FilterMode.NAME, "en"); //$NON-NLS-1$

        assertEquals(40, total);
        assertTrue(page.rows.isEmpty());
        assertEquals(0, resources.resolutions);
    }

    @Test
    public void testTheAllListingLoadsOnlyThePageAcrossTypes()
    {
        Configuration config = configurationWithRoleProxies(300);
        @SuppressWarnings("unchecked")
        InternalEList<Catalog> catalogs = (InternalEList<Catalog>)config.getCatalogs();
        for (int i = 0; i < 4; i++)
        {
            catalogs.addUnique(proxy(MdClassFactory.eINSTANCE.createCatalog(), "Catalog" + i)); //$NON-NLS-1$
        }
        GetMetadataObjectsTool.PageWindow page = new GetMetadataObjectsTool.PageWindow(0, 6);

        int total = new GetMetadataObjectsTool().collectConfiguration(config, "all", page, null, //$NON-NLS-1$
            GetMetadataObjectsTool.FilterMode.NAME, "en"); //$NON-NLS-1$

        assertEquals("the Total spans every collection", 304, total); //$NON-NLS-1$
        assertEquals(6, page.rows.size());
        assertEquals("an all page loads its own rows only", 6, resources.resolutions); //$NON-NLS-1$
    }

    @Test
    public void testAnAllPageContinuesIntoTheNextTypeAfterTheOffset()
    {
        Configuration config = configurationWithRoleProxies(5);
        @SuppressWarnings("unchecked")
        InternalEList<Catalog> catalogs = (InternalEList<Catalog>)config.getCatalogs();
        for (int i = 0; i < 3; i++)
        {
            catalogs.addUnique(proxy(MdClassFactory.eINSTANCE.createCatalog(), "Catalog" + i)); //$NON-NLS-1$
        }
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        GetMetadataObjectsTool.PageWindow first = new GetMetadataObjectsTool.PageWindow(0, 4);
        GetMetadataObjectsTool.PageWindow second = new GetMetadataObjectsTool.PageWindow(4, 4);

        int total = tool.collectConfiguration(config, "all", first, null, //$NON-NLS-1$
            GetMetadataObjectsTool.FilterMode.NAME, "en"); //$NON-NLS-1$
        tool.collectConfiguration(config, "all", second, null, //$NON-NLS-1$
            GetMetadataObjectsTool.FilterMode.NAME, "en"); //$NON-NLS-1$

        assertEquals(8, total);
        List<String> both = new ArrayList<>(names(first.rows));
        both.addAll(names(second.rows));
        assertEquals("two pages cover every object once, in order", 8, both.size()); //$NON-NLS-1$
        assertEquals(8, both.stream().distinct().count());
        assertTrue(both.containsAll(List.of("Catalog0", "Catalog1", "Catalog2", "Role0", "Role4"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void testAFilteredPageSkipsMatchesBeforeTheOffset()
    {
        Configuration config = configurationWithRoleProxies(200);
        GetMetadataObjectsTool.PageWindow page = new GetMetadataObjectsTool.PageWindow(5, 3);

        // "Role1" matches Role1, Role10..Role19, Role100..Role199: 111 roles, in list order.
        int total = new GetMetadataObjectsTool().collectConfiguration(config, ROLE, page, "role1", //$NON-NLS-1$
            GetMetadataObjectsTool.FilterMode.NAME, "en"); //$NON-NLS-1$

        assertEquals(111, total);
        assertEquals(List.of("Role14", "Role15", "Role16"), names(page.rows)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testTheToolDeclaresOffset()
    {
        String schema = new GetMetadataObjectsTool().getInputSchema();
        assertTrue(schema, schema.contains("\"offset\"")); //$NON-NLS-1$
    }

    @Test
    public void testTheTotalLineNamesTheNextPage() throws Exception
    {
        try (var fixture = new com.ditrix.edt.mcp.server.utils.HeadlessReadFixture())
        {
            String first = fixture.call(new GetMetadataObjectsTool(),
                Map.of("metadataType", "CommonModule", "limit", "1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue(first, first.contains("(showing 1-1 of 2; next page: offset=1)")); //$NON-NLS-1$
            assertTrue(first, first.contains("| EnglishName |")); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheOffsetArgumentReachesTheSecondPage() throws Exception
    {
        try (var fixture = new com.ditrix.edt.mcp.server.utils.HeadlessReadFixture())
        {
            String second = fixture.call(new GetMetadataObjectsTool(),
                Map.of("metadataType", "CommonModule", "limit", "1", "offset", "1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            assertTrue(second, second.contains("(showing 2-2 of 2)")); //$NON-NLS-1$
            assertTrue(second, second.contains("| Код |")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAnOffsetPastTheEndSaysWhyThePageIsEmpty() throws Exception
    {
        try (var fixture = new com.ditrix.edt.mcp.server.utils.HeadlessReadFixture())
        {
            String past = fixture.call(new GetMetadataObjectsTool(),
                Map.of("metadataType", "CommonModule", "offset", "5")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue(past, past.contains("**Total:** 2 objects\n")); //$NON-NLS-1$
            assertTrue(past, past.contains("offset=5 is past the last of the 2 matching objects")); //$NON-NLS-1$
        }
    }
}
