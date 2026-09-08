# Using hyphen-minus in documenting comment

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-use-minus` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Checks that only hyphen-minus character (hyphen-minus, U+002D) is used in documenting comments, not other similar symbols (em dash, en dash, etc.).

## ❌ Error Examples

### Example 1 - Wrong dash character

```bsl
// Parameters:
//  Value — String — value  ← ERROR: Em dash used instead of hyphen-minus
//
Procedure Process(Value)
```

### Example 2 - En dash

```bsl
// Returns:
//  Number – count  ← ERROR: En dash (U+2013) instead of hyphen-minus
//
Function GetCount()
```

### Example 3 - Various wrong characters

```bsl
// Parameters:
//  Data ‒ Structure ‒ data  ← ERROR: Figure dash used
//
Procedure LoadData(Data)
```

## ✅ Compliant Solutions

### Example 1 - Correct hyphen-minus

```bsl
// Parameters:
//  Value - String - value
//
Procedure Process(Value)
```

### Example 2 - Proper format

```bsl
// Returns:
//  Number - count
//
Function GetCount()
```

### Example 3 - Standard hyphen

```bsl
// Parameters:
//  Data - Structure - data
//
Procedure LoadData(Data)
```

## 🔧 How to Fix

1. Replace all dash-like characters with hyphen-minus (-)
2. Hyphen-minus is the standard keyboard hyphen
3. ASCII code 45 (U+002D)
4. Available on standard keyboard next to "0" key

### Characters to avoid:
- Em dash (—) U+2014
- En dash (–) U+2013
- Figure dash (‒) U+2012
- Horizontal bar (―) U+2015
- Minus sign (−) U+2212

### Correct character:
- Hyphen-minus (-) U+002D

## 🔍 Technical Details

- **Java class**: `DocCommentUseMinusCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Error message**: "Only hyphen-minus symbol is allowed in documentation comment"

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
