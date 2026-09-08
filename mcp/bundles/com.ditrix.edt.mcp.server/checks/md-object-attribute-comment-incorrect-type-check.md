# "Comment" attribute has invalid type

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `md-object-attribute-comment-incorrect-type` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Checks that "Comment" attribute in documents and catalogs has correct data type: unlimited length string with multiline mode enabled.

## ❌ Error Examples

### Example 1 - Wrong type

```xml
<Attribute>
  <Name>Comment</Name>
  <Type>Number</Type>  <!-- ERROR: Type is not String -->
</Attribute>
```

### Example 2 - Limited length string

```xml
<Attribute>
  <Name>Comment</Name>
  <Type>
    <StringType>
      <Length>100</Length>  <!-- ERROR: Should be unlimited -->
    </StringType>
  </Type>
</Attribute>
```

### Example 3 - Without multiline mode

```xml
<Attribute>
  <Name>Comment</Name>
  <Type>
    <StringType>
      <Length>0</Length>  <!-- Unlimited -->
    </StringType>
  </Type>
  <MultiLine>false</MultiLine>  <!-- ERROR: MultiLine not enabled -->
</Attribute>
```

## ✅ Compliant Solutions

### Example 1 - Correct Comment attribute

```xml
<Attribute>
  <Name>Comment</Name>
  <Type>
    <StringType>
      <Length>0</Length>  <!-- Unlimited string -->
    </StringType>
  </Type>
  <MultiLine>true</MultiLine>
</Attribute>
```

## 🔧 How to Fix

1. Open the "Comment" attribute in Configurator
2. Set type: Unlimited length string
3. Enable "Multiline mode" flag

### Requirements for "Comment" attribute:
- Type: String
- Length: Unlimited (0)
- Multiline mode: Yes

### Checked objects:
- Documents
- Catalogs (optional)

## 🔍 Technical Details

- **Java class**: `MdObjectAttributeCommentCheck`
- **Location**: `com.e1c.v8codestyle.md.check`
- **Configurable parameters**:
  - `checkDocuments` - check documents
  - `checkCatalogs` - check catalogs

## 📚 References

- [1C Development Standards](https://its.1c.ru/db/v8std)
