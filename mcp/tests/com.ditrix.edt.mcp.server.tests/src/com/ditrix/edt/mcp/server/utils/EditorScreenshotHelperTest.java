/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.junit.Test;

/**
 * Unit tests for the render-readiness polling and the screenshot identity guard in
 * {@link EditorScreenshotHelper}.
 *
 * <p>The render-readiness loop is the fix for the empty-snapshot defect: the EDT
 * form layout is produced by an asynchronous native render, so the first read after opening a form
 * used to return an empty result. These tests exercise the pure polling behaviour of
 * {@link EditorScreenshotHelper#waitUntilRendered} with a {@code null} viewer (so no real WYSIWYG
 * representation and no rebuild are involved) and a controllable readiness predicate.</p>
 *
 * <p>The {@code fqnMatchesFormPath} tests cover the identity guard for {@code get_form_screenshot}
 * (the wrong-form defect): the tool must only return the requested form's image when the active editor and
 * its WYSIWYG representation genuinely show the requested form, never another form's PNG from the
 * shared offscreen render buffer. The full capture path against a live WYSIWYG editor is covered by
 * e2e.</p>
 */
public class EditorScreenshotHelperTest
{
    private static final int SHORT_TIMEOUT_MS = 600;

    @Test
    public void testReturnsImmediatelyWhenAlreadyRendered()
    {
        AtomicInteger polls = new AtomicInteger();
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null, () ->
        {
            polls.incrementAndGet();
            return true;
        }, SHORT_TIMEOUT_MS);

