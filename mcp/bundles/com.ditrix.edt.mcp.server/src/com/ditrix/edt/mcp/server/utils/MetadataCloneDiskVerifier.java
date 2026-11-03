/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;

import com._1c.g5.modeling.xml.AbstractXmlResource;
import com._1c.g5.v8.dt.core.naming.ISymbolicNameService;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.provider.NoopLazyLinkerProvider;
import com._1c.g5.v8.dt.core.resource.IDtProjectAware;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;

/** Compare a detached exported file with BM; must be called inside the model's read boundary. */
public final class MetadataCloneDiskVerifier
{
    private MetadataCloneDiskVerifier()
    {
    }

    /** Called after export has drained; pending destination files never use source-readiness checks. */
    public static byte[] verifyCopiedFile(IFile file, String expectedHash)
    {
        if (!file.exists())
        {
            throw new IllegalStateException("Missing clone file '" + file.getProjectRelativePath()
                + "' after export. Inspect the clone and use resync_to_disk to export its committed model.");
        }
        try (InputStream input = file.getContents(true))
        {
            byte[] bytes = input.readAllBytes();
            if (expectedHash != null && !expectedHash.equals(MetadataClonePlan.bytesHash(bytes)))
            {
                throw new IllegalStateException("Copied bytes differ in '" + file.getProjectRelativePath() + "'");
            }
            return bytes;
        }
        catch (org.eclipse.core.runtime.CoreException | java.io.IOException e)
        {
            throw new IllegalStateException("Cannot read clone file '" + file.getProjectRelativePath()
                + "' after export. Inspect its file permissions and the EDT log before recovery.", e);
        }
    }

    /** Source readiness is model/disk equality, not merely an empty export queue. */
    public static void verifyOwned(IProject project, Map<String, EObject> graph, IProjectFileSystemSupport fs)
    {
        IDtProject dtProject = Activator.getDefault().getDtProjectManager().getDtProject(project);
        ISymbolicNameService names = ServiceAccess.get(ISymbolicNameService.class);
        IRuntimeVersionSupport versions = ServiceAccess.get(IRuntimeVersionSupport.class);
        if (dtProject == null || names == null || versions == null)
        {
            throw new IllegalStateException("EDT source-verification services are unavailable. "
                + "Wait for the project to load, then preview again.");
        }
        for (Map.Entry<String, EObject> entry : graph.entrySet())
        {
            EObject object = entry.getValue();
            if (entry.getKey().isEmpty() || (object instanceof IBmObject && ((IBmObject)object).bmIsTop()))
            {
                IFile file = fs.getFile(object);
                if (file == null || !file.exists())
                {
                    throw new IllegalStateException("A source model file is missing. Save/export the source "
                        + "and run resync_to_disk before previewing again.");
                }
                verify(file, object, dtProject, versions.getRuntimeVersion(project), names);
            }
        }
    }

