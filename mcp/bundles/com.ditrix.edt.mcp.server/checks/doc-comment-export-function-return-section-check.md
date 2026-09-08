# Return value section for export function

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-export-function-return-section` |
| **Severity** | Major |
| **Type** | Code style |
| **Standard** | № 453 |

## 🎯 What This Check Does

Checks that export function has "Returns" section in documenting comment. If function refers to another function via "See", the section is checked in the linked function.

## ❌ Error Examples

### Example 1 - Missing return section

```bsl
// Gets list of available products
//
// Parameters:
//  Filter - Structure - selection filter  ← ERROR: No Returns section
//
Function GetProducts(Filter) Export
    // ...
EndFunction
```

### Example 2 - Empty return section

```bsl
// Calculates document total
//
// Parameters:
//  Document - DocumentRef.SalesOrder - document reference
//
// Returns:
//  ← ERROR: Return section is empty
//
Function CalculateTotal(Document) Export
```

## ✅ Correct Solutions

### Example 1 - With return section

```bsl
// Gets list of available products
//
// Parameters:
//  Filter - Structure - selection filter
//
// Returns:
//  Array of CatalogRef.Products - matching products
//
Function GetProducts(Filter) Export
    // ...
EndFunction
```

### Example 2 - With See reference

```bsl
// Gets list of available products
//
// See Common.GetProducts
//
Function GetProducts(Filter) Export
    Return Common.GetProducts(Filter);
EndFunction
```

### Example 3 - Detailed return section

```bsl
// Gets user settings
//
// Returns:
//  Structure:
//      * Theme - String - UI theme name
//      * Language - String - language code
//      * Timeout - Number - session timeout in minutes
//
Function GetUserSettings() Export
```

## 🔧 How to Fix

1. Add `// Returns:` section after parameters
2. Specify return type and description
3. For complex types, describe the structure
4. Alternatively, use `// See FunctionName` to inherit documentation

### Required format:
```
// Returns:
//  <Type> - <Description>
```

## 🔍 Technical Details

- **Java class**: `ExportFunctionReturnSectionCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Applies to**: Export functions only

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
