/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

/**
 * Resolves metadata FQN paths (e.g. "Catalog.Products.Forms.ItemForm") to
 * file system paths relative to the EDT project root.
 * <p>
 * This resolver supports forms and can be extended for other metadata artifacts
 * such as print form templates.
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
     * <p>Russian type names are also supported (e.g. "Справочник.Товары.Forms.ФормаЭлемента").
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

            if (!"forms".equalsIgnoreCase(formsKeyword)) //$NON-NLS-1$
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
