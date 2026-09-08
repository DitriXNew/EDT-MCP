# Return value section for procedure

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-procedure-return-section` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Checks that documenting comment of procedure does not contain "Returns" section. Procedures do not return values, so Returns section should not be present.

## ❌ Error Examples

### Example 1 - Procedure with Returns section

```bsl
// Clears cache.
//
// Returns:
//  Boolean - success flag  ← ERROR: Procedure cannot have Returns section
//
Procedure ClearCache()
```

### Example 2 - Procedure with return type

```bsl
// Saves document.
//
// Parameters:
//  Document - DocumentObject - document to save
//
// Returns:  ← ERROR: Remove Returns section
//  Undefined
//
Procedure SaveDocument(Document)
```

## ✅ Correct Solutions

### Example 1 - Procedure without Returns

```bsl
// Clears cache.
//
Procedure ClearCache()
```

### Example 2 - Use Function if returning value

```bsl
// Saves document and returns result.
//
// Parameters:
//  Document - DocumentObject - document to save
//
// Returns:
//  Boolean - True if saved successfully
//
Function SaveDocument(Document)
    // ...
    Return True;
EndFunction
```

### Example 3 - Proper procedure documentation

```bsl
// Saves document.
//
// Parameters:
//  Document - DocumentObject - document to save
//
Procedure SaveDocument(Document)
```

## 🔧 How to Fix

1. Remove `// Returns:` section from procedure documentation
2. If method needs to return value, change to Function
3. Keep only Description and Parameters sections for procedures

### Procedure documentation structure:
```
// Description
//
// Parameters:
//  ParamName - Type - description
//
```

## 🔍 Technical Details

- **Java class**: `ProcedureReturnSectionCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Error message**: "Return section is not allowed for procedure"

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
