# Privileged mode when posting document

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `document-post-in-privileged-mode` |
| **Severity** | Major |
| **Type** | Warning |
| **Standard** | № 689 |

## 🎯 What This Check Does

Checks that document that allows posting has flags "Privileged mode on posting" and "Privileged mode on cancel posting" set.

## ❌ Error Examples

### Example 1 - Flag not set

**Document metadata XML:**
```xml
<Document>
  <Name>SalesOrder</Name>
  <Posting>Allow</Posting>
  <PrivilegedModeOnPosting>false</PrivilegedModeOnPosting>  <!-- ERROR -->
  <PrivilegedModeOnCancelPosting>false</PrivilegedModeOnCancelPosting>  <!-- ERROR -->
</Document>
```

### Example 2 - Partial configuration

```xml
<Document>
  <Name>Receipt</Name>
  <Posting>Allow</Posting>
  <PrivilegedModeOnPosting>true</PrivilegedModeOnPosting>
  <PrivilegedModeOnCancelPosting>false</PrivilegedModeOnCancelPosting>  <!-- ERROR -->
</Document>
```

## ✅ Compliant Solutions

### Example 1 - Both flags set

```xml
<Document>
  <Name>SalesOrder</Name>
  <Posting>Allow</Posting>
  <PrivilegedModeOnPosting>true</PrivilegedModeOnPosting>
  <PrivilegedModeOnCancelPosting>true</PrivilegedModeOnCancelPosting>
</Document>
```

### Example 2 - Document without posting

```xml
<Document>
  <Name>InternalDocument</Name>
  <Posting>Deny</Posting>
  <!-- Flags not required when posting is disabled -->
</Document>
```

## 🔧 How to Fix

1. Open document properties in Configurator
2. Navigate to the "Movements" tab
3. Enable "Privileged mode on posting" flag
4. Enable "Privileged mode on cancel posting" flag

### Exception
Documents intended for direct adjustment of register records may be posted with access rights verification. In this case, you must provide roles that grant rights to modify registers.

## 🔍 Technical Details

- **Category**: Metadata checks
- **Applicability**: Documents with posting allowed

## 📚 References

- [Standard #689 - Roles and Access Rights Configuration](https://its.1c.ru/db/v8std#content:689:hdoc:1.7)
