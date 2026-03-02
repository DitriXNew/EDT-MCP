/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.model;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link Group} and {@link GroupStorage} model classes.
 * Verifies group CRUD operations, child management, and storage queries.
 */
public class GroupModelTest
{
    private GroupStorage storage;

    @Before
    public void setUp()
    {
        storage = new GroupStorage();
    }

    // ========== Group Tests ==========

    @Test
    public void testGroupDefaultConstructor()
    {
        Group group = new Group();
        assertNull(group.getName());
        assertNull(group.getPath());
        assertNull(group.getDescription());
        assertEquals(0, group.getOrder());
        assertNotNull(group.getChildren());
        assertTrue(group.getChildren().isEmpty());
    }

    @Test
    public void testGroupNamePathConstructor()
    {
        Group group = new Group("ServerModules", "CommonModules");
        assertEquals("ServerModules", group.getName());
        assertEquals("CommonModules", group.getPath());
        assertEquals(0, group.getOrder());
        assertTrue(group.isEmpty());
    }

    @Test
    public void testGroupGetFullPathWithPath()
    {
        Group group = new Group("ServerModules", "CommonModules");
        assertEquals("CommonModules/ServerModules", group.getFullPath());
    }

    @Test
    public void testGroupGetFullPathNullPath()
    {
        Group group = new Group("RootGroup", null);
        assertEquals("RootGroup", group.getFullPath());
    }

    @Test
    public void testGroupGetFullPathEmptyPath()
    {
        Group group = new Group("RootGroup", "");
        assertEquals("RootGroup", group.getFullPath());
    }

    @Test
    public void testGroupSetters()
    {
        Group group = new Group();
        group.setName("MyGroup");
        group.setPath("Catalogs");
        group.setDescription("Test group");
        group.setOrder(5);
        group.setChildren(Arrays.asList("Catalog.A", "Catalog.B"));

        assertEquals("MyGroup", group.getName());
        assertEquals("Catalogs", group.getPath());
        assertEquals("Test group", group.getDescription());
        assertEquals(5, group.getOrder());
        assertEquals(2, group.getChildren().size());
    }

    @Test
    public void testGroupSetChildrenNull()
    {
        Group group = new Group();
        group.setChildren(null);
        assertNotNull(group.getChildren());
        assertTrue(group.getChildren().isEmpty());
    }

    @Test
    public void testGroupChildrenDefensiveCopy()
    {
        Group group = new Group("G", "P");
        group.addChild("Catalog.A");
        List<String> children = group.getChildren();
        children.add("Catalog.B"); // Modify returned list
        assertEquals("Original should not be modified", 1, group.getChildren().size());
    }

    @Test
    public void testGroupAddChild()
    {
        Group group = new Group("G", "P");
        assertTrue(group.addChild("CommonModule.MyModule"));
        assertTrue(group.containsChild("CommonModule.MyModule"));
        assertEquals(1, group.getChildren().size());
    }

    @Test
    public void testGroupAddChildDuplicate()
    {
        Group group = new Group("G", "P");
        assertTrue(group.addChild("Catalog.A"));
        assertFalse("Duplicate should not be added", group.addChild("Catalog.A"));
        assertEquals(1, group.getChildren().size());
    }

    @Test
    public void testGroupRemoveChild()
    {
        Group group = new Group("G", "P");
        group.addChild("Catalog.A");
        assertTrue(group.removeChild("Catalog.A"));
        assertFalse(group.containsChild("Catalog.A"));
    }

    @Test
    public void testGroupRemoveChildNotPresent()
    {
        Group group = new Group("G", "P");
        assertFalse(group.removeChild("Catalog.X"));
    }

    @Test
    public void testGroupContainsChild()
    {
        Group group = new Group("G", "P");
        group.addChild("Doc.Order");
        assertTrue(group.containsChild("Doc.Order"));
        assertFalse(group.containsChild("Doc.Invoice"));
    }

