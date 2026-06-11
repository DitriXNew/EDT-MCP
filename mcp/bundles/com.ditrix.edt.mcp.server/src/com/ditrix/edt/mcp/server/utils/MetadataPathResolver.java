/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

/**
 * Resolves metadata FQN paths (e.g. "Catalog.Products.Forms.ItemForm") to
 * file system paths relative to the EDT project root.
 * <p>
 * This resolver supports forms and top-object {@code .mdo} files
 * ({@link #resolveTopObjectMdoPath(String)}) and can be extended for other metadata
 * artifacts such as print form templates.
 * Supports both English and Russian metadata type names via {@link MetadataTypeUtils}.
 */
public final class MetadataPathResolver
{
    private MetadataPathResolver()
    {
        // Utility class
    }

    /**
     * Resolves a form FQN path to a file path relative to the project root.
     *
     * <p>Supported formats:
     * <ul>
     *   <li>{@code "Catalog.Products.Forms.ItemForm"} &rarr;
     *       {@code "src/Catalogs/Products/Forms/ItemForm/Form.form"}</li>
     *   <li>{@code "CommonForm.MyForm"} &rarr;
     *       {@code "src/CommonForms/MyForm/Form.form"}</li>
     * </ul>
     *
     * <p>The form segment accepts the same token set as
     * {@link FormElementWriter#isFormToken(String)} - {@code Form} / {@code Forms} and their Russian
     * equivalents, case-insensitive - so a form FQN accepted by create_metadata resolves here too.
     * Russian type names are also supported (e.g. "Справочник.Товары.Forms.ФормаЭлемента").
     *
     * @param formPath FQN path
     * @return file path relative to project root, or {@code null} if cannot resolve
     */
    public static String resolveFormFilePath(String formPath)
    {
        if (formPath == null || formPath.isEmpty())
        {
            return null;
        }

        String[] parts = formPath.split("\\."); //$NON-NLS-1$

        // CommonForm.FormName (2 parts)
        if (parts.length == 2)
        {
            String dirName = resolveMetadataDir(parts[0]);
            if ("CommonForms".equals(dirName)) //$NON-NLS-1$
            {
                return "src/CommonForms/" + parts[1] + "/Form.form"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return null;
        }

        // MetadataType.ObjectName.Forms.FormName (4 parts)
        if (parts.length == 4)
        {
            String objectName = parts[1];
            String formsKeyword = parts[2];
            String formName = parts[3];

            // ONE shared predicate with the FQN parsers (Form/Forms + Russian, case-insensitive), so
            // every form path create_metadata accepts is addressable by screenshot/snapshot too.
            if (!FormElementWriter.isFormToken(formsKeyword))
            {
                return null;
            }

            String dirName = resolveMetadataDir(parts[0]);
            if (dirName == null)
            {
                return null;
            }

            return "src/" + dirName + "/" + objectName + "/Forms/" + formName + "/Form.form"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        return null;
    }

    /**
     * Resolves the FOLDER that holds a form's resource files (e.g. {@code Form.form} and its
     * {@code Module.bsl}), relative to the project root - the directory portion of
     * {@link #resolveFormFilePath(String)} without the trailing {@code /Form.form}.
     *
     * <p>Supported formats (same as {@link #resolveFormFilePath(String)}):
     * <ul>
     *   <li>{@code "Catalog.Products.Forms.ItemForm"} &rarr;
     *       {@code "src/Catalogs/Products/Forms/ItemForm"}</li>
     *   <li>{@code "CommonForm.MyForm"} &rarr; {@code "src/CommonForms/MyForm"}</li>
     * </ul>
     *
     * @param formPath FQN path
     * @return the form-folder path relative to the project root, or {@code null} if cannot resolve
     */
    public static String resolveFormFolderPath(String formPath)
    {
        String filePath = resolveFormFilePath(formPath);
        if (filePath == null)
        {
            return null;
        }
        int slash = filePath.lastIndexOf('/');
        // resolveFormFilePath always ends in "/Form.form", so a slash is guaranteed; defensive anyway.
        return slash > 0 ? filePath.substring(0, slash) : null;
    }

    /**
     * Resolves a TOP metadata object FQN (e.g. {@code "Catalog.Products"}) to its
     * {@code .mdo} file path relative to the EDT project root
     * (e.g. {@code "src/Catalogs/Products/Products.mdo"}).
     *
     * <p>Returns {@code null} when the FQN does not map to an own {@code .mdo} file
     * under a {@code src/} type directory:
     * <ul>
     *   <li>{@code null}, empty, or dotless FQN (e.g. the {@code Configuration} root),
     *       or an FQN with a leading/trailing dot;</li>
     *   <li>an unknown type token, or a type with no {@code src/} directory layout
     *       (resolved via {@link MetadataTypeUtils#getDirectoryName(String)}).</li>
     * </ul>
     *
     * <p>The type token is bilingual (English/Russian, singular/plural, via
     * {@link MetadataTypeUtils}); the object Name passes through verbatim. Only TOP
     * object FQNs ({@code Type.Name}) are supported - member FQNs have no own
     * {@code .mdo} file.
     *
     * @param fqn the top-object FQN (e.g. {@code "Catalog.Products"})
     * @return the {@code .mdo} path relative to the project root (e.g.
     *         {@code "src/Catalogs/Products/Products.mdo"}), or {@code null}
     */
    public static String resolveTopObjectMdoPath(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot >= fqn.length() - 1)
        {
            // Dotless FQN (e.g. the Configuration root) - not a type-directory object.
            return null;
        }
        String type = fqn.substring(0, dot);
        String name = fqn.substring(dot + 1);
        String dir = resolveMetadataDir(type);
        if (dir == null)
        {
            return null;
        }
        return "src/" + dir + "/" + name + "/" + name + ".mdo"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Resolves a metadata type string to the directory name under {@code src/}.
     * Handles English/Russian, singular/plural forms via {@link MetadataTypeUtils}.
     *
     * @param metadataType metadata type name in any recognized form
     * @return directory name (e.g. "Catalogs"), or {@code null} if unknown
     */
    public static String resolveMetadataDir(String metadataType)
    {
        return MetadataTypeUtils.getDirectoryName(metadataType);
    }
}
