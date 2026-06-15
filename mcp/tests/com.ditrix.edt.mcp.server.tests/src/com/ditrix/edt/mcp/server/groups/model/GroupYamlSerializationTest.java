/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.model;

import static org.junit.Assert.*;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Pure, workspace-free tests for the YAML serialization surface of
 * {@link GroupStorage} and {@link Group}.
 *
 * <p>These exercise the same SnakeYAML configuration that
 * {@code YamlGroupRepository} uses on disk, but entirely in memory via a
 * String round-trip, so no {@code IProject}/{@code ResourcesPlugin} is
 * required. The intent is to cover the model beans' serialization-relevant
 * accessor paths (getters/setters driven by SnakeYAML introspection) and the
 * on-disk YAML contract.</p>
 */
public class GroupYamlSerializationTest
{
    // ==================== SnakeYAML config mirrored from YamlGroupRepository ====================

    /**
     * Serializes a {@link GroupStorage} to a YAML String using the same
     * representer/tag configuration as the production repository's save path.
     */
    private static String dump(GroupStorage storage)
    {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setWidth(120);
        options.setSplitLines(false);

        Representer representer = new SortedKeysRepresenter(options);
        representer.getPropertyUtils().setSkipMissingProperties(true);
        representer.addClassTag(GroupStorage.class, Tag.MAP);
        representer.addClassTag(Group.class, Tag.MAP);

        Yaml yaml = new Yaml(representer, options);
        StringWriter writer = new StringWriter();
        yaml.dump(storage, writer);
        return writer.toString();
    }

    /**
     * Deserializes a {@link GroupStorage} from a YAML String using the same
     * constructor configuration as the production repository's load path.
     */
    private static GroupStorage load(String text)
    {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setTagInspector(tag -> true);
        Constructor constructor = new Constructor(GroupStorage.class, loaderOptions);
        // The dump emits every readable JavaBean property, including derived,
        // setter-less getters (Group#getFullPath / #isEmpty, GroupStorage#
        // getGroupCount / #isEmpty). Tolerate those extra keys on load so the
        // round-trip never trips over a property that has no setter.
        constructor.getPropertyUtils().setSkipMissingProperties(true);
        Yaml yaml = new Yaml(constructor);
        return yaml.load(new StringReader(text));
    }

    /**
     * Custom representer matching the production one: outputs bean keys in
     * sorted order for Git-friendly diffs.
     */
    private static class SortedKeysRepresenter extends Representer
    {
        SortedKeysRepresenter(DumperOptions options)
        {
            super(options);
        }

        @Override
        protected MappingNode representJavaBean(java.util.Set<Property> properties, Object javaBean)
        {
            List<Property> sorted = new java.util.ArrayList<>(properties);
            sorted.sort(Comparator.comparing(Property::getName));
            return super.representJavaBean(new java.util.LinkedHashSet<>(sorted), javaBean);
        }
    }

    // ==================== Round-trip: empty / minimal ====================