    @Test
    public void testGroupIsEmpty()
    {
        Group group = new Group("G", "P");
        assertTrue(group.isEmpty());
        group.addChild("X");
        assertFalse(group.isEmpty());
    }

    @Test
    public void testGroupEquals()
    {
        Group g1 = new Group("Name", "Path");
        Group g2 = new Group("Name", "Path");
        assertEquals(g1, g2);
        assertEquals(g1.hashCode(), g2.hashCode());
    }

    @Test
    public void testGroupNotEquals()
    {
        Group g1 = new Group("Name", "Path1");
        Group g2 = new Group("Name", "Path2");
        assertNotEquals(g1, g2);
    }

    @Test
    public void testGroupToString()
    {
        Group group = new Group("MyGroup", "CommonModules");
        group.addChild("A");
        String str = group.toString();
        assertTrue(str.contains("CommonModules/MyGroup"));
        assertTrue(str.contains("children=1"));
    }

    // ========== GroupStorage Tests ==========

    @Test
    public void testStorageDefaultConstructor()
    {
        assertTrue(storage.isEmpty());
        assertEquals(0, storage.getGroupCount());
        assertNotNull(storage.getGroups());
    }

    @Test
    public void testStorageAddGroup()
    {
        Group group = new Group("Server", "CommonModules");
        assertTrue(storage.addGroup(group));
        assertEquals(1, storage.getGroupCount());
    }

    @Test
    public void testStorageAddGroupDuplicate()
    {
        Group g1 = new Group("Server", "CommonModules");
        Group g2 = new Group("Server", "CommonModules");
        assertTrue(storage.addGroup(g1));
        assertFalse("Duplicate full path should not be added", storage.addGroup(g2));
        assertEquals(1, storage.getGroupCount());
    }

    @Test
    public void testStorageGetGroupByFullPath()
    {
        Group group = new Group("Server", "CommonModules");
        storage.addGroup(group);
        Group found = storage.getGroupByFullPath("CommonModules/Server");
        assertNotNull(found);
        assertSame(group, found);
    }

    @Test
    public void testStorageGetGroupByFullPathNotFound()
    {
        assertNull(storage.getGroupByFullPath("Nonexistent/Path"));
    }

    @Test
    public void testStorageRemoveGroup()
    {
        storage.addGroup(new Group("G", "P"));
        assertTrue(storage.removeGroup("P/G"));
        assertEquals(0, storage.getGroupCount());
    }

    @Test
    public void testStorageRemoveGroupNotFound()
    {
        assertFalse(storage.removeGroup("X/Y"));
    }

    @Test
    public void testStorageGetGroupsAtPath()
    {
        Group g1 = new Group("Alpha", "CommonModules");
        g1.setOrder(2);
        Group g2 = new Group("Beta", "CommonModules");
        g2.setOrder(1);
        Group g3 = new Group("Other", "Catalogs");
        storage.addGroup(g1);
        storage.addGroup(g2);
        storage.addGroup(g3);

        List<Group> atPath = storage.getGroupsAtPath("CommonModules");
        assertEquals(2, atPath.size());
        // Should be sorted by order: Beta(1) then Alpha(2)
        assertEquals("Beta", atPath.get(0).getName());
        assertEquals("Alpha", atPath.get(1).getName());
    }