        assertTrue("should report rendered when predicate is satisfied", rendered); //$NON-NLS-1$
        assertTrue("predicate must be evaluated at least once", polls.get() >= 1); //$NON-NLS-1$
    }

    @Test
    public void testReturnsTrueOncePredicateBecomesReady()
    {
        AtomicInteger polls = new AtomicInteger();
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null,
            () -> polls.incrementAndGet() >= 3, SHORT_TIMEOUT_MS);

        assertTrue("should report rendered once predicate flips to true", rendered); //$NON-NLS-1$
        assertTrue("predicate must have been polled repeatedly", polls.get() >= 3); //$NON-NLS-1$
    }

    @Test
    public void testReturnsFalseWhenNeverRendered()
    {
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null, () -> false, SHORT_TIMEOUT_MS);
        assertFalse("should report not rendered when predicate never satisfied", rendered); //$NON-NLS-1$
    }

    @Test
    public void testPredicateExceptionTreatedAsNotReady()
    {
        // A transient failure while the model is still building must not abort the wait.
        boolean rendered = EditorScreenshotHelper.waitUntilRendered(null, () ->
        {
            throw new IllegalStateException("model still building"); //$NON-NLS-1$
        }, SHORT_TIMEOUT_MS);

        assertFalse("predicate exceptions must be treated as not-ready, not propagated", rendered); //$NON-NLS-1$
    }

    // ==================== fqnMatchesFormPath (identity guard) ====================

    @Test
    public void testFqnMatchesSamePathAndModelFqn()
    {
        // The model FQN uses the singular 'Form' separator; the requested path may use plural
        // 'Forms'. Both must be recognized as the same form (object form case).
        assertTrue("singular Form FQN must match the same form", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Form.ItemForm", "Catalog.Products.Form.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("plural 'Forms' request must match singular 'Form' model FQN", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Form.ItemForm", "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFqnMatchesCommonForm()
    {
        assertTrue("CommonForm FQN must match itself", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath("CommonForm.MyForm", "CommonForm.MyForm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFqnMatchesContentFormFqn()
    {
        // The WYSIWYG representation renders the content form, whose bmGetFqn carries an extra
        // trailing ".Form" segment. That content-form FQN must still match the requested MD-form path.
        assertTrue("object content-form FQN (trailing .Form) must match the requested form", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Form.ItemForm.Form", "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("common content-form FQN (trailing .Form) must match the requested common form", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "CommonForm.MyForm.Form", "CommonForm.MyForm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFqnMatchesFormNamedForm()
    {
        // A form genuinely named "Form" must not be mis-stripped: the 4-part FQN is canonicalized
        // directly and matches itself, and does NOT collapse to the 3-part owner FQN.
        assertTrue("a form actually named 'Form' must match itself", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Forms.Form", "Catalog.Products.Forms.Form")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFqnMatchesIgnoresCaseAndRussianType()
    {
        assertTrue("type segment must match case-insensitively", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Form.ItemForm", "catalog.Products.forms.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        // Russian type name on the requested side must resolve to the same English type.
        // "Catalog.Товары.Form.X" must match
        // "Справочник.Товары.Forms.X".
        assertTrue("Russian metadata type must match its English equivalent", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Товары.Form.X", // Catalog.Товары.Form.X //$NON-NLS-1$
                "Справочник.Товары.Forms.X")); // Справочник.Товары.Forms.X //$NON-NLS-1$
    }

    @Test
    public void testFqnDoesNotMatchDifferentForm()
    {
        // The core defect: a previously active form (DataProcessor.X) must NOT be accepted when a
        // different catalog object form was requested.
        assertFalse("different form must not match (the wrong-form defect)", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "DataProcessor.GrafikSTO.Form.MainForm", //$NON-NLS-1$
                "Catalog.TestS11.Forms.ItemForm")); //$NON-NLS-1$
        assertFalse("same object different form name must not match", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
                "Catalog.Products.Forms.ListForm")); //$NON-NLS-1$
    }

    @Test
    public void testFqnMatchNullSafe()
    {
        assertFalse("null actual FQN must not match", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(null, "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
        assertFalse("null requested path must not match", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath("Catalog.Products.Form.ItemForm", null)); //$NON-NLS-1$
    }

    @Test
    public void testFqnDoesNotMatchMalformedShape()
    {
        // Unrecognized shapes (not a 2-part common form or 4-part object form) must never match,
        // so a non-form or truncated FQN cannot be accepted as the requested form.
        assertFalse("a bare single segment must not match", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath("Catalog", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a 4-part FQN without a forms separator must not match", //$NON-NLS-1$
            EditorScreenshotHelper.fqnMatchesFormPath(
                "Catalog.Products.Attribute.Name", "Catalog.Products.Attribute.Name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== ensureRenderedFormImage force-refresh (stale-screenshot fix) ====================
    //
    // These tests cover the runtime-free decision logic of the force-refresh mode with a fake
    // representation driven through the same reflective accessors the production code uses
    // (formImageData field, rebuild(boolean) method; the synchronous-render hooks are absent, so the
    // helper exercises its async-rebuild fallback). The synchronous render path against a live
    // FormWysiwygRepresentation needs a running EDT workbench and is left for stand verification.

    /**
     * Fake of the reflectively-accessed surface of {@code FormWysiwygRepresentation}: the
     * {@code formImageData} field read/cleared by the helper and the {@code rebuild(boolean)} method
     * invoked by the async-rebuild fallback. It has no {@code controller}/{@code rebuildInternal}
     * members, so {@code renderRequestedFormSynchronously} reports UNREACHABLE, as on an EDT without
     * the synchronous hooks.
     */
    private static final class FakeRepresentation
    {
        ImageData formImageData;
        Runnable onRebuild;

        @SuppressWarnings("unused") // invoked reflectively by EditorScreenshotHelper.rebuildRepresentation
        void rebuild(boolean full)
        {
            if (onRebuild != null)
            {
                onRebuild.run();
            }
        }
    }

    private static ImageData nonEmptyImage()
    {
        return new ImageData(4, 4, 24, new PaletteData(0xFF0000, 0x00FF00, 0x0000FF));
    }

    @Test
    public void testEnsureRenderedAcceptsExistingImageWithoutForce()
    {
        // refresh=false contract unchanged: a pre-existing non-empty image on the identity-verified
        // representation is accepted directly, without triggering any rebuild.
        FakeRepresentation rep = new FakeRepresentation();
        rep.formImageData = nonEmptyImage();
        AtomicInteger rebuilds = new AtomicInteger();
        rep.onRebuild = rebuilds::incrementAndGet;

        assertTrue("refresh=false must accept the pre-existing image", //$NON-NLS-1$
            EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS, false));
        assertEquals("refresh=false must not trigger a rebuild when an image already exists", //$NON-NLS-1$
            0, rebuilds.get());
    }

    @Test
    public void testEnsureRenderedPopulatesEmptyImageWithoutForce()
    {
        // refresh=false contract unchanged: with no image yet, the helper drives a rebuild and accepts
        // the image it produces.
        FakeRepresentation rep = new FakeRepresentation();
        rep.onRebuild = () -> rep.formImageData = nonEmptyImage();

        assertTrue("refresh=false must wait for a rebuild to populate the missing image", //$NON-NLS-1$
            EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS, false));
    }

    @Test
    public void testForceRefreshRejectsStaleImageWhenNoRenderHappens()
    {
        // The stale-screenshot defect: refresh=true used to short-circuit on the pre-existing (pre-edit) image and
        // return it as "refreshed". In force mode a rebuild that produces nothing must yield false so
        // the tool fails explicitly instead of returning the stale PNG.
        FakeRepresentation rep = new FakeRepresentation();
        rep.formImageData = nonEmptyImage();
        AtomicInteger rebuilds = new AtomicInteger();
        rep.onRebuild = rebuilds::incrementAndGet; // rebuild runs but never produces an image

        boolean rendered = EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS, true);

        assertFalse("a pre-existing image must not satisfy refresh=true without a real re-render", //$NON-NLS-1$
            rendered);
        assertTrue("force mode must have attempted a re-render", rebuilds.get() >= 1); //$NON-NLS-1$
    }

    @Test
    public void testForceRefreshAcceptsNewImageProducedByRender()
    {
        FakeRepresentation rep = new FakeRepresentation();
        rep.formImageData = nonEmptyImage();
        rep.onRebuild = () -> rep.formImageData = nonEmptyImage();

        assertTrue("a re-render that produces a new image must satisfy refresh=true", //$NON-NLS-1$
            EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS, true));
    }

    @Test
    public void testForceRefreshAcceptsReusedInstanceReassignedByRender()
    {
        // The native render may reuse the same ImageData instance when re-rendering (the reason the
        // old "must be a NEW instance" gate was removed). Freshness is detected by clearing the buffer
        // first, so a render that re-assigns the SAME instance still counts as a real re-render.
        FakeRepresentation rep = new FakeRepresentation();
        ImageData reused = nonEmptyImage();
        rep.formImageData = reused;
        rep.onRebuild = () -> rep.formImageData = reused;

        assertTrue("a re-render that reuses the ImageData instance must satisfy refresh=true", //$NON-NLS-1$
            EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS, true));
    }

    @Test
    public void testForceRefreshWithoutPreexistingImageAcceptsRenderedImage()
    {
        // No pre-existing buffer means nothing can be stale: any image the re-render produces is fresh.
        FakeRepresentation rep = new FakeRepresentation();
        rep.onRebuild = () -> rep.formImageData = nonEmptyImage();

        assertTrue("refresh=true with no pre-existing image must accept the rendered image", //$NON-NLS-1$
            EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS, true));
    }

    @Test
    public void testForceRefreshNullRepresentationIsNotRendered()
    {
        assertFalse("a null representation must never report a rendered image", //$NON-NLS-1$
            EditorScreenshotHelper.ensureRenderedFormImage(null, SHORT_TIMEOUT_MS, true));
    }

    // ==================== synchronous render path (the dead-sync-render fix) ====================
    //
    // renderRequestedFormSynchronously must obtain the CommandInterfaceMapping and NativeRenderEvent
    // classes from the located rebuildInternal method's OWN parameter types (resolved by the form
    // bundle's classloader), never via Class.forName on this bundle's classloader - whose
    // Import-Package deliberately lacks com._1c.g5.v8.dt.form.mapping.model and
    // com._1c.g5.v8.dt.form.model, so the old Class.forName always threw ClassNotFoundException and
    // silently disabled the whole synchronous path (refresh=true then cleared formImageData, depended
    // on this path to repopulate it, and timed out). These fakes pin the parameter-type contract
    // headlessly: the classes the helper drives the render with are EXACTLY paramTypes[1]/[2] of the
    // arity-4 rebuildInternal it located.

    /** Stand-in for {@code com._1c.g5.v8.dt.form.mapping.model.CommandInterfaceMapping}. */
    public static final class FakeCommandInterfaceMapping
    {
    }

    /**
     * Stand-in for {@code com._1c.g5.v8.dt.form.model.NativeRenderEvent} with its static
     * {@code buildUpdateEvent()} factory (public, so the helper's plain {@code getMethod} +
     * {@code invoke(null)} works exactly as it does against the real class).
     */
    public static final class FakeNativeRenderEvent
    {
        public static FakeNativeRenderEvent buildUpdateEvent()
        {
            return new FakeNativeRenderEvent();
        }
    }

    /** Controller stand-in recording which mapping class the helper requested the root for. */
    private static final class FakeMappingController
    {
        Class<?> requestedMappingClass;

        @SuppressWarnings("unused") // invoked reflectively (MappingController.getMappingRoot(Class))
        Object getMappingRoot(Class<?> mappingClass)
        {
            requestedMappingClass = mappingClass;
            return new FakeCommandInterfaceMapping();
        }
    }

    /**
     * Fake of the synchronous-render surface of {@code FormWysiwygRepresentation}: the {@code form} /
     * {@code controller} / {@code formImageData} fields, the private
     * {@code rebuildInternal(Form, CommandInterfaceMapping, NativeRenderEvent, boolean)} whose
     * parameter types here are the FAKE classes the helper must pick up, and the async
     * {@code rebuild(boolean)} fallback, which records that it was (not) used.
     */
    private static final class FakeSyncRepresentation
    {
        final Object form = new Object();
        final FakeMappingController controller = new FakeMappingController();
        ImageData formImageData;
        Object lastEvent;
        Boolean lastUpdateOnly;
        final AtomicInteger asyncRebuilds = new AtomicInteger();

        @SuppressWarnings("unused") // invoked reflectively (the synchronous render body)
        private void rebuildInternal(Object renderedForm, FakeCommandInterfaceMapping mapping,
            FakeNativeRenderEvent event, boolean updateOnly)
        {
            lastEvent = event;
            lastUpdateOnly = Boolean.valueOf(updateOnly);
            formImageData = nonEmptyImage();
        }

        @SuppressWarnings("unused") // the async fallback hook; must stay UNUSED when sync hooks exist
        void rebuild(boolean full)
        {
            asyncRebuilds.incrementAndGet();
        }
    }

    /**
     * A representation whose only arity-4 {@code rebuildInternal} has a different shape (no trailing
     * boolean): the helper must NOT mis-invoke it and must use the async fallback instead.
     */
    private static final class FakeWrongShapeRepresentation
    {
        @SuppressWarnings("unused")
        final Object form = new Object();
        @SuppressWarnings("unused")
        final FakeMappingController controller = new FakeMappingController();
        ImageData formImageData;
        final AtomicInteger asyncRebuilds = new AtomicInteger();

        @SuppressWarnings("unused") // a different overload shape; must never be invoked
        private void rebuildInternal(Object a, Object b, Object c, String notABoolean)
        {
            throw new AssertionError("rebuildInternal of a foreign shape must not be invoked"); //$NON-NLS-1$
        }

        @SuppressWarnings("unused") // invoked reflectively by the async fallback
        void rebuild(boolean full)
        {
            asyncRebuilds.incrementAndGet();
            formImageData = nonEmptyImage();
        }
    }

    @Test
    public void testSyncRenderResolvesClassesFromRebuildInternalParameterTypes()
    {
        FakeSyncRepresentation rep = new FakeSyncRepresentation();

        assertTrue("the synchronous render path must run and produce the image", //$NON-NLS-1$
            EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS, false));
        assertSame("the mapping root must be requested with rebuildInternal's parameter type [1]", //$NON-NLS-1$
            FakeCommandInterfaceMapping.class, rep.controller.requestedMappingClass);
        assertTrue("the render event must be built via parameter type [2]'s static buildUpdateEvent", //$NON-NLS-1$
            rep.lastEvent instanceof FakeNativeRenderEvent);
        assertEquals("updateOnly=false - a full layout/render pass", Boolean.FALSE, rep.lastUpdateOnly); //$NON-NLS-1$
        assertEquals("the async rebuild fallback must NOT be used when the sync hooks exist", //$NON-NLS-1$
            0, rep.asyncRebuilds.get());
    }

    @Test
    public void testForceRefreshSatisfiedBySynchronousRender()
    {
        // The refresh=true flow that was broken by the dead sync path: the stale buffer is cleared and
        // the synchronous render (RENDERED outcome) repopulates it during this call.
        FakeSyncRepresentation rep = new FakeSyncRepresentation();
        rep.formImageData = nonEmptyImage(); // the potentially stale pre-edit buffer

        assertTrue("refresh=true must be satisfied by a completed synchronous render", //$NON-NLS-1$
            EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS, true));
        assertTrue("the synchronous render must actually have run", //$NON-NLS-1$
            rep.lastEvent instanceof FakeNativeRenderEvent);
        assertEquals("the async rebuild fallback must NOT be used when the sync hooks exist", //$NON-NLS-1$
            0, rep.asyncRebuilds.get());
    }

    @Test
    public void testSyncRenderForeignShapeFallsBackToAsyncRebuild()
    {
        FakeWrongShapeRepresentation rep = new FakeWrongShapeRepresentation();

        assertTrue("the async fallback must still produce the image", //$NON-NLS-1$
            EditorScreenshotHelper.ensureRenderedFormImage(rep, SHORT_TIMEOUT_MS, false));
        assertTrue("a rebuildInternal of a foreign shape must route to the async fallback", //$NON-NLS-1$
            rep.asyncRebuilds.get() >= 1);
    }
}
