# Reference to existing object

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-ref-link` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Checks that reference in documenting comment (`See` / `См.` construction) points to existing metadata object or method.

## ❌ Error Examples

### Example 1 - Reference to non-existent method

```bsl
// See Common.NonExistentMethod  ← ERROR: Method does not exist
//
Function GetData()
```

### Example 2 - Invalid object reference

```bsl
// See Catalogs.NonExistentCatalog  ← ERROR: Object does not exist
//
Procedure ProcessItem()
```

### Example 3 - Broken reference

```bsl
// Parameters:
//  Data - See DataProcessing.MyReport  ← ERROR: Reference not found
//
Procedure LoadData(Data)
```

## ✅ Correct Solutions

### Example 1 - Valid method reference

```bsl
// See Common.GetCurrentUser
//
Function GetData()
```

### Example 2 - Reference to existing object

```bsl
// See Catalogs.Products
//
Procedure ProcessItem()
```

### Example 3 - Valid type reference

```bsl
// Parameters:
//  Data - See DataProcessor.ImportData.LoadedData
//
Procedure LoadData(Data)
```

## 🔧 How to Fix

1. Verify the referenced object exists
2. Check spelling of object and method names
3. Use correct path to the referenced object
4. Update references if objects were renamed

### Reference formats:
- `See ModuleName.MethodName`
- `See Catalogs.CatalogName`
- `See CommonModule.MethodName`
- `See DataProcessor.Name.MethodName`

### Configurable options:
- `allowSeeInDescription` - Allow "See" in description section

## 🔍 Technical Details

- **Java class**: `RefLinkPartCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Error message**: "Link referenced to unexisting object"

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
