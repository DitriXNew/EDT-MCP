# Right set: Start Web Client

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-start-web-client` |
| **Category** | Role Rights |
| **Severity** | Minor |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `StartWebClient` right in roles. Web client provides browser access without application installation. Checks the correct configuration in accordance with system access policy.

## ❌ Error Examples

```xml
<!-- Potentially incorrect - WebClient disabled for mobile users -->
<!-- Role: MobileUser -->
<Rights>
  <Right>
    <Name>StartWebClient</Name>
    <Value>false</Value>
  </Right>
</Rights>

<!-- Incorrect - No client access at all -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>StartThinClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartThickClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartWebClient</Name>
    <Value>false</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - WebClient for remote users -->
<!-- Role: RemoteUser -->
<Rights>
  <Right>
    <Name>StartWebClient</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - WebClient only for external users -->
<!-- Role: ExternalUser -->
<Rights>
  <Right>
    <Name>StartThinClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartThickClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartWebClient</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - All clients for administrator -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>StartWebClient</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Allow StartWebClient for roles with remote access
2. Use web client for external users
3. Ensure the role has a way to connect to the system
4. Document the web client usage policy

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightStartWebClient`
- **Plugin**: `com.e1c.v8codestyle.right`

