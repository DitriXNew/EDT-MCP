# Collection type in documenting comment

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-collection-item-type` |
| **Severity** | Minor |
| **Type** | Code style |
| **Standard** | № 453 |

## 🎯 What This Check Does

Checks that collection type (array, map, etc.) in documenting comment contains description of collection item type.

## ❌ Error Examples

### Example 1 - Collection without item type

```bsl
// Returns list of products
//
// Returns:
//  Array - list of products ← ERROR: Collection type without item type
//
Function GetProducts()
    Result = New Array;
    // ...
    Return Result;
EndFunction
```

### Example 2 - Map without key/value types

```bsl
// Gets settings
//
// Parameters:
//  Settings - Map - user settings ← ERROR: Map without item types
//
Procedure LoadSettings(Settings)
```

## ✅ Correct Solutions

### Example 1 - Array with item type

```bsl
// Returns list of products
//
// Returns:
//  Array of CatalogRef.Products - list of products
//
Function GetProducts()
    Result = New Array;
    // ...
    Return Result;
EndFunction
```

### Example 2 - Map with key-value types

```bsl
// Gets settings
//
// Parameters:
//  Settings - Map of KeyAndValue:
//      * Key - String - setting name
//      * Value - Arbitrary - setting value
//
Procedure LoadSettings(Settings)
```

### Example 3 - ValueList with item type

```bsl
// Returns available values
//
// Returns:
//  ValueList of EnumRef.PaymentTypes - payment methods
//
Function GetPaymentTypes()
```

## 🔧 How to Fix

1. Find collection types in documentation comments
2. Add item type specification:
   - For `Array` - add `of <ItemType>`
   - For `Map` - add `of KeyAndValue` with nested description
   - For `ValueList` - add `of <ValueType>`
   - For `FixedArray` - add `of <ItemType>`
3. Use concrete types instead of `Arbitrary` where possible

### Collection types requiring item specification:
- Array / FixedArray
- Map / FixedMap
- ValueList
- FixedCollection

## 🔍 Technical Details

- **Java class**: `CollectionTypeDefinitionCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Configurable parameter**: `collectionTypes` - list of collection type names

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
- [Documentation comment format](https://its.1c.ru/db/v8std#content:453:hdoc)
