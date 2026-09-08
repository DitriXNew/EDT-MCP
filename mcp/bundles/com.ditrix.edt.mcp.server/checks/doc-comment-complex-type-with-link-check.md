# Documenting comment field uses complex type instead of link

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-complex-type-with-link` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Checks that documenting comment field uses link to constructor function instead of full description of complex type. This improves readability and allows to avoid duplication of descriptions.

## ❌ Error Examples

### Example 1 - Full structure description instead of link

```bsl
// Gets document data
//
// Returns:
//  Structure:
//      * Date - Date - document date
//      * Number - String - document number
//      * Amount - Number - total amount  ← ERROR: Use link to constructor
//      * Currency - CatalogRef.Currencies - currency
//
Function GetDocumentData()
```

### Example 2 - Inline complex type description

```bsl
// Parameters:
//  Settings - Structure:
//      * User - String
//      * Password - String  ← ERROR: Complex type repeated in multiple places
//      * Server - String
//
Procedure Connect(Settings)
```

## ✅ Correct Solutions

### Example 1 - Link to constructor function

```bsl
// Gets document data
//
// Returns:
//  Structure - See NewDocumentData
//
Function GetDocumentData()
    Return NewDocumentData();
EndFunction

// Creates new document data structure
//
// Returns:
//  Structure:
//      * Date - Date - document date
//      * Number - String - document number
//      * Amount - Number - total amount
//      * Currency - CatalogRef.Currencies - currency
//
Function NewDocumentData()
    Result = New Structure;
    Result.Insert("Date", CurrentDate());
    Result.Insert("Number", "");
    Result.Insert("Amount", 0);
    Result.Insert("Currency", Undefined);
    Return Result;
EndFunction
```

### Example 2 - Reference to type definition

```bsl
// Parameters:
//  Settings - See ConnectionSettings
//
Procedure Connect(Settings)

// Returns connection settings structure
//
// Returns:
//  Structure:
//      * User - String
//      * Password - String
//      * Server - String
//
Function ConnectionSettings()
```

## 🔧 How to Fix

1. Create a constructor function that returns the complex type
2. Document the structure in the constructor function
3. In other places, use `See FunctionName` reference
4. This avoids duplication and keeps structure definition in one place

### Types that should be moved to constructors:
- Structure / FixedStructure
- ValueTable / ValueTree
- Array with complex items
- Map with specific key-value types

## 🔍 Technical Details

- **Java class**: `FieldDefinitionTypeWithLinkRefCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Configurable parameter**: `collectionTypes` - types to check

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
- [Constructor function pattern](https://its.1c.ru/db/v8std#content:640:hdoc)
