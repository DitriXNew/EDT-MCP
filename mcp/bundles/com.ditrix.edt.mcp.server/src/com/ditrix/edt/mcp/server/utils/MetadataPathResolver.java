/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves metadata FQN paths (e.g. "Catalog.Products.Forms.ItemForm") to
 * file system paths relative to the EDT project root.
 * <p>
 * This resolver supports forms and can be extended for other metadata artifacts
 * such as print form templates.
 */
public final class MetadataPathResolver
{
    /** Mapping from singular metadata type (FQN, lowercase) to plural directory name in src/ */
    private static final Map<String, String> METADATA_TYPE_TO_DIR = new HashMap<>();
    static
    {
        METADATA_TYPE_TO_DIR.put("catalog", "Catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("document", "Documents"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("dataprocessor", "DataProcessors"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("report", "Reports"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("informationregister", "InformationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("accumulationregister", "AccumulationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("accountingregister", "AccountingRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("calculationregister", "CalculationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("exchangeplan", "ExchangePlans"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("businessprocess", "BusinessProcesses"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("task", "Tasks"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("chartofaccounts", "ChartsOfAccounts"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("chartofcharacteristictypes", "ChartsOfCharacteristicTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("chartofcalculationtypes", "ChartsOfCalculationTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("documentjournal", "DocumentJournals"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("settingsstorage", "SettingsStorages"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("filtercriterion", "FilterCriteria"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("externaldatasource", "ExternalDataSources"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("enum", "Enums"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("constant", "Constants"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("commonform", "CommonForms"); //$NON-NLS-1$ //$NON-NLS-2$
    }

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
            String type = parts[0].toLowerCase();
            if ("commonform".equals(type) || "commonforms".equals(type)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return "src/CommonForms/" + parts[1] + "/Form.form"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return null;
        }

        // MetadataType.ObjectName.Forms.FormName (4 parts)
        if (parts.length == 4)
        {
            String metadataType = parts[0].toLowerCase();
            String objectName = parts[1];
            String formsKeyword = parts[2];
            String formName = parts[3];

            if (!"forms".equalsIgnoreCase(formsKeyword)) //$NON-NLS-1$
            {
                return null;
            }

            String dirName = resolveMetadataDir(metadataType);
            if (dirName == null)
            {
                return null;
            }

            return "src/" + dirName + "/" + objectName + "/Forms/" + formName + "/Form.form"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        return null;
    }

    /**
     * Resolves a metadata type string (lowercase) to the directory name under {@code src/}.
     * Handles both singular and plural forms (e.g. "catalog" and "catalogs").
     *
     * @param metadataType lowercase metadata type
     * @return directory name (e.g. "Catalogs"), or {@code null} if unknown
     */
    public static String resolveMetadataDir(String metadataType)
    {
        String dirName = METADATA_TYPE_TO_DIR.get(metadataType);
        if (dirName == null && metadataType.endsWith("s")) //$NON-NLS-1$
        {
            dirName = METADATA_TYPE_TO_DIR.get(metadataType.substring(0, metadataType.length() - 1));
        }
        return dirName;
    }
}