    @Test
    public void testEmptyStorageRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        GroupStorage restored = load(dump(original));
        assertNotNull(restored);
        assertTrue(restored.isEmpty());
        assertEquals(0, restored.getGroupCount());
    }

    @Test
    public void testSingleGroupRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        original.addGroup(new Group("Server", "CommonModules")); //$NON-NLS-1$ //$NON-NLS-2$

        GroupStorage restored = load(dump(original));

        assertEquals(1, restored.getGroupCount());
        Group g = restored.getGroupByFullPath("CommonModules/Server"); //$NON-NLS-1$
        assertNotNull(g);
        assertEquals("Server", g.getName()); //$NON-NLS-1$
        assertEquals("CommonModules", g.getPath()); //$NON-NLS-1$
        assertTrue(g.isEmpty());
    }

    // ==================== Round-trip: full field coverage ====================

    @Test
    public void testAllFieldsRoundTrip()
    {
        Group group = new Group("Reports", "Catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        group.setDescription("Grouped reports"); //$NON-NLS-1$
        group.setOrder(7);
        group.addChild("Catalog.A"); //$NON-NLS-1$
        group.addChild("Catalog.B"); //$NON-NLS-1$
        GroupStorage original = new GroupStorage();
        original.addGroup(group);

        GroupStorage restored = load(dump(original));

        Group g = restored.getGroupByFullPath("Catalogs/Reports"); //$NON-NLS-1$
        assertNotNull(g);
        assertEquals("Reports", g.getName()); //$NON-NLS-1$
        assertEquals("Catalogs", g.getPath()); //$NON-NLS-1$
        assertEquals("Grouped reports", g.getDescription()); //$NON-NLS-1$
        assertEquals(7, g.getOrder());
        assertEquals(2, g.getChildren().size());
        assertTrue(g.containsChild("Catalog.A")); //$NON-NLS-1$
        assertTrue(g.containsChild("Catalog.B")); //$NON-NLS-1$
    }

    @Test
    public void testMultipleGroupsRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        Group g1 = new Group("Alpha", "CommonModules"); //$NON-NLS-1$ //$NON-NLS-2$
        g1.setOrder(1);
        g1.addChild("CommonModule.One"); //$NON-NLS-1$
        Group g2 = new Group("Beta", "CommonModules"); //$NON-NLS-1$ //$NON-NLS-2$
        g2.setOrder(2);
        Group g3 = new Group("Root", null); //$NON-NLS-1$
        original.addGroup(g1);
        original.addGroup(g2);
        original.addGroup(g3);

        GroupStorage restored = load(dump(original));

        assertEquals(3, restored.getGroupCount());
        assertNotNull(restored.getGroupByFullPath("CommonModules/Alpha")); //$NON-NLS-1$
        assertNotNull(restored.getGroupByFullPath("CommonModules/Beta")); //$NON-NLS-1$
        assertNotNull(restored.getGroupByFullPath("Root")); //$NON-NLS-1$
    }

    // ==================== Nested / hierarchical groups ====================

    @Test
    public void testNestedGroupsRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        original.addGroup(new Group("Parent", "Root")); //$NON-NLS-1$ //$NON-NLS-2$
        Group child = new Group("Child", "Root/Parent"); //$NON-NLS-1$ //$NON-NLS-2$
        child.addChild("CommonModule.Nested"); //$NON-NLS-1$
        original.addGroup(child);

        GroupStorage restored = load(dump(original));

        assertEquals(2, restored.getGroupCount());
        Group restoredChild = restored.getGroupByFullPath("Root/Parent/Child"); //$NON-NLS-1$
        assertNotNull(restoredChild);
        assertTrue(restoredChild.containsChild("CommonModule.Nested")); //$NON-NLS-1$
    }

    // ==================== Null / default field handling ====================

    @Test
    public void testNullPathRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        Group group = new Group("TopLevel", null); //$NON-NLS-1$
        original.addGroup(group);

        GroupStorage restored = load(dump(original));

        Group g = restored.getGroupByFullPath("TopLevel"); //$NON-NLS-1$
        assertNotNull(g);
        assertNull(g.getPath());
        assertNull(g.getDescription());
    }

    @Test
    public void testDefaultOrderIsZeroAfterRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        original.addGroup(new Group("G", "P")); //$NON-NLS-1$ //$NON-NLS-2$

        GroupStorage restored = load(dump(original));

        assertEquals(0, restored.getGroupByFullPath("P/G").getOrder()); //$NON-NLS-1$
    }

    @Test
    public void testEmptyChildrenRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        original.addGroup(new Group("Empty", "P")); //$NON-NLS-1$ //$NON-NLS-2$

        GroupStorage restored = load(dump(original));

        Group g = restored.getGroupByFullPath("P/Empty"); //$NON-NLS-1$
        assertNotNull(g);
        assertNotNull("children must never be null after load", g.getChildren()); //$NON-NLS-1$
        assertTrue(g.isEmpty());
    }

    // ==================== Unicode / Cyrillic payloads ====================

    @Test
    public void testCyrillicValuesRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        Group group = new Group(
            "Сервер", //$NON-NLS-1$
            "ОбщиеМодули"); //$NON-NLS-1$
        group.setDescription("Описание"); //$NON-NLS-1$
        group.addChild("CommonModule.Модуль"); //$NON-NLS-1$
        original.addGroup(group);

        GroupStorage restored = load(dump(original));

        Group g = restored.getGroups().get(0);
        assertEquals("Сервер", g.getName()); //$NON-NLS-1$
        assertEquals("ОбщиеМодули", g.getPath()); //$NON-NLS-1$
        assertEquals("Описание", g.getDescription()); //$NON-NLS-1$
        assertTrue(g.containsChild("CommonModule.Модуль")); //$NON-NLS-1$
    }

    // ==================== Emitted YAML shape ====================

    @Test
    public void testDumpContainsExpectedKeys()
    {
        GroupStorage original = new GroupStorage();
        Group group = new Group("Server", "CommonModules"); //$NON-NLS-1$ //$NON-NLS-2$
        group.addChild("CommonModule.X"); //$NON-NLS-1$
        original.addGroup(group);

        String yaml = dump(original);

        assertTrue(yaml.contains("groups")); //$NON-NLS-1$
        assertTrue(yaml.contains("name")); //$NON-NLS-1$
        assertTrue(yaml.contains("Server")); //$NON-NLS-1$
        assertTrue(yaml.contains("CommonModules")); //$NON-NLS-1$
        assertTrue(yaml.contains("CommonModule.X")); //$NON-NLS-1$
    }

    @Test
    public void testDumpKeysAreSortedAlphabetically()
    {
        GroupStorage original = new GroupStorage();
        Group group = new Group("Server", "CommonModules"); //$NON-NLS-1$ //$NON-NLS-2$
        group.setDescription("d"); //$NON-NLS-1$
        group.setOrder(3);
        group.addChild("CommonModule.X"); //$NON-NLS-1$
        original.addGroup(group);

        String yaml = dump(original);

        // SortedKeysRepresenter emits bean properties alphabetically:
        // children < description < name < order < path
        int children = yaml.indexOf("children"); //$NON-NLS-1$
        int description = yaml.indexOf("description"); //$NON-NLS-1$
        int name = yaml.indexOf("name"); //$NON-NLS-1$
        int order = yaml.indexOf("order"); //$NON-NLS-1$
        int path = yaml.indexOf("path"); //$NON-NLS-1$
        assertTrue("children present", children >= 0); //$NON-NLS-1$
        assertTrue("description present", description >= 0); //$NON-NLS-1$
        assertTrue("name present", name >= 0); //$NON-NLS-1$
        assertTrue("order present", order >= 0); //$NON-NLS-1$
        assertTrue("path present", path >= 0); //$NON-NLS-1$
        assertTrue(children < description);
        assertTrue(description < name);
        assertTrue(name < order);
        assertTrue(order < path);
    }

    // ==================== Loading hand-written YAML ====================

    @Test
    public void testLoadHandWrittenYaml()
    {
        String yaml = String.join("\n", //$NON-NLS-1$
            "groups:", //$NON-NLS-1$
            "- name: ServerModules", //$NON-NLS-1$
            "  path: CommonModules", //$NON-NLS-1$
            "  description: Server-side modules", //$NON-NLS-1$
            "  order: 5", //$NON-NLS-1$
            "  children:", //$NON-NLS-1$
            "  - CommonModule.A", //$NON-NLS-1$
            "  - CommonModule.B"); //$NON-NLS-1$

        GroupStorage storage = load(yaml);

        assertEquals(1, storage.getGroupCount());
        Group g = storage.getGroupByFullPath("CommonModules/ServerModules"); //$NON-NLS-1$
        assertNotNull(g);
        assertEquals("Server-side modules", g.getDescription()); //$NON-NLS-1$
        assertEquals(5, g.getOrder());
        assertEquals(2, g.getChildren().size());
        assertTrue(g.containsChild("CommonModule.A")); //$NON-NLS-1$
        assertTrue(g.containsChild("CommonModule.B")); //$NON-NLS-1$
    }

    @Test
    public void testLoadEmptyDocumentReturnsNull()
    {
        // SnakeYAML returns null for an empty/blank document; the repository
        // guards this by substituting an empty GroupStorage. Here we just
        // pin the raw loader behaviour our model must tolerate.
        assertNull(load("")); //$NON-NLS-1$
    }

    // ==================== Queries survive the round-trip ====================

    @Test
    public void testGroupedObjectsQueryAfterRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        Group g1 = new Group("G1", "CommonModules"); //$NON-NLS-1$ //$NON-NLS-2$
        g1.addChild("CommonModule.Direct"); //$NON-NLS-1$
        Group g2 = new Group("G2", "CommonModules/Sub"); //$NON-NLS-1$ //$NON-NLS-2$
        g2.addChild("CommonModule.Nested"); //$NON-NLS-1$
        original.addGroup(g1);
        original.addGroup(g2);

        GroupStorage restored = load(dump(original));

        java.util.Set<String> objects = restored.getGroupedObjectsAtPath("CommonModules"); //$NON-NLS-1$
        assertTrue(objects.contains("CommonModule.Direct")); //$NON-NLS-1$
        assertTrue(objects.contains("CommonModule.Nested")); //$NON-NLS-1$
    }

    @Test
    public void testFindGroupForObjectAfterRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        Group g = new Group("Server", "CommonModules"); //$NON-NLS-1$ //$NON-NLS-2$
        g.addChild("CommonModule.Mod"); //$NON-NLS-1$
        original.addGroup(g);

        GroupStorage restored = load(dump(original));

        Group found = restored.findGroupForObject("CommonModule.Mod"); //$NON-NLS-1$
        assertNotNull(found);
        assertEquals("Server", found.getName()); //$NON-NLS-1$
    }

    @Test
    public void testManyChildrenRoundTrip()
    {
        GroupStorage original = new GroupStorage();
        Group g = new Group("Big", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> fqns = Arrays.asList(
            "Catalog.A", "Catalog.B", "Catalog.C", "Document.D", "Document.E"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        for (String fqn : fqns)
        {
            g.addChild(fqn);
        }
        original.addGroup(g);

        GroupStorage restored = load(dump(original));

        Group rg = restored.getGroupByFullPath("P/Big"); //$NON-NLS-1$
        assertNotNull(rg);
        assertEquals(5, rg.getChildren().size());
        for (String fqn : fqns)
        {
            assertTrue("Missing child after round-trip: " + fqn, rg.containsChild(fqn)); //$NON-NLS-1$
        }
    }
}
