/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tags.model;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link Tag} and {@link TagStorage} model classes.
 * Verifies tag CRUD, assignments, ordering, and storage queries.
 */
public class TagModelTest
{
    private TagStorage storage;

    @Before
    public void setUp()
    {
        storage = new TagStorage();
    }

    // ========== Tag Tests ==========

    @Test
    public void testTagSingleArgConstructor()
    {
        Tag tag = new Tag("important");
        assertEquals("important", tag.getName());
        assertEquals("#808080", tag.getColor());
        assertEquals("", tag.getDescription());
    }

    @Test
    public void testTagTwoArgConstructor()
    {
        Tag tag = new Tag("bug", "#FF0000");
        assertEquals("bug", tag.getName());
        assertEquals("#FF0000", tag.getColor());
        assertEquals("", tag.getDescription());
    }

    @Test
    public void testTagThreeArgConstructor()
    {
        Tag tag = new Tag("feature", "#00FF00", "Feature request");
        assertEquals("feature", tag.getName());
        assertEquals("#00FF00", tag.getColor());
        assertEquals("Feature request", tag.getDescription());
    }

    @Test
    public void testTagDefaultConstructor()
    {
        Tag tag = new Tag();
        assertEquals("", tag.getName());
        assertEquals("#808080", tag.getColor());
        assertEquals("", tag.getDescription());
    }

    @Test
    public void testTagNullColorDefaults()
    {
        Tag tag = new Tag("t", null, "desc");
        assertEquals("#808080", tag.getColor());
    }

    @Test
    public void testTagNullDescriptionDefaults()
    {
        Tag tag = new Tag("t", "#FFF", null);
        assertEquals("", tag.getDescription());
    }

    @Test(expected = NullPointerException.class)
    public void testTagNullNameThrows()
    {
        new Tag(null);
    }

    @Test
    public void testTagSetters()
    {
        Tag tag = new Tag("old");
        tag.setName("new");
        tag.setColor("#000000");
        tag.setDescription("Updated");
        assertEquals("new", tag.getName());
        assertEquals("#000000", tag.getColor());
        assertEquals("Updated", tag.getDescription());
    }

