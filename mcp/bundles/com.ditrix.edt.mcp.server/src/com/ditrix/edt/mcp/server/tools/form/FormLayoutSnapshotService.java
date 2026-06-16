/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.form;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper;
import com.ditrix.edt.mcp.server.utils.ReflectionUtils;

/**
 * Domain logic for extracting calculated WYSIWYG layout data from an EDT form editor.
 */
public class FormLayoutSnapshotService
{
    private static final String WYSIWYG_VIEWER_FIELD = "wysiwygViewer"; //$NON-NLS-1$
    private static final String WYSIWYG_REPRESENTATION_FIELD = "wysiwygRepresentation"; //$NON-NLS-1$
    private static final String HIPPO_LAY_FORM_FIELD = "hippoLayForm"; //$NON-NLS-1$
    private static final String HIPPO_SESSION_FIELD = "hippoSession"; //$NON-NLS-1$
    private static final String MODEL_PROJECTION_FIELD = "modelProjection"; //$NON-NLS-1$
    private static final String LAYOUT_PROJECTION_FIELD = "layoutProjection"; //$NON-NLS-1$
    private static final String VIEW_PROJECTION_FIELD = "viewProjection"; //$NON-NLS-1$
    private static final String MODE_COMPACT = "compact"; //$NON-NLS-1$
    private static final String MODE_FULL = "full"; //$NON-NLS-1$
    /** Bounds map key for element height. */
    private static final String KEY_HEIGHT = "height"; //$NON-NLS-1$
    /** Bounds map key for element width. */
    private static final String KEY_WIDTH = "width"; //$NON-NLS-1$
    private static final List<String> DISPLAY_PROPERTY_NAMES = List.of(
        "visible", "groupVisible", "enabled", "groupEnabled", "readOnly", "groupReadOnly", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "skipOnInput", "defaultControl", "stretchableMode", "gridLeft", "gridTop", "gridWidth", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "gridHeight", "gridHAlign", "gridVAlign", "gridLeftPadding", "gridTopPadding", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "gridRightPadding", "gridBottomPadding", "alignedAreaTopOffset", KEY_WIDTH, KEY_HEIGHT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "minWidth", "maxWidth", "minHeight", "maxHeight", "backColor", "textColor", "borderColor", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "tooltip", "representation", "shape", "pictureLocation", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "textHAlign", "hAlign", "vAlign", "wrap", "multiLine", "passwordMode", "choiceButton", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "clearButton", "spinButton", "openButton", "dropListButton", "showAsCard", "buttonImportance", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "markRequiredComplete", "textBreakMode", "scale", "useOutput", "formModelElement"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    private static final List<String> INSPECTABLE_VALUE_GETTERS = List.of(
        "getName", "getKind", "getRed", "getGreen", "getBlue", "getAlpha", "getSize", "getHeight", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        "getWidth", "getScale", "getFaceName", "getFontName", "getPictureName", "getUuid", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "getBorderType", "getStyle", "isBold", "isItalic", "isUnderline", "isStrikeout"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    private static final List<String> HIPPO_LAYOUTER_CATEGORY_NAMES = List.of(
        "Main", "Title", "AlignedTitle", "ExtTooltip", "CloseBtnImage", "ShowData", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "WidthDependedHeight", "ChangeHeightOnChangeData", "HorStretchPriority", "HorCompressPriority", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "VerCompressPriority", "Spacing", "NoCalcHorContent", "NoCalcVerContent", "AlwaysVerticalAlign", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "Align", "Primary", "AutoChangeVAlign", "AutoChangeRowsCount", "ContentStretchableMode", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "ContentIndepended", "BehaviorIcon", "MasterColumn", "SlaveColumn", "HasSingleElement", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "Layered", "LinkToAnchor", "FullScreen", "Anchor", "Sticky", "Splitter", "SlaveChangeVAlign", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "NoHorStretchable", "NoVerStretchable", "SlaveVisibility", "AmpersandIsData", "PostGeneratorSizesInDLU", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "MobileLeftCaption", "MobileRightCaption", "MobileAutoCloseButton", "MobileSpecialRightAlign", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "MobileNoMobileTransformation", "MobileSeparatedColumn", "MobileSkippedColumns", "MobileTableGroupData", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "ContainMobileSeparatedColumns", "MarkRequired", "CheckboxOrRadioButtonLabel", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "SelectedItemsActionsPanelGroup", "SelectedItemsActionsPanelInGridGroup", "SelectedItemsActionsPanelInMoxelGroup", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "SelectedItemsActionsPanelButton", "SelectedItemsActionsPanelLabelButton", "SelectedItemsActionsPanelCommandBar", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "SelectedItemsActionsPanelInGridCommandBar", "SelectedItemsActionsPanelInMoxelCommandBar", //$NON-NLS-1$ //$NON-NLS-2$
        "RowActionsPanelGroup", "RowActionsPanelCommandBar", "HierarchyPanelMenuGroup", "CollapsibleGroupTitle", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "GroupTitle", "TopCommandBar", "SearchControl", "BottomCommandBar", "CreateButton", "FABCommandBar", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "CollapsibleGroupCollapseButton", "EditInCommandBar", "Last"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    public String captureLayoutSnapshot(String projectName, String formPath, boolean refresh, String mode)
    {
        List<String> warnings = new ArrayList<>();
        boolean fullMode = MODE_FULL.equals(mode);

        try
        {
            Object editorPage = resolveEditorPage(projectName, formPath);
            if (editorPage == null)
            {
                if (formPath != null && !formPath.isEmpty())
                {
                    return errorYaml("Form editor opened but WYSIWYG page is not available. " + //$NON-NLS-1$
                        "The form may still be loading or rendering; try again."); //$NON-NLS-1$
                }
                return errorYaml("No active form editor page found. Specify formPath to open a form automatically."); //$NON-NLS-1$
            }

            Object wysiwygViewer = ReflectionUtils.getFieldValue(editorPage, WYSIWYG_VIEWER_FIELD);
            if (wysiwygViewer == null)
            {
                return errorYaml("WYSIWYG viewer is not available"); //$NON-NLS-1$
            }

            if (refresh)
            {
                EditorScreenshotHelper.refreshViewer(wysiwygViewer);
            }

            Object representation = ReflectionUtils.getFieldValue(wysiwygViewer, WYSIWYG_REPRESENTATION_FIELD);
            if (representation == null)
            {
                return errorYaml("WYSIWYG representation is not available"); //$NON-NLS-1$
            }

            // Identity guard (b), mirrored from get_form_screenshot: the layout (hippoLayForm) and the
            // form image are produced together from a single createHippoSession(tx, this.form, ...)
            // call on the representation's OWN form model, so the snapshot belongs to whatever form
            // THIS representation renders. Confirm that model is the requested form before reading the
            // layout; otherwise fail explicitly rather than return another form's layout (the same
            // silent wrong-form defect get_form_screenshot had).
            if (formPath != null && !formPath.isEmpty()
                && !EditorScreenshotHelper.representationFormMatches(representation, formPath))
            {
                return errorYaml("The WYSIWYG editor for '" + formPath //$NON-NLS-1$
                    + "' does not render the requested form model. No layout snapshot was taken to " //$NON-NLS-1$
                    + "avoid returning another form's layout; try again once the requested form's " //$NON-NLS-1$
                    + "editor is fully open."); //$NON-NLS-1$
            }

            Object hippoLayForm = ReflectionUtils.getFieldValue(representation, HIPPO_LAY_FORM_FIELD);
            if (!(hippoLayForm instanceof EObject))
            {
                return errorYaml("WYSIWYG layout model is not available"); //$NON-NLS-1$
            }

            Object hippoSession = ReflectionUtils.getFieldValue(representation, HIPPO_SESSION_FIELD);
            Object modelProjection = ReflectionUtils.getFieldValue(representation, MODEL_PROJECTION_FIELD);
            Object layoutProjection = ReflectionUtils.getFieldValue(representation, LAYOUT_PROJECTION_FIELD);
            Object viewProjection = ReflectionUtils.getFieldValue(representation, VIEW_PROJECTION_FIELD);

            List<Map<String, Object>> elements = collectElements((EObject)hippoLayForm, hippoSession,
                modelProjection, layoutProjection, viewProjection, fullMode, warnings);

            int elementCount = countElements(elements);
            int elementsWithBounds = countElementsWithBounds(elements);
            if (elementsWithBounds == 0)
            {
                warnings.add("No calculated element bounds were found. The form may not be fully rendered yet."); //$NON-NLS-1$
            }

            Map<String, Object> formSize = getFormSize(wysiwygViewer, refresh);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true); //$NON-NLS-1$
            result.put("projectName", projectName); //$NON-NLS-1$
            result.put("formPath", formPath); //$NON-NLS-1$
            result.put("mode", mode); //$NON-NLS-1$
            result.put("formSize", formSize); //$NON-NLS-1$
            result.put("elementCount", elementCount); //$NON-NLS-1$
            result.put("elementsWithBounds", elementsWithBounds); //$NON-NLS-1$
            result.put("boundsCoordinateSpace", "form WYSIWYG pixels"); //$NON-NLS-1$ //$NON-NLS-2$
            result.put("warnings", warnings); //$NON-NLS-1$
            result.put("elements", elements); //$NON-NLS-1$
            return dumpYaml(result);
        }
        catch (IllegalStateException e)
        {
            return errorYaml(e.getMessage());
        }
        catch (Exception e)
        {
            Activator.logError("Failed to capture form layout snapshot", e); //$NON-NLS-1$
            return errorYaml("Failed to capture form layout snapshot: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the WYSIWYG page the snapshot is read from. For a requested {@code formPath} the form
     * is opened via {@link EditorScreenshotHelper#openForm} and the page is resolved from <i>that</i>
     * editor part ({@link EditorScreenshotHelper#waitForFormEditorPageOf}), NOT from the global
     * {@code FormEditor.getActiveFormEditorPage()} lookup that returns whatever form editor currently
     * holds workbench focus — the same wrong-form defect {@code get_form_screenshot} had. The opened
     * editor's model FQN is then verified against the requested form (identity guard (a)); a mismatch
     * throws an {@link IllegalStateException} whose message the caller reports verbatim. Without a
     * {@code formPath} the globally active page is used, as before.
     */
    private Object resolveEditorPage(String projectName, String formPath) throws Exception
    {
        if (formPath != null && !formPath.isEmpty())
        {
            // Open the requested form and keep a direct handle on the editor opened for it.
            EditorScreenshotHelper.OpenFormResult openResult =
                EditorScreenshotHelper.openForm(projectName, formPath);
            if (!openResult.isSuccess())
            {
                throw new IllegalStateException(extractToolErrorMessage(openResult.getError()));
            }
            IEditorPart editorPart = openResult.getEditorPart();

            // Let the UI settle after activation (same budget as get_form_screenshot).
            Display display = Display.getCurrent();
            for (int i = 0; i < 5; i++)
            {
                EditorScreenshotHelper.processEvents(display);
                Thread.sleep(100);
            }

            // Resolve the WYSIWYG page from THIS editor part (findPage); the helper owns the shared
            // wait/re-activate loop, replacing the global-active-page wait used here before.
            Object editorPage = EditorScreenshotHelper.waitForFormEditorPageOf(editorPart);
            if (editorPage == null)
            {
                return null;
            }

            // Identity guard (a): the opened editor must correspond to the requested form before its
            // layout is read; otherwise fail explicitly instead of returning the wrong form's layout.
            String actualFqn = EditorScreenshotHelper.getFormEditorFqn(editorPart);
            if (actualFqn != null && !EditorScreenshotHelper.fqnMatchesFormPath(actualFqn, formPath))
            {
                throw new IllegalStateException("Form editor does not match the requested form. " //$NON-NLS-1$
                    + "Requested '" + formPath + "' but the opened editor is '" + actualFqn //$NON-NLS-1$ //$NON-NLS-2$
                    + "'. No layout snapshot was taken to avoid returning the wrong form's layout; " //$NON-NLS-1$
                    + "try again once the requested form's editor is fully open."); //$NON-NLS-1$
            }
            return editorPage;
        }

        return EditorScreenshotHelper.getActiveFormEditorPage();
    }

    public String normalizeMode(String mode)
    {
        if (mode == null || mode.isEmpty() || MODE_COMPACT.equalsIgnoreCase(mode))
        {
            return MODE_COMPACT;
        }
        if (MODE_FULL.equalsIgnoreCase(mode))
        {
            return MODE_FULL;
        }
        return null;
    }

    private String extractToolErrorMessage(String errorJson)
    {
        try
        {
            JsonObject object = JsonParser.parseString(errorJson).getAsJsonObject();
            if (object.has("error")) //$NON-NLS-1$
            {
                return object.get("error").getAsString(); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            return errorJson;
        }
        return errorJson;
    }

    private List<Map<String, Object>> collectElements(EObject hippoLayForm, Object hippoSession,
        Object modelProjection, Object layoutProjection, Object viewProjection, boolean fullMode, List<String> warnings)
    {
        return collectElementTree(hippoLayForm, hippoSession, modelProjection, layoutProjection, viewProjection,
            fullMode, warnings);
    }

    private List<Map<String, Object>> collectElementTree(EObject element, Object hippoSession, Object modelProjection,
        Object layoutProjection, Object viewProjection, boolean fullMode, List<String> warnings)
    {
        List<Map<String, Object>> childItems = new ArrayList<>();
        for (EObject child : element.eContents())
        {
            childItems.addAll(collectElementTree(child, hippoSession, modelProjection, layoutProjection, viewProjection,
                fullMode, warnings));
        }

        Map<String, Object> item = createElementItem(element, hippoSession, modelProjection,
            layoutProjection, viewProjection, fullMode);
        if (item == null)
        {
            return childItems;
        }

        if (!childItems.isEmpty())
        {
            item.put("children", childItems); //$NON-NLS-1$
        }

        List<Map<String, Object>> result = new ArrayList<>();
        result.add(item);
        return result;
    }

    private Map<String, Object> createElementItem(EObject element, Object hippoSession, Object modelProjection,
        Object layoutProjection, Object viewProjection, boolean fullMode)
    {
        Object formEntity = getOriginalFormEntity(hippoSession, element);
        Object presentation = getProjectedModel(modelProjection, element);
        Map<String, Object> bounds = getBounds(presentation, layoutProjection, viewProjection);
        if (!fullMode && !hasPositiveBounds(bounds))
        {
            return null;
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("layoutType", getEType(element)); //$NON-NLS-1$
        putIfNotNull(item, "formEntityType", getEType(formEntity)); //$NON-NLS-1$
        putIfNotNull(item, "name", firstNonBlank(getName(formEntity), getName(element))); //$NON-NLS-1$
        putIfNotNull(item, "title", firstNonBlank(getStringValue(formEntity, "getTitle"), //$NON-NLS-1$ //$NON-NLS-2$
            getStringValue(element, "getTitle"))); //$NON-NLS-1$
        putIfNotNull(item, "formEntity", describeEObject(formEntity)); //$NON-NLS-1$
        item.put("bounds", bounds); //$NON-NLS-1$
        item.put("boundsSource", getBoundsSource(presentation, layoutProjection, viewProjection)); //$NON-NLS-1$
        Map<String, Object> properties = collectProperties(element, fullMode);
        if (!properties.isEmpty())
        {
            item.put("properties", properties); //$NON-NLS-1$
        }

        if (formEntity instanceof EObject)
        {
            Map<String, Object> entityProperties = collectProperties((EObject)formEntity, fullMode);
            if (!entityProperties.isEmpty())
            {
                item.put("formProperties", entityProperties); //$NON-NLS-1$
            }
        }

        return item;
    }

    private Object getOriginalFormEntity(Object hippoSession, EObject element)
    {
        if (hippoSession == null)
        {
            return null;
        }
        return invokeOneArg(hippoSession, "getOriginalFormEntity", element); //$NON-NLS-1$
    }

    private Object getProjectedModel(Object projection, Object domain)
    {
        if (projection == null || domain == null)
        {
            return null;
        }
        return invokeOneArg(projection, "getModel", domain); //$NON-NLS-1$
    }

    private Map<String, Object> getBounds(Object presentation, Object layoutProjection, Object viewProjection)
    {
        Object layout = getProjectedModel(layoutProjection, presentation);
        Map<String, Object> bounds = getLayoutBounds(layout);
        if (bounds != null)
        {
            return bounds;
        }

        Object view = getProjectedModel(viewProjection, presentation);
        Object controlBounds = invokeNoArg(view, "getBounds"); //$NON-NLS-1$
        if (controlBounds instanceof Rectangle)
        {
            Rectangle rectangle = (Rectangle)controlBounds;
            return boundsMap(rectangle.x, rectangle.y, rectangle.width, rectangle.height);
        }

        return null;
    }

    private String getBoundsSource(Object presentation, Object layoutProjection, Object viewProjection)
    {
        Object layout = getProjectedModel(layoutProjection, presentation);
        if (getLayoutBounds(layout) != null)
        {
            return LAYOUT_PROJECTION_FIELD;
        }

        Object view = getProjectedModel(viewProjection, presentation);
        Object controlBounds = invokeNoArg(view, "getBounds"); //$NON-NLS-1$
        if (controlBounds instanceof Rectangle)
        {
            return VIEW_PROJECTION_FIELD;
        }

        return null;
    }

    private boolean hasPositiveBounds(Map<String, Object> bounds)
    {
        if (bounds == null)
        {
            return false;
        }
        Object width = bounds.get(KEY_WIDTH);
        Object height = bounds.get(KEY_HEIGHT);
        return width instanceof Number && height instanceof Number
            && ((Number)width).intValue() > 0 && ((Number)height).intValue() > 0;
    }

    private Map<String, Object> getLayoutBounds(Object layout)
    {
        if (layout == null)
        {
            return null;
        }

        Object left = invokeNoArg(layout, "getLeft"); //$NON-NLS-1$
        Object top = invokeNoArg(layout, "getTop"); //$NON-NLS-1$
        Object width = invokeNoArg(layout, "getWidth"); //$NON-NLS-1$
        Object height = invokeNoArg(layout, "getHeight"); //$NON-NLS-1$
        if (left instanceof Number && top instanceof Number && width instanceof Number && height instanceof Number)
        {
            return boundsMap(((Number)left).intValue(), ((Number)top).intValue(),
                ((Number)width).intValue(), ((Number)height).intValue());
        }

        return null;
    }

    private Map<String, Object> boundsMap(int left, int top, int width, int height)
    {
        Map<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("left", left); //$NON-NLS-1$
        bounds.put("top", top); //$NON-NLS-1$
        bounds.put(KEY_WIDTH, width);
        bounds.put(KEY_HEIGHT, height);
        bounds.put("right", left + width); //$NON-NLS-1$
        bounds.put("bottom", top + height); //$NON-NLS-1$
        return bounds;
    }

    private Map<String, Object> collectProperties(EObject object, boolean fullMode)
    {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures())
        {
            if ("categoriesHolder".equals(feature.getName())) //$NON-NLS-1$
            {
                collectCategoriesHolder(properties, object, feature, fullMode);
                continue;
            }
            if (!fullMode && !DISPLAY_PROPERTY_NAMES.contains(feature.getName()))
            {
                continue;
            }
            if (feature.isMany() && !object.eIsSet(feature))
            {
                continue;
            }
            if (feature instanceof EReference && ((EReference)feature).isContainment())
            {
                continue;
            }

            collectConvertedFeature(properties, object, feature);
        }
        return properties;
    }

    /**
     * Handles the {@code categoriesHolder} feature: in full mode, converts it and adds a
     * {@code categories} entry to {@code properties} when the conversion is non-null. No-op otherwise.
     * Mirrors the original inline branch (the enclosing {@code continue} stays in the caller).
     */
    private void collectCategoriesHolder(Map<String, Object> properties, EObject object,
        EStructuralFeature feature, boolean fullMode)
    {
        if (fullMode)
        {
            Object categories = convertCategoriesHolder(object.eGet(feature, false));
            if (categories != null)
            {
                properties.put("categories", categories); //$NON-NLS-1$
            }
        }
    }

    /**
     * Reads, converts and (when the result is non-null) stores a single feature value into
     * {@code properties}. Uses the border-specific converter for the {@code border} feature.
     */
    private void collectConvertedFeature(Map<String, Object> properties, EObject object, EStructuralFeature feature)
    {
        Object value = object.eGet(feature, false);
        Object converted = "border".equals(feature.getName()) //$NON-NLS-1$
            ? convertBorderValue(value) : convertFeatureValue(value);
        if (converted != null)
        {
            properties.put(feature.getName(), converted);
        }
    }

    private String dumpYaml(Map<String, Object> result)
    {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(120);
        return new Yaml(options).dump(result);
    }

    public String errorYaml(String message)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false); //$NON-NLS-1$
        result.put(McpKeys.ERROR, message);
        return dumpYaml(result);
    }

    private Object convertFeatureValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Enum<?>)
        {
            return String.valueOf(value);
        }
        if (value instanceof String)
        {
            Object decoded = decodeObjectHashString((String)value);
            return decoded != null ? decoded : value;
        }
        if (value instanceof Number || value instanceof Boolean)
        {
            return value;
        }
        if (value instanceof EObject)
        {
            return describeEObject(value);
        }
        if (value instanceof Collection<?>)
        {
            return convertCollectionValue((Collection<?>)value);
        }

        Map<String, Object> inspected = describeInspectableValue(value);
        if (!inspected.isEmpty())
        {
            return inspected;
        }

        return String.valueOf(value);
    }

    /**
     * Converts a collection feature value element-by-element, capping the result
     * at 50 entries with a trailing {@code "..."} marker. Behaviour is identical
     * to the inline Collection branch it was extracted from: same element order,
     * same null-skipping, same cap, same empty-to-{@code null} mapping.
     */
    private Object convertCollectionValue(Collection<?> collection)
    {
        List<Object> values = new ArrayList<>();
        for (Object item : collection)
        {
            Object converted = convertFeatureValue(item);
            if (converted != null)
            {
                values.add(converted);
            }
            if (values.size() >= 50)
            {
                values.add("..."); //$NON-NLS-1$
                break;
            }
        }
        return values.isEmpty() ? null : values;
    }

    private Object convertCategoriesHolder(Object value)
    {
        if (!(value instanceof BitSet))
        {
            return null;
        }
        BitSet categoriesHolder = (BitSet)value;
        if (categoriesHolder.isEmpty())
        {
            return null;
        }

        List<String> categories = new ArrayList<>();
        for (int categoryIndex = categoriesHolder.nextSetBit(0); categoryIndex >= 0;
            categoryIndex = categoriesHolder.nextSetBit(categoryIndex + 1))
        {
            categories.add(categoryIndex < HIPPO_LAYOUTER_CATEGORY_NAMES.size()
                ? HIPPO_LAYOUTER_CATEGORY_NAMES.get(categoryIndex) : "#" + categoryIndex); //$NON-NLS-1$
        }
        return categories;
    }

    private Object convertBorderValue(Object value)
    {
        if (value == null)
        {
            return "Auto"; //$NON-NLS-1$
        }
        if (value instanceof String && decodeObjectHashString((String)value) != null)
        {
            return "Auto"; //$NON-NLS-1$
        }
        // A V8Border with an unset mCoreBorder renders as the "Auto" sentinel. Detect it by the
        // PRESENCE of the public mCoreBorder field, not the class name: V8Border is a platform-internal
        // type not on our classpath (instanceof impossible), and a name match is fragile. Any value
        // without that field simply falls through to the generic conversion.
        try
        {
            if (value.getClass().getField("mCoreBorder").get(value) == null) //$NON-NLS-1$
            {
                return "Auto"; //$NON-NLS-1$
            }
        }
        catch (ReflectiveOperationException e)
        {
            // No public mCoreBorder field on this value — not a border sentinel; fall through.
        }
        return convertFeatureValue(value);
    }

    private Map<String, Object> describeInspectableValue(Object value)
    {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", value.getClass().getSimpleName()); //$NON-NLS-1$

        for (String getterName : INSPECTABLE_VALUE_GETTERS)
        {
            Object getterValue = invokeNoArg(value, getterName);
            Object converted = convertInspectableNestedValue(getterValue, 0);
            if (converted != null)
            {
                description.put(propertyNameFromGetter(getterName), converted);
            }
        }

        for (Field field : value.getClass().getFields())
        {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic())
            {
                continue;
            }
            try
            {
                Object fieldValue = field.get(value);
                Object converted = convertInspectableNestedValue(fieldValue, 0);
                if (converted != null)
                {
                    description.put(field.getName(), converted);
                }
            }
            catch (IllegalAccessException e)
            {
                // Ignore inaccessible diagnostic fields.
            }
        }

        return description;
    }

    private Object convertInspectableNestedValue(Object value, int depth)
    {
        Object simpleValue = convertSimpleValue(value);
        if (simpleValue != null)
        {
            return simpleValue;
        }
        if (value instanceof EObject)
        {
            return describeEObject(value);
        }
        if (value == null)
        {
            return null;
        }
        if (depth > 0)
        {
            return String.valueOf(value);
        }

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", value.getClass().getSimpleName()); //$NON-NLS-1$
        for (Field field : value.getClass().getFields())
        {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic())
            {
                continue;
            }
            try
            {
                Object converted = convertInspectableNestedValue(field.get(value), depth + 1);
                if (converted != null)
                {
                    description.put(field.getName(), converted);
                }
            }
            catch (IllegalAccessException e)
            {
                // Ignore inaccessible diagnostic fields.
            }
        }
        return description;
    }

    private Object convertSimpleValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Enum<?>)
        {
            return String.valueOf(value);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean)
        {
            if (value instanceof String)
            {
                Object decoded = decodeObjectHashString((String)value);
                return decoded != null ? decoded : value;
            }
            return value;
        }
        return null;
    }

    private Object decodeObjectHashString(String value)
    {
        int atIndex = value.lastIndexOf('@');
        if (atIndex <= 0 || atIndex == value.length() - 1)
        {
            return null;
        }
        String hash = value.substring(atIndex + 1);
        if (!hash.matches("[0-9a-fA-F]+")) //$NON-NLS-1$
        {
            return null;
        }

        String className = value.substring(0, atIndex);
        int dotIndex = className.lastIndexOf('.');
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", dotIndex >= 0 ? className.substring(dotIndex + 1) : className); //$NON-NLS-1$
        return description;
    }

    private String propertyNameFromGetter(String getterName)
    {
        String name = getterName;
        if (name.startsWith("get")) //$NON-NLS-1$
        {
            name = name.substring(3);
        }
        else if (name.startsWith("is")) //$NON-NLS-1$
        {
            name = name.substring(2);
        }

        if (name.isEmpty())
        {
            return getterName;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value)
    {
        if (value != null)
        {
            map.put(key, value);
        }
    }

    private Map<String, Object> describeEObject(Object value)
    {
        if (!(value instanceof EObject))
        {
            return null;
        }

        EObject object = (EObject)value;
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", getEType(object)); //$NON-NLS-1$
        String name = getName(object);
        if (name != null && !name.isBlank())
        {
            description.put("name", name); //$NON-NLS-1$
        }
        Object fqn = invokeNoArg(object, "bmGetFqn"); //$NON-NLS-1$
        if (fqn != null)
        {
            description.put("fqn", String.valueOf(fqn)); //$NON-NLS-1$
        }
        return description;
    }

    private String getEType(Object value)
    {
        if (value instanceof EObject)
        {
            EObject object = (EObject)value;
            String packageName = object.eClass().getEPackage() != null
                ? object.eClass().getEPackage().getName() : null;
            if (packageName != null && !packageName.isBlank())
            {
                return packageName + ":" + object.eClass().getName(); //$NON-NLS-1$
            }
            return object.eClass().getName();
        }
        return value != null ? value.getClass().getName() : null;
    }

    private String getName(Object value)
    {
        return firstNonBlank(getStringValue(value, "getName"), getStringValue(value, "getId")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String getStringValue(Object value, String methodName)
    {
        Object result = invokeNoArg(value, methodName);
        return result != null ? String.valueOf(result) : null;
    }

    private String firstNonBlank(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.isBlank())
            {
                return value;
            }
        }
        return null;
    }

    private Object invokeNoArg(Object target, String methodName)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private Object invokeOneArg(Object target, String methodName, Object argument)
    {
        if (target == null || argument == null)
        {
            return null;
        }
        try
        {
            Method method = ReflectionUtils.findMethod(target.getClass(), methodName, Object.class);
            if (method == null)
            {
                method = target.getClass().getMethod(methodName, Object.class);
            }
            method.setAccessible(true);
            return method.invoke(target, argument);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private Map<String, Object> getFormSize(Object wysiwygViewer, boolean refresh) throws Exception
    {
        if (refresh)
        {
            ImageData imageData = EditorScreenshotHelper.extractFormImageData(wysiwygViewer);
            if (imageData != null)
            {
                return boundsMap(0, 0, imageData.width, imageData.height);
            }
        }

        ImageData controlImageData = EditorScreenshotHelper.captureControlImageData(wysiwygViewer);
        if (controlImageData != null)
        {
            return boundsMap(0, 0, controlImageData.width, controlImageData.height);
        }

        return null;
    }

    private int countElementsWithBounds(List<Map<String, Object>> elements)
    {
        int count = 0;
        for (Map<String, Object> element : elements)
        {
            if (element.get("bounds") != null) //$NON-NLS-1$
            {
                count++;
            }
            count += countElementsWithBounds(getChildren(element));
        }
        return count;
    }

    private int countElements(List<Map<String, Object>> elements)
    {
        int count = 0;
        for (Map<String, Object> element : elements)
        {
            count++;
            count += countElements(getChildren(element));
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getChildren(Map<String, Object> element)
    {
        Object children = element.get("children"); //$NON-NLS-1$
        if (children instanceof List<?>)
        {
            return (List<Map<String, Object>>)children;
        }
        return List.of();
    }
}