    public static void verify(IFile file, EObject model, IDtProject project, Version version,
        ISymbolicNameService names)
    {
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toPortableString(), true);
        Resource.Factory factory = Resource.Factory.Registry.INSTANCE.getFactory(uri, null);
        if (factory == null)
        {
            throw new IllegalStateException("No EDT resource reader for '" + file.getProjectRelativePath()
                + "'. Reopen the project and inspect the clone; do not repeat confirm.");
        }
        Resource resource = factory.createResource(uri);
        if (resource instanceof IDtProjectAware)
        {
            ((IDtProjectAware)resource).setDtProject(project);
        }
        if (resource instanceof AbstractXmlResource)
        {
            ((AbstractXmlResource)resource).setLinker(NoopLazyLinkerProvider.get());
        }
        try (InputStream input = file.getContents(true))
        {
            Map<String, Object> options = new java.util.HashMap<>();
            options.put("PLATFORM_VERSION", version);
            resource.load(input, options);
            if (!resource.getErrors().isEmpty() || resource.getContents().size() != 1)
            {
                throw new IllegalStateException("Exported model cannot be parsed: " + file.getProjectRelativePath());
            }
            compare(model, resource.getContents().get(0), names);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Export verification failed for '" + file.getProjectRelativePath()
                + "': " + String.valueOf(e.getMessage()).replaceFirst("[.!?]+$", "")
                + ". Inspect the model and use resync_to_disk before recovery.", e);
        }
        finally
        {
            resource.unload();
        }
    }

    public static void compare(EObject model, EObject disk, ISymbolicNameService names)
    {
        Map<EObject, String> modelPaths = paths(model);
        Map<EObject, String> diskPaths = paths(disk);
        compareNode(model, disk, "", modelPaths, diskPaths, names);
    }

    private static Map<EObject, String> paths(EObject root)
    {
        Map<EObject, String> paths = new IdentityHashMap<>();
        collectPaths(root, "", paths);
        return paths;
    }

    private static void collectPaths(EObject node, String path, Map<EObject, String> paths)
    {
        paths.put(node, path);
        int index = 0;
        for (EObject child : PersistedContents.of(node))
        {
            collectPaths(child, path + "/" + index++, paths);
        }
    }

    private static void compareNode(EObject model, EObject disk, String path,
        Map<EObject, String> modelPaths, Map<EObject, String> diskPaths, ISymbolicNameService names)
    {
        if (model.eClass() != disk.eClass())
        {
            throw new IllegalStateException("Different model type at " + path);
        }
        for (EStructuralFeature feature : model.eClass().getEAllStructuralFeatures())
        {
            if (feature.isDerived() || feature.isTransient())
            {
                continue;
            }
            String location = path + "/" + feature.getName();
            Object wanted = model.eGet(feature, false);
            Object actual = disk.eGet(feature, false);
            List<?> expectedValues = values(wanted);
            List<?> actualValues = values(actual);
            if (expectedValues.size() != actualValues.size())
            {
                throw new IllegalStateException("Different value count at " + location);
            }
            for (int i = 0; i < expectedValues.size(); i++)
            {
                Object expected = expectedValues.get(i);
                Object found = actualValues.get(i);
                if (feature instanceof EReference)
                {
                    EReference reference = (EReference)feature;
                    if (reference.isContainment())
                    {
                        compareNode((EObject)expected, (EObject)found, location + "/" + i,
                            modelPaths, diskPaths, names);
                    }
                    else
                    {
                        String expectedSymbol = symbol((EObject)expected, model, reference, modelPaths, names);
                        String actualSymbol = symbol((EObject)found, disk, reference, diskPaths, names);
                        if (!Objects.equals(expectedSymbol, actualSymbol))
                        {
                            throw new IllegalStateException("Different reference target at " + location);
                        }
                    }
                }
                else if (!Objects.deepEquals(expected, found))
                {
                    throw new IllegalStateException("Different persisted property at " + location);
                }
            }
        }
    }

    private static List<?> values(Object value)
    {
        if (value == null)
        {
            return List.of();
        }
        return value instanceof Iterable<?> ? copy((Iterable<?>)value) : List.of(value);
    }

    private static List<?> copy(Iterable<?> values)
    {
        List<Object> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    static String symbol(EObject target, EObject context, EReference reference,
        Map<EObject, String> local, ISymbolicNameService names)
    {
        if (target == null)
        {
            return null;
        }
        if (target.eIsProxy())
        {
            URI uri = ((InternalEObject)target).eProxyURI();
            if ("unresolved".equals(uri.scheme()))
            {
                return uri.path().substring(1);
            }
            String fragment = uri.fragment();
            if (fragment != null && fragment.contains("lazyLink_"))
            {
                return fragment.substring(fragment.lastIndexOf("::") + 2);
            }
        }
        String symbolic = names.generateSymbolicName(target, context, reference);
        if (symbolic != null)
        {
            return symbolic;
        }
        String localPath = local.get(target);
        if (localPath == null)
        {
            throw new IllegalStateException("Cannot verify reference '" + reference.getName() + "'");
        }
        return "local:" + localPath;
    }
}