    @Test
    public void testStorageGetGroupsAtPathEmpty()
    {
        List<Group> result = storage.getGroupsAtPath("Nonexistent");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testStorageGetGroupsAtNullPath()
    {
        Group group = new Group("RootGroup", null);
        storage.addGroup(group);
        List<Group> result = storage.getGroupsAtPath(null);
        assertEquals(1, result.size());
    }

    @Test
    public void testStorageRenameGroup()
    {
        storage.addGroup(new Group("OldName", "CommonModules"));
        assertTrue(storage.renameGroup("CommonModules/OldName", "NewName"));
        assertNotNull(storage.getGroupByFullPath("CommonModules/NewName"));
        assertNull(storage.getGroupByFullPath("CommonModules/OldName"));
    }

    @Test
    public void testStorageRenameGroupConflict()
    {
        storage.addGroup(new Group("A", "Path"));
        storage.addGroup(new Group("B", "Path"));
        assertFalse("Should not rename to existing name", storage.renameGroup("Path/A", "B"));
    }

    @Test
    public void testStorageRenameGroupNotFound()
    {
        assertFalse(storage.renameGroup("X/Y", "Z"));
    }

    @Test
    public void testStorageRenameGroupUpdatesChildPaths()
    {
        Group parent = new Group("Parent", "Root");
        Group child = new Group("Child", "Root/Parent");
        storage.addGroup(parent);
        storage.addGroup(child);

        storage.renameGroup("Root/Parent", "NewParent");

        // Child group should have updated path
        Group updatedChild = storage.getGroupByFullPath("Root/NewParent/Child");
        assertNotNull("Child path should be updated after parent rename", updatedChild);
    }

    @Test
    public void testStorageUpdateGroup()
    {
        Group g = new Group("G", "P");
        storage.addGroup(g);
        assertTrue(storage.updateGroup("P/G", "NewG", "New description"));
        assertNotNull(storage.getGroupByFullPath("P/NewG"));
        assertEquals("New description", storage.getGroupByFullPath("P/NewG").getDescription());
    }

    @Test
    public void testStorageUpdateGroupSameName()
    {
        Group g = new Group("G", "P");
        storage.addGroup(g);
        assertTrue(storage.updateGroup("P/G", "G", "Updated desc"));
        assertEquals("Updated desc", g.getDescription());
    }

    @Test
    public void testStorageFindGroupForObject()
    {
        Group g = new Group("Server", "CommonModules");
        g.addChild("CommonModule.MyMod");
        storage.addGroup(g);

        Group found = storage.findGroupForObject("CommonModule.MyMod");
        assertNotNull(found);
        assertEquals("Server", found.getName());
    }

    @Test
    public void testStorageFindGroupForObjectNotGrouped()
    {
        assertNull(storage.findGroupForObject("Catalog.Unknown"));
    }

    @Test
    public void testStorageMoveObjectToGroup()
    {
        Group g1 = new Group("G1", "P");
        Group g2 = new Group("G2", "P");
        g1.addChild("Obj.A");
        storage.addGroup(g1);
        storage.addGroup(g2);

        assertTrue(storage.moveObjectToGroup("Obj.A", "P/G2"));
        assertFalse(g1.containsChild("Obj.A"));
        assertTrue(g2.containsChild("Obj.A"));
    }

    @Test
    public void testStorageMoveObjectToNonexistentGroup()
    {
        assertFalse(storage.moveObjectToGroup("Obj.A", "No/Such/Group"));
    }

    @Test
    public void testStorageRemoveObjectFromAllGroups()
    {
        Group g1 = new Group("G1", "P");
        Group g2 = new Group("G2", "P");
        g1.addChild("Obj.X");
        g2.addChild("Obj.X");
        storage.addGroup(g1);
        storage.addGroup(g2);

        assertTrue(storage.removeObjectFromAllGroups("Obj.X"));
        assertFalse(g1.containsChild("Obj.X"));
        assertFalse(g2.containsChild("Obj.X"));
    }

    @Test
    public void testStorageRemoveObjectFromAllGroupsNotPresent()
    {
        assertFalse(storage.removeObjectFromAllGroups("Obj.Missing"));
    }

    @Test
    public void testStorageGetGroupedObjectsAtPath()
    {
        Group g1 = new Group("G1", "CommonModules");
        g1.addChild("CommonModule.A");
        g1.addChild("CommonModule.B");
        Group g2 = new Group("G2", "Catalogs");
        g2.addChild("Catalog.X");
        storage.addGroup(g1);
        storage.addGroup(g2);

        Set<String> objects = storage.getGroupedObjectsAtPath("CommonModules");
        assertTrue(objects.contains("CommonModule.A"));
        assertTrue(objects.contains("CommonModule.B"));
        assertFalse(objects.contains("Catalog.X"));
    }

    @Test
    public void testStorageRenameObjectNotFound()
    {
        assertFalse(storage.renameObject("Missing", "NewName"));
    }

    @Test
    public void testStorageRenameObject()
    {
        Group g = new Group("Server", "CommonModules");
        g.addChild("CommonModule.OldName");
        g.addChild("CommonModule.Other");
        storage.addGroup(g);

        assertTrue(storage.renameObject("CommonModule.OldName", "CommonModule.NewName"));
        
        // Verify the rename persisted in the actual group
        Group found = storage.findGroupForObject("CommonModule.NewName");
        assertNotNull("Renamed object should be found in group", found);
        assertEquals("Server", found.getName());
        
        // Verify old name is gone
        assertNull("Old FQN should no longer be in any group",
            storage.findGroupForObject("CommonModule.OldName"));
        
        // Verify other children are untouched
        assertTrue(found.containsChild("CommonModule.Other"));
    }

    @Test
    public void testStorageRenameObjectInMultipleGroups()
    {
        Group g1 = new Group("G1", "P");
        Group g2 = new Group("G2", "P");
        g1.addChild("Obj.Old");
        g2.addChild("Obj.Old");
        storage.addGroup(g1);
        storage.addGroup(g2);

        assertTrue(storage.renameObject("Obj.Old", "Obj.New"));
        
        assertTrue("G1 should contain renamed object", g1.containsChild("Obj.New"));
        assertTrue("G2 should contain renamed object", g2.containsChild("Obj.New"));
        assertFalse("Old name should be gone from G1", g1.containsChild("Obj.Old"));
        assertFalse("Old name should be gone from G2", g2.containsChild("Obj.Old"));
    }

    // ========== Group.renameChild Tests ==========

    @Test
    public void testGroupRenameChild()
    {
        Group group = new Group("G", "P");
        group.addChild("CommonModule.OldName");
        
        assertTrue(group.renameChild("CommonModule.OldName", "CommonModule.NewName"));
        assertTrue(group.containsChild("CommonModule.NewName"));
        assertFalse(group.containsChild("CommonModule.OldName"));
    }

    @Test
    public void testGroupRenameChildNotFound()
    {
        Group group = new Group("G", "P");
        group.addChild("Catalog.A");
        
        assertFalse(group.renameChild("Missing.Fqn", "New.Fqn"));
        assertTrue("Existing child should be untouched", group.containsChild("Catalog.A"));
    }

    @Test
    public void testGroupRenameChildPreservesOrder()
    {
        Group group = new Group("G", "P");
        group.addChild("First");
        group.addChild("Second");
        group.addChild("Third");
        
        assertTrue(group.renameChild("Second", "Renamed"));
        
        List<String> children = group.getChildren();
        assertEquals("First", children.get(0));
        assertEquals("Renamed", children.get(1));
        assertEquals("Third", children.get(2));
    }

    @Test
    public void testStorageHasGroupsAtPath()
    {
        storage.addGroup(new Group("G", "CommonModules"));
        assertTrue(storage.hasGroupsAtPath("CommonModules"));
        assertFalse(storage.hasGroupsAtPath("Catalogs"));
    }

    @Test
    public void testStorageSetGroups()
    {
        List<Group> list = Arrays.asList(new Group("A", "P"), new Group("B", "P"));
        storage.setGroups(list);
        assertEquals(2, storage.getGroupCount());
    }

    @Test
    public void testStorageSetGroupsNull()
    {
        storage.addGroup(new Group("G", "P"));
        storage.setGroups(null);
        assertEquals(0, storage.getGroupCount());
    }

    @Test
    public void testStorageIsEmpty()
    {
        assertTrue(storage.isEmpty());
        storage.addGroup(new Group("G", "P"));
        assertFalse(storage.isEmpty());
    }
}
