package com.ditrix.edt.mcp.server.tags.refactoring;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.tags.TagService;

public class TagRefactoringContributorTest {
    private final TagRenameRefactoringContributor rename = new TagRenameRefactoringContributor();
    private final TagDeleteRefactoringContributor delete = new TagDeleteRefactoringContributor();
    private Field plugin;
    private Object previous;
    private String application;

    @Before
    public void setUp() throws Exception {
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previous = plugin.get(null);
        application = System.getProperty("eclipse.application");
    }

    @After
    public void tearDown() throws Exception {
        plugin.set(null, previous);
        if (application == null) {
            System.clearProperty("eclipse.application");
        } else {
            System.setProperty("eclipse.application", application);
        }
    }

    @Test
    public void unavailablePluginIsIgnoredByBothParticipants() throws Exception {
        plugin.set(null, null);
        assertUnavailable(mock(IProject.class));
    }

    @Test
    public void headlessHostStillProvidesTheTagService() throws Exception {
        // get_tags is headless-capable, so a headless rename must keep tag assignments in step.
        plugin.set(null, mock(Activator.class));
        System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
        try {
            assertNotNull(TagService.getInstanceIfAvailable());
        } finally {
            TagService.dispose();
        }
    }

    @Test
    public void nullProjectIsIgnoredBeforeStartingTheService() throws Exception {
        plugin.set(null, mock(Activator.class));
        System.setProperty("eclipse.application", "windowed-test");
        assertUnavailable(null);
    }

    private void assertUnavailable(IProject project) {
        assertFalse(rename.hasAssociation(project, "Catalog.Items"));
        assertFalse(rename.performRename(project, "Catalog.Items", "Catalog.Products"));
        assertFalse(delete.hasAssociation(project, "Catalog.Items"));
        assertFalse(delete.performRemove(project, "Catalog.Items"));
    }
}
