package com.ditrix.edt.mcp.server.utils.yaml;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yaml.snakeyaml.error.YAMLException;

import com.ditrix.edt.mcp.server.groups.internal.GroupServiceImpl;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.model.GroupStorage;
import com.ditrix.edt.mcp.server.groups.repository.YamlGroupRepository;
import com.ditrix.edt.mcp.server.tags.TagConstants;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagYamlStore;
import com.ditrix.edt.mcp.server.tags.model.Tag;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;

/** Exercises the production YAML configuration and project-store synchronization. */
public class ProjectYamlStoreTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final TagYamlStore tags = new TagYamlStore();
    private final YamlGroupRepository groups = new YamlGroupRepository();
    private IProject project;
    private Path directory;

    @Before
    public void setUp() throws Exception {
        directory = temporary.newFolder("project").toPath();
        Files.createDirectories(directory.resolve(".settings"));
        project = mock(IProject.class);
        when(project.getName()).thenReturn("association-test");
        IFolder folder = mock(IFolder.class);
        when(folder.exists()).thenReturn(true);
        when(project.getFolder(any(IPath.class))).thenReturn(folder);
        Map<IPath, IFile> files = new ConcurrentHashMap<>();
        when(project.getFile(any(IPath.class))).thenAnswer(invocation -> {
            IPath relative = invocation.getArgument(0);
            return files.computeIfAbsent(relative, key -> file(directory.resolve(key.toPortableString())));
        });
    }

    @After
    public void tearDown() {
        TagService.dispose();
    }

    private IFile file(Path path) {
        try {
            IFile file = mock(IFile.class);
            when(file.exists()).thenAnswer(invocation -> Files.exists(path));
            when(file.getLocation()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(path.toString()));
            when(file.getFullPath()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(path.toString()));
            when(file.getContents()).thenAnswer(invocation -> Files.newInputStream(path));
            doAnswer(invocation -> { Files.deleteIfExists(path); return null; }).when(file).delete(true, null);
            return file;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void foreignGlobalTagsAreRejectedBeforeConstruction() {
        ForeignBean.constructions.set(0);
        for (String collection : new String[]{"tags", "groups"}) {
            AbstractProjectYamlStore<?> store = collection.equals("tags") ? tags : groups;
            for (String type : new String[]{"java.net.URL", ForeignBean.class.getName()}) {
                YAMLException failure = assertThrows(YAMLException.class, () -> store.readYaml(
                    new StringReader(collection + ": [!!" + type + " {}]")));
                assertTrue(failure.getMessage(), failure.getMessage().contains("Global tag is not allowed"));
            }
        }
        assertEquals(0, ForeignBean.constructions.get());
    }

    @Test
    public void diskLoadRefusesForeignGlobalTags() throws Exception {
        Files.writeString(directory.resolve(".settings/metadata-tags.yaml"),
            "tags: [!!java.net.URL [https://example.invalid]]", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".settings/groups.yaml"),
            "groups: [!!java.net.URL [https://example.invalid]]", StandardCharsets.UTF_8);
        assertTrue(tags.load(project).getTags().isEmpty());
        assertTrue(groups.load(project).getGroups().isEmpty());
    }

    @Test
    public void tagFormatAndRoundTripStayIdentical() {
        TagStorage storage = new TagStorage();
        storage.addTag(new Tag("Review", "#112233", "Needs review"));
        storage.assignTag("Catalog.Items", "Review");
        String yaml = tags.dump(storage);
        assertEquals("assignments:\n  Catalog.Items:\n  - Review\ntags:\n- color: '#112233'\n"
            + "  description: Needs review\n  name: Review\n", yaml);
        TagStorage restored = tags.readYaml(new StringReader(yaml));
        assertEquals(storage.getAssignments(), restored.getAssignments());
        assertEquals("#112233", restored.getTags().get(0).getColor());
        assertEquals(yaml, tags.dump(restored));
    }

    @Test
    public void groupFormatSortingAndRoundTripStayIdentical() {
        GroupStorage storage = new GroupStorage();
        Group group = new Group("Review", "Catalogs");
        group.addChild("Catalog.Z");
        group.addChild("Catalog.A");
        storage.addGroup(group);
        String yaml = groups.dump(storage);
        assertEquals("groups:\n- children:\n  - Catalog.A\n  - Catalog.Z\n  description: null\n"
            + "  name: Review\n  order: 0\n  path: Catalogs\n", yaml);
        assertEquals(yaml, groups.dump(groups.readYaml(new StringReader(yaml))));
        assertEquals("Formatting must not mutate live children", "Catalog.Z", group.getChildren().get(0));
    }

    @Test
    public void tagSnapshotsAndMutationResultsCannotChangeTheCache() {
        Tag created = tags.update(project, storage -> {
            Tag tag = new Tag("Review");
            storage.addTag(tag);
            storage.assignTag("Catalog.Items", "Review");
            return tag;
        }, result -> result != null);
        created.setName("Changed");
        TagStorage snapshot = tags.snapshot(project);
        snapshot.getTags().get(0).setColor("#000000");
        snapshot.getTags().clear();
        snapshot.getAssignments().get("Catalog.Items").clear();
        TagStorage actual = tags.snapshot(project);
        assertNotNull(actual.getTagByName("Review"));
        assertEquals("#808080", actual.getTags().get(0).getColor());
        assertEquals(java.util.Set.of("Review"), actual.getTagNames("Catalog.Items"));
    }

    @Test
    public void loadedAssignmentOrderSurvivesSnapshotsAndSaves() throws Exception {
        String yaml = "assignments:\n  Catalog.Z:\n  - Review\n  Catalog.A:\n  - Review\n"
            + "tags:\n- color: '#112233'\n  description: Needs review\n  name: Review\n";
        Path file = directory.resolve(".settings/metadata-tags.yaml");
        Files.writeString(file, yaml);
        TagStorage snapshot = tags.snapshot(project);
        assertEquals(yaml, tags.dump(snapshot));
        assertTrue(tags.save(project, snapshot));
        assertEquals(yaml, Files.readString(file));
    }

    @Test
    public void groupServiceReturnsDetachedBeansAndStorage() {
        GroupServiceImpl service = new GroupServiceImpl();
        Group created = service.createGroup(project, "Review", "Catalogs", "Original");
        created.setName("Changed");
        assertTrue(service.addObjectToGroup(project, "Catalog.Items", "Catalogs/Review"));
        GroupStorage snapshot = service.getGroupStorage(project);
        snapshot.getGroups().get(0).setDescription("Changed");
        snapshot.getGroups().clear();
        assertEquals("Original", service.getAllGroups(project).get(0).getDescription());
        assertNotNull(service.findGroupForObject(project, "Catalog.Items"));
        assertTrue(service.renameObject(project, "Catalog.Items", "Catalog.Products"));
        service.refresh(project);
        assertNotNull(service.findGroupForObject(project, "Catalog.Products"));
    }

    @Test
    public void renamingTagPreservesAssignmentsInMemoryAndAfterReload() {
        TagService service = TagService.getInstance();
        service.createTag(project, "Review", "#112233", "description");
        service.createTag(project, "Other", null, null);
        assertTrue(service.assignTag(project, "Catalog.Items", "Review"));
        assertTrue(service.assignTag(project, "Catalog.Second", "Review"));
        assertTrue(service.assignTag(project, "Catalog.Items", "Other"));

        assertTrue(service.updateTag(project, "Review", "Approved", null, null));
        TagStorage memory = service.getTagStorage(project);
        service.refresh(project);
        TagStorage persisted = service.getTagStorage(project);

        for (TagStorage snapshot : java.util.List.of(memory, persisted)) {
            assertEquals(java.util.Set.of("Catalog.Items", "Catalog.Second"),
                snapshot.getObjectsByTag("Approved"));
            assertTrue(snapshot.getObjectsByTag("Review").isEmpty());
            assertNull(snapshot.getTagByName("Review"));
            assertNotNull(snapshot.getTagByName("Approved"));
            assertEquals(java.util.Set.of("Approved", "Other"), snapshot.getTagNames("Catalog.Items"));
        }
    }

    @Test
    public void invalidGroupMovePreservesMemoryAndDisk() throws Exception {
        GroupServiceImpl service = new GroupServiceImpl();
        service.createGroup(project, "Initial", "Catalogs", "description");
        assertTrue(service.addObjectToGroup(project, "Catalog.Items", "Catalogs/Initial"));
        Path file = directory.resolve(".settings/groups.yaml");
        byte[] before = Files.readAllBytes(file);

        assertFalse(service.addObjectToGroup(project, "Catalog.Items", "Catalogs/Missing"));
        assertEquals("Catalogs/Initial", service.findGroupForObject(project, "Catalog.Items").getFullPath());
        assertArrayEquals(before, Files.readAllBytes(file));
        service.refresh(project);
        assertEquals("Catalogs/Initial", service.findGroupForObject(project, "Catalog.Items").getFullPath());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    public void tagServiceReadersCanIterateDuringWrites() throws Exception {
        TagService service = TagService.getInstance();
        concurrent(() -> {
            for (int i = 0; i < 80; i++) {
                String name = "Tag" + i;
                Tag created = service.createTag(project, name, "#112233", "description");
                assertNotNull(created);
                created.setName("Detached");
                assertTrue(service.assignTag(project, "Catalog.Items" + i, name));
            }
        }, () -> {
            for (int i = 0; i < 160; i++) {
                TagStorage snapshot = service.getTagStorage(project);
                snapshot.getTags().forEach(tag -> assertFalse(tag.getName().isEmpty()));
                snapshot.getAssignments().entrySet().forEach(entry -> entry.getValue().forEach(value ->
                    assertNotNull(snapshot.getTagByName(value))));
            }
        });
        assertEquals(80, service.getTags(project).size());
        service.refresh(project);
        assertEquals(80, service.getTags(project).size());
        assertEquals(80, service.getTagStorage(project).getAssignments().size());
    }

    @Test
    public void groupServiceReadersCanIterateDuringWrites() throws Exception {
        GroupServiceImpl service = new GroupServiceImpl();
        concurrent(() -> {
            for (int i = 0; i < 80; i++) {
                assertNotNull(service.createGroup(project, "Group" + i, "Catalogs", "description"));
                assertTrue(service.addObjectToGroup(project, "Catalog.Items" + i, "Catalogs/Group" + i));
            }
        }, () -> {
            for (int i = 0; i < 160; i++) {
                GroupStorage snapshot = service.getGroupStorage(project);
                snapshot.getGroups().forEach(group -> group.getChildren().forEach(child -> assertNotNull(child)));
            }
        });
        assertEquals(80, service.getAllGroups(project).size());
        service.refresh(project);
        assertEquals(80, service.getAllGroups(project).size());
    }

    @Test
    public void concurrentWritersDoNotLoseUpdates() throws Exception {
        concurrent(() -> addTags("A"), () -> addTags("B"));
        assertEquals(80, tags.snapshot(project).getTags().size());
        tags.invalidate(project);
        assertEquals(80, tags.snapshot(project).getTags().size());
    }

    private void addTags(String prefix) {
        for (int i = 0; i < 40; i++) {
            String name = prefix + i;
            assertTrue(tags.update(project, storage -> storage.addTag(new Tag(name)), Boolean.TRUE::equals));
        }
    }

    @Test
    public void deniedFileLockDoesNotTruncateEitherStore() throws Exception {
        TagStorage tagStorage = new TagStorage();
        tagStorage.addTag(new Tag("Review"));
        GroupStorage groupStorage = new GroupStorage();
        groupStorage.addGroup(new Group("Review", "Catalogs"));
        for (String name : new String[]{TagConstants.TAGS_FILE, "groups.yaml"}) {
            Path path = directory.resolve(".settings").resolve(name);
            Files.writeString(path, "original");
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                assertTrue(lock.isValid());
                assertFalse(name.equals(TagConstants.TAGS_FILE)
                    ? tags.save(project, tagStorage) : groups.save(project, groupStorage));
            }
            assertEquals("original", Files.readString(path));
        }
    }

    @Test
    public void tagIteratorsRemainValidAfterAnotherThreadMutatesStorage() throws Exception {
        TagService service = TagService.getInstance();
        service.createTag(project, "Initial", null, null);
        service.assignTag(project, "Catalog.Initial", "Initial");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch written = new CountDownLatch(1);
        concurrent(() -> {
            await(reading);
            try {
                service.createTag(project, "Added", null, null);
                service.assignTag(project, "Catalog.Added", "Added");
            } finally {
                written.countDown();
            }
        }, () -> {
            TagStorage snapshot = service.getTagStorage(project);
            var tagIterator = snapshot.getTags().iterator();
            var assignmentIterator = snapshot.getAssignments().entrySet().iterator();
            reading.countDown();
            await(written);
            assertEquals("Initial", tagIterator.next().getName());
            assertFalse(tagIterator.hasNext());
            assertEquals("Catalog.Initial", assignmentIterator.next().getKey());
            assertFalse(assignmentIterator.hasNext());
        });
        assertEquals(2, service.getTags(project).size());
    }

    @Test
    public void groupIteratorsRemainValidAfterAnotherThreadMutatesStorage() throws Exception {
        GroupServiceImpl service = new GroupServiceImpl();
        service.createGroup(project, "Initial", "Catalogs", null);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch written = new CountDownLatch(1);
        concurrent(() -> {
            await(reading);
            try {
                service.createGroup(project, "Added", "Catalogs", null);
            } finally {
                written.countDown();
            }
        }, () -> {
            var iterator = service.getGroupStorage(project).getGroups().iterator();
            reading.countDown();
            await(written);
            assertEquals("Initial", iterator.next().getName());
            assertFalse(iterator.hasNext());
        });
        assertEquals(2, service.getAllGroups(project).size());
    }

    @Test
    public void groupEmptyFilePolicyIsPreserved() {
        assertTrue(groups.save(project, new GroupStorage()));
        assertFalse(groups.exists(project));
        assertTrue(tags.save(project, new TagStorage()));
        assertTrue(tags.exists(project));
    }

    /** Codex P2 on #965: a write the file lock refused is neither published nor reported as done. */
    @Test
    public void refusedSaveIsNotPublishedAndReportsFailure() throws Exception {
        TagService service = TagService.getInstance();
        assertNotNull(service.createTag(project, "Existing", null, null));
        Path path = directory.resolve(".settings").resolve(TagConstants.TAGS_FILE);
        byte[] before = Files.readAllBytes(path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            assertTrue(lock.isValid());
            assertNull(service.createTag(project, "Refused", null, null));
            assertFalse(service.assignTag(project, "Catalog.Items", "Existing"));
            assertFalse(tags.update(project, storage -> storage.addTag(new Tag("Direct")), Boolean.TRUE::equals));
        }
        assertArrayEquals(before, Files.readAllBytes(path));
        TagStorage after = tags.snapshot(project);
        assertNotNull(after.getTagByName("Existing"));
        assertNull(after.getTagByName("Refused"));
        assertNull(after.getTagByName("Direct"));
        assertTrue(after.getTagNames("Catalog.Items").isEmpty());
    }

    /** An unreadable file is not replaced by a store built from nothing. */
    @Test
    public void unreadableFileRefusesMutations() throws Exception {
        Path path = directory.resolve(".settings").resolve(TagConstants.TAGS_FILE);
        Files.createDirectories(path);
        assertFalse(tags.update(project, storage -> storage.addTag(new Tag("Lost")), Boolean.TRUE::equals));
        assertNull(TagService.getInstance().createTag(project, "Lost", null, null));
        assertTrue(Files.isDirectory(path));
        assertTrue(tags.snapshot(project).getTags().isEmpty());
    }

    @Test
    public void refusedResultIsFalseForAFlagAndNullOtherwise() {
        assertEquals(Boolean.FALSE, AbstractProjectYamlStore.refused(Boolean.TRUE));
        assertNull(AbstractProjectYamlStore.refused(new Tag("Created")));
    }

    /**
     * Codex P1 on #965: a resource listener invalidates while the workspace holds a rule that a writer
     * holding the project lock waits for; invalidation must not wait for that lock.
     */
    @Test
    public void invalidateDoesNotWaitForTheProjectLock() throws Exception {
        tags.update(project, storage -> storage.addTag(new Tag("Cached")), Boolean.TRUE::equals);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> tags.update(project, storage -> {
                holding.countDown();
                await(release);
                return false;
            }, Boolean.TRUE::equals));
            await(holding);
            Future<?> listener = executor.submit(() -> tags.invalidate(project));
            listener.get(2, TimeUnit.SECONDS);
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        Files.writeString(directory.resolve(".settings").resolve(TagConstants.TAGS_FILE),
            "tags:\n- name: External\n", StandardCharsets.UTF_8);
        tags.invalidate(project);
        TagStorage reloaded = tags.snapshot(project);
        assertNotNull("an invalidation reloads the file", reloaded.getTagByName("External"));
        assertNull(reloaded.getTagByName("Cached"));
    }

    private static void concurrent(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = executor.submit(() -> { await(start); first.run(); });
            Future<?> b = executor.submit(() -> { await(start); second.run(); });
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch start) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    public static class ForeignBean {
        static final AtomicInteger constructions = new AtomicInteger();
        public ForeignBean() {
            constructions.incrementAndGet();
        }
    }
}