    @Test
    public void testTagEqualsName()
    {
        Tag a = new Tag("same", "#FF0000");
        Tag b = new Tag("same", "#00FF00");
        assertEquals("Equality should be based on name only", a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testTagNotEquals()
    {
        Tag a = new Tag("alpha");
        Tag b = new Tag("beta");
        assertNotEquals(a, b);
    }

    @Test
    public void testTagToString()
    {
        Tag tag = new Tag("myTag");
        assertEquals("myTag", tag.toString());
    }

    @Test
    public void testTagEqualsSelf()
    {
        Tag tag = new Tag("x");
        assertEquals(tag, tag);
    }

    @Test
    public void testTagNotEqualsNull()
    {
        Tag tag = new Tag("x");
        assertNotEquals(tag, null);
    }

    @Test
    public void testTagNotEqualsOtherClass()
    {
        Tag tag = new Tag("x");
        assertNotEquals(tag, "x");
    }

    // ========== TagStorage Basic Tests ==========

    @Test
    public void testStorageDefaultConstructor()
    {
        assertNotNull(storage.getTags());
        assertTrue(storage.getTags().isEmpty());
        assertNotNull(storage.getAssignments());
        assertTrue(storage.getAssignments().isEmpty());
    }

    @Test
    public void testStorageAddTag()
    {
        Tag tag = new Tag("important", "#FF0000");
        assertTrue(storage.addTag(tag));
        assertEquals(1, storage.getTags().size());
    }

    @Test
    public void testStorageAddTagDuplicate()
    {
        storage.addTag(new Tag("dup"));
        assertFalse("Duplicate tag should not be added", storage.addTag(new Tag("dup")));
        assertEquals(1, storage.getTags().size());
    }

    @Test
    public void testStorageGetTagByName()
    {
        Tag tag = new Tag("target");
        storage.addTag(tag);
        Tag found = storage.getTagByName("target");
        assertNotNull(found);
        assertSame(tag, found);
    }

    @Test
    public void testStorageGetTagByNameNotFound()
    {
        assertNull(storage.getTagByName("missing"));
    }

    @Test
    public void testStorageRemoveTag()
    {
        storage.addTag(new Tag("removable"));
        assertTrue(storage.removeTag("removable"));
        assertNull(storage.getTagByName("removable"));
    }

    @Test
    public void testStorageRemoveTagNotFound()
    {
        assertFalse(storage.removeTag("nonexistent"));
    }

    @Test
    public void testStorageRemoveTagClearsAssignments()
    {
        storage.addTag(new Tag("cleanup"));
        storage.assignTag("Catalog.A", "cleanup");
        storage.removeTag("cleanup");

        Set<String> tags = storage.getTagNames("Catalog.A");
        assertFalse("Tag should be removed from assignments", tags.contains("cleanup"));
    }

    // ========== TagStorage Assignment Tests ==========

    @Test
    public void testStorageAssignTag()
    {
        storage.addTag(new Tag("bug"));
        assertTrue(storage.assignTag("Document.Order", "bug"));
        assertTrue(storage.getTagNames("Document.Order").contains("bug"));
    }

    @Test
    public void testStorageAssignTagNonExistent()
    {
        assertFalse("Cannot assign non-existent tag",
            storage.assignTag("Document.Order", "missing_tag"));
    }

    @Test
    public void testStorageAssignTagDuplicate()
    {
        storage.addTag(new Tag("bug"));
        storage.assignTag("Doc.A", "bug");
        assertFalse("Should not assign same tag twice",
            storage.assignTag("Doc.A", "bug"));
    }

    @Test
    public void testStorageUnassignTag()
    {
        storage.addTag(new Tag("temp"));
        storage.assignTag("Obj.X", "temp");
        assertTrue(storage.unassignTag("Obj.X", "temp"));
        assertTrue(storage.getTagNames("Obj.X").isEmpty());
    }

    @Test
    public void testStorageUnassignTagRemovesEmptyEntry()
    {
        storage.addTag(new Tag("only"));
        storage.assignTag("Obj.Y", "only");
        storage.unassignTag("Obj.Y", "only");
        // After removing last tag, the assignment entry should be cleaned up
        assertFalse(storage.getAssignments().containsKey("Obj.Y"));
    }

    @Test
    public void testStorageUnassignTagNotAssigned()
    {
        assertFalse(storage.unassignTag("Obj.Z", "nothing"));
    }

    @Test
    public void testStorageGetObjectTags()
    {
        Tag bug = new Tag("bug", "#FF0000");
        Tag feature = new Tag("feature", "#00FF00");
        storage.addTag(bug);
        storage.addTag(feature);
        storage.assignTag("Doc.A", "bug");
        storage.assignTag("Doc.A", "feature");

        Set<Tag> tags = storage.getObjectTags("Doc.A");
        assertEquals(2, tags.size());
        assertTrue(tags.contains(bug));
        assertTrue(tags.contains(feature));
    }

    @Test
    public void testStorageGetObjectTagsNoAssignments()
    {
        Set<Tag> tags = storage.getObjectTags("Unknown.Obj");
        assertNotNull(tags);
        assertTrue(tags.isEmpty());
    }

    @Test
    public void testStorageGetTagNames()
    {
        storage.addTag(new Tag("a"));
        storage.addTag(new Tag("b"));
        storage.assignTag("Obj", "a");
        storage.assignTag("Obj", "b");

        Set<String> names = storage.getTagNames("Obj");
        assertEquals(2, names.size());
        assertTrue(names.contains("a"));
        assertTrue(names.contains("b"));
    }

    @Test
    public void testStorageGetTagNamesNoAssignment()
    {
        Set<String> names = storage.getTagNames("NoObj");
        assertNotNull(names);
        assertTrue(names.isEmpty());
    }

    @Test
    public void testStorageGetObjectsByTag()
    {
        storage.addTag(new Tag("shared"));
        storage.assignTag("Catalog.A", "shared");
        storage.assignTag("Doc.B", "shared");

        Set<String> objects = storage.getObjectsByTag("shared");
        assertEquals(2, objects.size());
        assertTrue(objects.contains("Catalog.A"));
        assertTrue(objects.contains("Doc.B"));
    }

    @Test
    public void testStorageGetObjectsByTagEmpty()
    {
        Set<String> objects = storage.getObjectsByTag("unused");
        assertNotNull(objects);
        assertTrue(objects.isEmpty());
    }

    // ========== TagStorage Rename/Remove Object ==========

    @Test
    public void testStorageRenameObject()
    {
        storage.addTag(new Tag("t"));
        storage.assignTag("Old.Name", "t");
        assertTrue(storage.renameObject("Old.Name", "New.Name"));
        assertTrue(storage.getTagNames("New.Name").contains("t"));
        assertTrue(storage.getTagNames("Old.Name").isEmpty());
    }

    @Test
    public void testStorageRenameObjectNotFound()
    {
        assertFalse(storage.renameObject("Missing", "New"));
    }

    @Test
    public void testStorageRemoveObject()
    {
        storage.addTag(new Tag("t"));
        storage.assignTag("Obj.Del", "t");
        assertTrue(storage.removeObject("Obj.Del"));
        assertTrue(storage.getTagNames("Obj.Del").isEmpty());
    }

    @Test
    public void testStorageRemoveObjectNotFound()
    {
        assertFalse(storage.removeObject("Not.Found"));
    }

    // ========== TagStorage Tag Ordering ==========

    @Test
    public void testStorageMoveTagUp()
    {
        storage.addTag(new Tag("first"));
        storage.addTag(new Tag("second"));
        storage.addTag(new Tag("third"));

        assertTrue(storage.moveTagUp("second"));
        assertEquals(0, storage.getTagIndex("second"));
        assertEquals(1, storage.getTagIndex("first"));
    }

    @Test
    public void testStorageMoveTagUpAlreadyAtTop()
    {
        storage.addTag(new Tag("top"));
        assertFalse(storage.moveTagUp("top"));
    }

    @Test
    public void testStorageMoveTagUpNotFound()
    {
        assertFalse(storage.moveTagUp("missing"));
    }

    @Test
    public void testStorageMoveTagDown()
    {
        storage.addTag(new Tag("first"));
        storage.addTag(new Tag("second"));
        storage.addTag(new Tag("third"));

        assertTrue(storage.moveTagDown("second"));
        assertEquals(2, storage.getTagIndex("second"));
        assertEquals(1, storage.getTagIndex("third"));
    }

    @Test
    public void testStorageMoveTagDownAlreadyAtBottom()
    {
        storage.addTag(new Tag("bottom"));
        assertFalse(storage.moveTagDown("bottom"));
    }

    @Test
    public void testStorageMoveTagDownNotFound()
    {
        assertFalse(storage.moveTagDown("missing"));
    }

    @Test
    public void testStorageGetTagIndex()
    {
        storage.addTag(new Tag("a"));
        storage.addTag(new Tag("b"));
        storage.addTag(new Tag("c"));

        assertEquals(0, storage.getTagIndex("a"));
        assertEquals(1, storage.getTagIndex("b"));
        assertEquals(2, storage.getTagIndex("c"));
        assertEquals(-1, storage.getTagIndex("z"));
    }

    // ========== TagStorage Setters ==========

    @Test
    public void testStorageSetTags()
    {
        List<Tag> list = Arrays.asList(new Tag("a"), new Tag("b"));
        storage.setTags(list);
        assertEquals(2, storage.getTags().size());
    }

    @Test
    public void testStorageSetTagsNull()
    {
        storage.addTag(new Tag("x"));
        storage.setTags(null);
        assertNotNull(storage.getTags());
        assertTrue(storage.getTags().isEmpty());
    }

    @Test
    public void testStorageSetAssignments()
    {
        Map<String, List<String>> map = Map.of("Obj.A", List.of("tag1"));
        storage.setAssignments(new java.util.HashMap<>(map));
        assertEquals(1, storage.getAssignments().size());
    }

    @Test
    public void testStorageSetAssignmentsNull()
    {
        storage.setAssignments(null);
        assertNotNull(storage.getAssignments());
        assertTrue(storage.getAssignments().isEmpty());
    }
}
