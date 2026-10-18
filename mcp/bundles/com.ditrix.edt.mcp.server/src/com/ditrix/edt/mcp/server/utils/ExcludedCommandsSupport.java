/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The form editor's CommandSet property, accessed without a form-model dependency.
 *
 * <p>BmFormCommandSetModel offers the source's inferred {@code commands}. Those commands are
 * transient, so only their canonical names cross the prepare/write boundary. EDT's
 * FormSymbolicNameServiceDelegate.resolveFormStandardCommandSourceExcludedCommandUri addresses a
 * reference by the source's containment path plus {@code /commands:<encoded name>}; its serializer
 * writes the name alone. Application creates proxies with that same BM address, never detached
 * command implementations. Callers provide the existing BM read/write transaction boundaries.</p>
 */
public final class ExcludedCommandsSupport
{
    public static final String PROPERTY = "excludedCommands"; //$NON-NLS-1$
    public static final int MAX_LISTED = 25;

    private static final String FORM_NS = "http://g5.1c.ru/v8/dt/form"; //$NON-NLS-1$
    private static final String COMMANDS = "commands"; //$NON-NLS-1$
    private static final String COMMAND_PATH = "/commands:"; //$NON-NLS-1$
    /** Settings read by FormCommandSourceInferrer, plus the binding/type that selects its branch. */
    private static final Set<String> COMMAND_SETTINGS = Set.of("datapath", "type", "valuetype", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "changerowset", "choicemode", "selectionmode", "searchcontrollocation", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "searchstringlocation", "viewstatuslocation", "allowformcustomize"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String RETRY = "EDT has not computed this item's standard commands yet, so " //$NON-NLS-1$
        + "'excludedCommands' cannot be validated. Wait for the project to be ready and retry; " //$NON-NLS-1$
        + "nothing was written."; //$NON-NLS-1$

    private ExcludedCommandsSupport()
    {
        // utility class
    }

    /** The exact persistent reference declared by FormStandardCommandSource, including its subtypes. */
    public static boolean isFeature(EStructuralFeature feature)
    {
        return feature instanceof EReference ref && !ref.isContainment() && ref.isMany()
            && PROPERTY.equals(ref.getName())
            && isFormType(ref.getEContainingClass(), "FormStandardCommandSource") //$NON-NLS-1$
            && isFormType(ref.getEReferenceType(), "FormStandardCommand"); //$NON-NLS-1$
    }

    /** Canonical English names supplied by this source's inferred command list, in platform order. */
    public static List<String> allowedValues(EObject source)
    {
        try
        {
            return namesOf(source).stream().map(CommandName::english).toList();
        }
        catch (RuntimeException e)
        {
            // Not computed yet: the property is still listed, with nothing allowed (as plan() refuses).
            return List.of();
        }
    }

    /**
     * A source-changing property needs a separate call, followed by EDT's command recomputation.
     * Validating against the old source would otherwise accept commands the resulting item lacks.
     */
    public static String batchError(List<JsonObject> properties)
    {
        boolean excluded = false;
        String sourceChange = null;
        for (JsonObject property : properties)
        {
            JsonElement raw = property.get("name"); //$NON-NLS-1$
            String name = raw != null && raw.isJsonPrimitive() ? raw.getAsString() : ""; //$NON-NLS-1$
            excluded |= PROPERTY.equalsIgnoreCase(name);
            if (COMMAND_SETTINGS.contains(name.toLowerCase(Locale.ROOT)))
            {
                sourceChange = name;
            }
        }
        return excluded && sourceChange != null
            ? "Set '" + sourceChange + "' and 'excludedCommands' in separate modify_metadata calls. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Wait for the project to be ready after that change, then read " //$NON-NLS-1$
                + "get_metadata_details with assignable:true for its new allowed commands." //$NON-NLS-1$
            : null;
    }

    /** Validate the entire replacement before any list mutation. A failed plan carries no names. */
    public static Plan plan(EObject source, JsonElement value)
    {
        if (value == null || !value.isJsonArray())
        {
            return refused(shapeError(value, -1));
        }
        List<CommandName> offered;
        try
        {
            offered = namesOf(source);
        }
        catch (RuntimeException e)
        {
            return refused(RETRY);
        }
        if (offered.isEmpty())
        {
            return refused(RETRY);
        }
        return resolve(offered, value.getAsJsonArray());
    }

    private static Plan resolve(List<CommandName> offered, JsonArray values)
    {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < values.size(); i++)
        {
            JsonElement raw = values.get(i);
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString())
            {
                return refused(shapeError(raw, i));
            }
            String value = raw.getAsString();
            CommandName match = offered.stream().filter(entry -> entry.matches(value)).findFirst().orElse(null);
            if (match == null)
            {
                return refused("Unknown standard command '" + value + "' for 'excludedCommands'. Allowed: " //$NON-NLS-1$ //$NON-NLS-2$
                    + Pagination.boundedListing(offered.stream().map(CommandName::english).toList(), MAX_LISTED)
                    + ". Use get_metadata_details with assignable:true on this item; pass its command names."); //$NON-NLS-1$
            }
            if (names.contains(match.english()))
            {
                return refused("'excludedCommands' names '" + match.english() //$NON-NLS-1$
                    + "' more than once (including English/Russian aliases). Give each command once."); //$NON-NLS-1$
            }
            names.add(match.english());
        }
        return new Plan(names, null);
    }

    private static Plan refused(String message)
    {
        return new Plan(List.of(), message);
    }

    private static String shapeError(JsonElement value, int index)
    {
        return "Invalid value" + (index < 0 ? "" : " at index " + index) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " for 'excludedCommands': " + value + ". Expected a JSON array of standard command name " //$NON-NLS-1$ //$NON-NLS-2$
            + "strings, e.g. [\"Copy\"]; [] clears the whole set."; //$NON-NLS-1$
    }

    private static List<CommandName> namesOf(EObject source)
    {
        EStructuralFeature feature = source == null ? null : source.eClass().getEStructuralFeature(COMMANDS);
        if (feature == null || !(source.eGet(feature) instanceof Iterable<?> entries))
        {
            return List.of();
        }
        List<CommandName> names = new ArrayList<>();
        for (Object entry : entries)
        {
            if (entry instanceof EObject command && !command.eIsProxy())
            {
                String english = stringFeature(command, "name"); //$NON-NLS-1$
                if (english != null && !english.isBlank())
                {
                    names.add(new CommandName(english, stringFeature(command, "nameRu"))); //$NON-NLS-1$
                }
            }
        }
        return names;
    }

    /** A pair read from DuallyNamedElement, with no independent translation table. */
    private record CommandName(String english, String russian)
    {
        boolean matches(String value)
        {
            return english.equalsIgnoreCase(value) || russian != null && russian.equalsIgnoreCase(value);
        }
    }

    /** Render resolved commands and BM proxies with the same canonical English vocabulary. */
    public static String render(Object value)
    {
        if (!(value instanceof Iterable<?> entries))
        {
            throw new IllegalStateException("'excludedCommands' is not a command list"); //$NON-NLS-1$
        }
        JsonArray result = new JsonArray();
        for (Object entry : entries)
        {
            String name = entry instanceof EObject command ? storedName(command) : null;
            if (name == null || name.isBlank())
            {
                throw new IllegalStateException("An excluded command has no readable canonical name"); //$NON-NLS-1$
            }
            result.add(name);
        }
        return result.toString();
    }

    private static String storedName(EObject command)
    {
        if (command.eIsProxy())
        {
            URI uri = ((InternalEObject)command).eProxyURI();
            String path = BmUriUtil.isBmUri(uri) ? BmUriUtil.extractContainmentPath(uri) : null;
            int start = path == null ? -1 : path.lastIndexOf(COMMAND_PATH);
            return start < 0 ? null : BmUriUtil.decodeCollectionItemId(path.substring(start + COMMAND_PATH.length()));
        }
        return stringFeature(command, "name"); //$NON-NLS-1$
    }

    private static String stringFeature(EObject object, String name)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(name);
        Object value = feature == null ? null : object.eGet(feature);
        return value instanceof String text ? text : null;
    }

    private static boolean isFormType(EClass type, String name)
    {
        return type != null && name.equals(type.getName()) && type.getEPackage() != null
            && FORM_NS.equals(type.getEPackage().getNsURI());
    }

    /** Immutable English names only; no inferred objects or transient BM ids leave preparation. */
    public record Plan(List<String> names, String error)
    {
        public Plan
        {
            names = List.copyOf(names);
        }

        /** Revalidate on the write target, create every proxy, then replace through EMF notifications. */
        @SuppressWarnings("unchecked")
        public void apply(EObject source, EStructuralFeature feature)
        {
            JsonArray requested = new JsonArray();
            names.forEach(requested::add);
            Plan checked = plan(source, requested);
            if (error != null || checked.error() != null)
            {
                throw Refusals.state(error != null ? error : checked.error());
            }
            URI ownerUri = source instanceof IBmObject bm ? bm.bmGetUri() : null;
            if (!isFeature(feature) || ownerUri == null || !BmUriUtil.isBmUri(ownerUri)
                || BmUriUtil.isUnreferenceableObjectFragment(ownerUri.fragment()))
            {
                throw Refusals.state("'excludedCommands' needs a command source attached to BM. " //$NON-NLS-1$
                    + "Wait for the project to be ready and retry."); //$NON-NLS-1$
            }
            String path = BmUriUtil.isTopObjectUri(ownerUri) ? "" : BmUriUtil.extractContainmentPath(ownerUri); //$NON-NLS-1$
            List<EObject> proxies = new ArrayList<>();
            for (String name : checked.names())
            {
                EObject proxy = EcoreUtil.create(((EReference)feature).getEReferenceType());
                ((InternalEObject)proxy).eSetProxyURI(BmUriUtil.createContainedBmObjectUri(
                    BmUriUtil.extractNamespace(ownerUri), BmUriUtil.extractTopObjectFqn(ownerUri),
                    path + COMMAND_PATH + BmUriUtil.encodeCollectionItemId(name)));
                proxies.add(proxy);
            }
            EList<EObject> list = (EList<EObject>)source.eGet(feature);
            list.clear();
            list.addAll(proxies);
        }
    }
}
