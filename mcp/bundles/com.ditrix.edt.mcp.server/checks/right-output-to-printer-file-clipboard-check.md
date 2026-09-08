# Right set: Output to Printer, File, Clipboard

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-output-to-printer-file-clipboard` |
| **Category** | Role Rights |
| **Severity** | Major |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `OutputToPrinterFileClipboard` right in roles. This right controls the ability to export data from the system. In some cases, restriction is required to prevent confidential information leaks.

## ❌ Error Examples

```xml
<!-- Potentially incorrect - Output enabled for all users -->
<Rights>
  <Right>
    <Name>OutputToPrinterFileClipboard</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - Temp worker can export data -->
<!-- Role: TemporaryWorker -->
<Rights>
  <Right>
    <Name>OutputToPrinterFileClipboard</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - Output controlled based on role requirements -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>OutputToPrinterFileClipboard</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Restricted roles cannot export -->
<!-- Role: RestrictedUser -->
<Rights>
  <Right>
    <Name>OutputToPrinterFileClipboard</Name>
    <Value>false</Value>
  </Right>
</Rights>

<!-- Correct - Standard users can export -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>OutputToPrinterFileClipboard</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Assess data export security requirements
2. Disable the right for roles with restricted access
3. Allow for roles that need export functionality
4. Document the data export policy

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightOutputToPrinterFileClipboard`
- **Plugin**: `com.e1c.v8codestyle.right`

