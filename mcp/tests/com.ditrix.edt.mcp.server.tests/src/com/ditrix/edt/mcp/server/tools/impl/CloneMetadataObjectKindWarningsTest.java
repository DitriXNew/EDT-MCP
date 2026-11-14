/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com.ditrix.edt.mcp.server.utils.MetadataCloneFilesFixture;
import com.ditrix.edt.mcp.server.utils.MetadataClonePlan;

/** Issue #933: a kind-specific consequence reaches the preview/apply warning the caller reads. */
public class CloneMetadataObjectKindWarningsTest
{
    @Test
    public void enabledScheduledJobWarningReachesTheToolWarning() throws Exception
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        job.setName("Nightly");
        job.setMethodName("CommonModule.Calc.Add");
        job.setUse(true);
        config.getScheduledJobs().add(job);
        IProjectFileSystemSupport fs = mock(IProjectFileSystemSupport.class);
        IFile file = MetadataCloneFilesFixture.file("Nightly/Nightly.mdo", true, "<ScheduledJob/>");
        IFolder folder = mock(IFolder.class);
        IFolder collection = mock(IFolder.class);
        when(fs.getFile(job)).thenReturn(file);
        when(file.getParent()).thenReturn(folder);
        when(folder.getProjectRelativePath()).thenReturn(new Path("src/Catalogs/Nightly"));
        when(folder.getParent()).thenReturn(collection);
        when(collection.getFolder(any(org.eclipse.core.runtime.IPath.class))).thenReturn(mock(IFolder.class));

        MetadataClonePlan plan = MetadataClonePlan.capture(mock(IProject.class), mock(IBmModel.class), config, job,
            "NightlyCopy", List.of(), fs, false);
        assertEquals(MetadataClonePlan.kindWarnings(job), plan.kindWarnings);
        String warning = CloneMetadataObjectTool.withKindWarnings("Base warning.", plan);
        assertTrue(warning, warning.startsWith("Base warning. "));
        assertTrue(warning, warning.contains("both jobs will run the same method"));

        job.setUse(false);
        MetadataClonePlan quiet = MetadataClonePlan.capture(mock(IProject.class), mock(IBmModel.class), config, job,
            "NightlyCopy", List.of(), fs, false);
        assertEquals("Base warning.", CloneMetadataObjectTool.withKindWarnings("Base warning.", quiet));
    }

    @Test
    public void kindRefusalIsNotPresentedAsAStaleSource()
    {
        RuntimeException wrapped = new IllegalStateException("BM read failed",
            new MetadataClonePlan.Refusal("CommonModule.Globals is global (global=true): refused."));
        String refusal = CloneMetadataObjectTool.previewError("CommonModule.Globals", wrapped);
        assertTrue(refusal, refusal.contains("global=true"));
        assertFalse(refusal, refusal.contains("resync_to_disk"));
        assertFalse(refusal, refusal.contains("BM read failed"));

        String stale = CloneMetadataObjectTool.previewError("Catalog.Source",
            new IllegalStateException("Cannot read 'src/Catalogs/Source/Source.mdo'."));
        assertTrue(stale, stale.contains("resync_to_disk"));
        assertTrue(stale, stale.contains("Source.mdo"));
    }
}
