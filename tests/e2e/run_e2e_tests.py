#!/usr/bin/env python3
"""
EDT MCP Server - End-to-End Test Suite

Tests all MCP tools by sending HTTP requests to a running MCP server.
Requires a running EDT instance with the MCP plugin and a loaded test configuration.

Usage:
    python run_e2e_tests.py [--host HOST] [--port PORT] [--project PROJECT]

Environment variables:
    MCP_HOST    - MCP server host (default: localhost)
    MCP_PORT    - MCP server port (default: 8765)
    MCP_PROJECT - EDT project name (default: TestConfiguration)
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error
from dataclasses import dataclass, field
from typing import Any


# ──────────────────────────────────────────────────────────────────────────────
# Configuration
# ──────────────────────────────────────────────────────────────────────────────

@dataclass
class Config:
    host: str = "localhost"
    port: int = 8765
    project: str = "TestConfiguration"

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    @property
    def mcp_url(self) -> str:
        return f"{self.base_url}/mcp"

    @property
    def health_url(self) -> str:
        return f"{self.base_url}/health"


# ──────────────────────────────────────────────────────────────────────────────
# JSON-RPC client
# ──────────────────────────────────────────────────────────────────────────────

_request_id = 0


def next_id() -> int:
    global _request_id
    _request_id += 1
    return _request_id


def send_jsonrpc(url: str, method: str, params: dict | None = None,
                 session_id: str | None = None, timeout: int = 120) -> dict:
    """Send a JSON-RPC 2.0 request and return parsed response."""
    payload = {
        "jsonrpc": "2.0",
        "id": next_id(),
        "method": method,
    }
    if params is not None:
        payload["params"] = params

    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if session_id:
        headers["MCP-Session-Id"] = session_id

    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8") if e.fp else ""
        return {"error": {"code": e.code, "message": f"HTTP {e.code}: {body}"}}
    except urllib.error.URLError as e:
        return {"error": {"code": -1, "message": str(e.reason)}}


def call_tool(url: str, tool_name: str, arguments: dict | None = None,
              session_id: str | None = None, timeout: int = 120) -> dict:
    """Call an MCP tool via tools/call."""
    params = {"name": tool_name}
    if arguments:
        params["arguments"] = arguments
    return send_jsonrpc(url, "tools/call", params, session_id, timeout)


# ──────────────────────────────────────────────────────────────────────────────
# Test result tracking
# ──────────────────────────────────────────────────────────────────────────────

@dataclass
class TestResult:
    name: str
    passed: bool
    duration_ms: float = 0
    message: str = ""
    response: dict = field(default_factory=dict)


class TestRunner:
    def __init__(self, config: Config):
        self.config = config
        self.results: list[TestResult] = []
        self.session_id: str | None = None

    def run_all(self) -> bool:
        """Run all tests and return True if all passed."""
        print(f"\n{'='*70}")
        print(f"  EDT MCP Server E2E Tests")
        print(f"  Server: {self.config.mcp_url}")
        print(f"  Project: {self.config.project}")
        print(f"{'='*70}\n")

        # Phase 1: Health & Protocol
        self._section("Protocol Tests")
        self._test("health_check", self.test_health_check)
        self._test("initialize", self.test_initialize)
        self._test("tools_list", self.test_tools_list)
        self._test("invalid_method", self.test_invalid_method)
        self._test("invalid_jsonrpc", self.test_invalid_jsonrpc)
        self._test("tool_not_found", self.test_tool_not_found)

        # Phase 2: Tools (no project needed)
        self._section("Standalone Tools")
        self._test("get_edt_version", self.test_get_edt_version)
        self._test("list_projects", self.test_list_projects)
        self._test("get_platform_documentation", self.test_get_platform_documentation)
        self._test("get_check_description", self.test_get_check_description)

        # Phase 3: Tools requiring a project
        self._section("Project Tools")
        self._test("get_configuration_properties", self.test_get_configuration_properties)
        self._test("get_metadata_objects", self.test_get_metadata_objects)
        self._test("get_metadata_objects_catalogs", self.test_get_metadata_objects_catalogs)
        self._test("get_metadata_details", self.test_get_metadata_details)
        self._test("metadata_bilingual_type_token", self.test_metadata_bilingual_type_token)
        self._test("get_problem_summary", self.test_get_problem_summary)
        self._test("get_project_errors", self.test_get_project_errors)
        self._test("get_tags", self.test_get_tags)
        self._test("get_bookmarks", self.test_get_bookmarks)
        self._test("get_tasks", self.test_get_tasks)
        self._test("create_metadata_object", self.test_create_metadata_object)
        self._test("create_metadata_object_invalid_name", self.test_create_metadata_object_invalid_name)
        self._test("create_metadata_object_with_language", self.test_create_metadata_object_with_language)
        self._test("create_metadata_object_cyrillic_synonym", self.test_create_metadata_object_cyrillic_synonym)
        self._test("add_metadata_attribute", self.test_add_metadata_attribute)
        self._test("delete_metadata_object", self.test_delete_metadata_object)

        # Phase 4: BSL code tools
        self._section("BSL Code Tools")
        self._test("list_modules", self.test_list_modules)
        self._test("get_module_structure", self.test_get_module_structure)
        self._test("read_module_source", self.test_read_module_source)
        self._test("read_method_source", self.test_read_method_source)
        self._test("search_in_code", self.test_search_in_code)
        self._test("search_in_code_count", self.test_search_in_code_count)
        self._test("validate_query", self.test_validate_query)

        # Phase 5: Advanced tools
        self._section("Advanced Tools")
        self._test("find_references", self.test_find_references)
        self._test("get_applications", self.test_get_applications)
        self._test("get_form_screenshot", self.test_get_form_screenshot)

        # Summary
        self._print_summary()
        all_passed = all(r.passed for r in self.results)
        if all_passed:
            if not self._cleanup_e2e_artifacts():
                print("\n  CLEANUP FAILED — E2E artifacts may remain in the project")
                return False
        return all_passed

    def _section(self, title: str):
        print(f"\n--- {title} ---")

    def _test(self, name: str, fn):
        start = time.time()
        try:
            fn()
            duration_ms = (time.time() - start) * 1000
            result = TestResult(name=name, passed=True, duration_ms=duration_ms)
            self.results.append(result)
            print(f"  PASS  {name} ({duration_ms:.0f}ms)")
        except AssertionError as e:
            duration_ms = (time.time() - start) * 1000
            result = TestResult(name=name, passed=False, duration_ms=duration_ms,
                                message=str(e))
            self.results.append(result)
            print(f"  FAIL  {name} ({duration_ms:.0f}ms): {e}")
        except Exception as e:
            duration_ms = (time.time() - start) * 1000
            result = TestResult(name=name, passed=False, duration_ms=duration_ms,
                                message=f"Exception: {e}")
            self.results.append(result)
            print(f"  ERROR {name} ({duration_ms:.0f}ms): {e}")

    def _print_summary(self):
        passed = sum(1 for r in self.results if r.passed)
        failed = sum(1 for r in self.results if not r.passed)
        total = len(self.results)
        total_time = sum(r.duration_ms for r in self.results)

        print(f"\n{'='*70}")
        print(f"  Results: {passed}/{total} passed, {failed} failed ")
        print(f"  Total time: {total_time/1000:.1f}s")
        print(f"{'='*70}")

        if failed > 0:
            print(f"\n  Failed tests:")
            for r in self.results:
                if not r.passed:
                    print(f"    - {r.name}: {r.message}")
            print()

    # ──────────────────────────────────────────────────────────────────────
    # Helpers
    # ──────────────────────────────────────────────────────────────────────

    def _assert_success(self, resp: dict, msg: str = ""):
        """Assert JSON-RPC response has result (no error)."""
        assert "error" not in resp or resp.get("error") is None, \
            f"Expected success but got error: {resp.get('error')} {msg}"
        assert "result" in resp, f"Missing 'result' in response {msg}"

    def _assert_error(self, resp: dict, code: int | None = None, msg: str = ""):
        """Assert JSON-RPC response is an error."""
        assert "error" in resp and resp["error"] is not None, \
            f"Expected error but got success {msg}"
        if code is not None:
            assert resp["error"]["code"] == code, \
                f"Expected error code {code}, got {resp['error']['code']} {msg}"

    def _call(self, tool_name: str, args: dict | None = None, timeout: int = 120) -> dict:
        return call_tool(self.config.mcp_url, tool_name, args, self.session_id, timeout)

    def _get_result_text(self, resp: dict) -> str:
        """Extract text content from tool call result."""
        result = resp.get("result", {})
        content = result.get("content", [])
        if content and content[0].get("type") == "text":
            return content[0].get("text", "")
        if content and content[0].get("type") == "resource":
            res = content[0].get("resource", {})
            return res.get("text", "")
        return ""

    def _get_structured_content(self, resp: dict) -> Any:
        """Extract structuredContent from tool call result."""
        return resp.get("result", {}).get("structuredContent")

    def _parse_json_result(self, resp: dict) -> dict:
        """Parse a JSON-response tool's payload.

        Prefers structuredContent; falls back to parsing the text content as
        JSON. Returns {} if neither yields a JSON object.
        """
        structured = self._get_structured_content(resp)
        if isinstance(structured, dict):
            return structured
        text = self._get_result_text(resp).strip()
        if text.startswith("{"):
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                return {}
        return {}

    # ──────────────────────────────────────────────────────────────────────
    # Protocol Tests
    # ──────────────────────────────────────────────────────────────────────

    def test_health_check(self):
        req = urllib.request.Request(self.config.health_url)
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            assert body.get("status") == "ok", f"Health check failed: {body}"

    def test_initialize(self):
        resp = send_jsonrpc(self.config.mcp_url, "initialize", {
            "protocolVersion": "2025-11-25",
            "capabilities": {},
            "clientInfo": {"name": "e2e-test", "version": "1.0.0"}
        })
        self._assert_success(resp)
        result = resp["result"]
        assert "protocolVersion" in result, "Missing protocolVersion"
        assert "serverInfo" in result, "Missing serverInfo"

        # Try to extract session ID from headers or use a dummy
        self.session_id = "e2e-test-session"

    def test_tools_list(self):
        resp = send_jsonrpc(self.config.mcp_url, "tools/list",
                            session_id=self.session_id)
        self._assert_success(resp)
        tools = resp["result"].get("tools", [])
        assert len(tools) > 0, "No tools registered"
        names = [t["name"] for t in tools]
        # Verify core tools exist
        for tool in ["get_edt_version", "list_projects", "get_metadata_objects"]:
            assert tool in names, f"Missing tool: {tool}"

    def test_invalid_method(self):
        resp = send_jsonrpc(self.config.mcp_url, "nonexistent/method",
                            session_id=self.session_id)
        self._assert_error(resp, McpConstants.ERROR_METHOD_NOT_FOUND)

    def test_invalid_jsonrpc(self):
        """Send a request with wrong JSON-RPC version."""
        payload = json.dumps({
            "jsonrpc": "1.0",
            "id": next_id(),
            "method": "initialize"
        }).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        req = urllib.request.Request(self.config.mcp_url, data=payload,
                                    headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            assert "error" in body, "Expected error for invalid JSON-RPC version"

    def test_tool_not_found(self):
        resp = self._call("nonexistent_tool_xyz")
        self._assert_error(resp, McpConstants.ERROR_METHOD_NOT_FOUND)

    # ──────────────────────────────────────────────────────────────────────
    # Standalone Tools
    # ──────────────────────────────────────────────────────────────────────

    def test_get_edt_version(self):
        resp = self._call("get_edt_version")
        self._assert_success(resp)
        text = self._get_result_text(resp)
        assert len(text) > 0, "Empty EDT version"

    def test_list_projects(self):
        resp = self._call("list_projects")
        self._assert_success(resp)

    def test_get_platform_documentation(self):
        resp = self._call("get_platform_documentation", {
            "typeName": "Array"
        })
        self._assert_success(resp)

    def test_get_check_description(self):
        resp = self._call("get_check_description", {
            "checkId": "begin-transaction"
        })
        self._assert_success(resp)

    # ──────────────────────────────────────────────────────────────────────
    # Project Tools
    # ──────────────────────────────────────────────────────────────────────

    def test_get_configuration_properties(self):
        resp = self._call("get_configuration_properties", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_metadata_objects(self):
        resp = self._call("get_metadata_objects", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_metadata_objects_catalogs(self):
        resp = self._call("get_metadata_objects", {
            "projectName": self.config.project,
            "metadataType": "catalogs"
        })
        self._assert_success(resp)

    def test_get_metadata_details(self):
        resp = self._call("get_metadata_details", {
            "projectName": self.config.project,
            "objectFqns": ["Catalog.Catalog"]
        })
        self._assert_success(resp)

    def _assert_type_token_identity(self, tool: str, en_args: dict, ru_args: dict):
        """The metadata TYPE token is bilingual: an FQN spelled with the English
        token ('Catalog.X') and the Russian one ('Справочник.X') must resolve to
        the same object and render an identical result. (The object NAME is
        programmatic and never translated.)"""
        en = self._call(tool, en_args)
        self._assert_success(en, f"[{tool} EN type token]")
        ru = self._call(tool, ru_args)
        self._assert_success(ru, f"[{tool} RU type token]")
        en_text = self._get_result_text(en)
        ru_text = self._get_result_text(ru)
        assert en_text == ru_text, (
            f"{tool}: bilingual type-token resolve diverged between the English "
            f"and Russian type token.\n--- EN ---\n{en_text}\n--- RU ---\n{ru_text}")
        return en_text

    def test_metadata_bilingual_type_token(self):
        # Every read tool that accepts an object FQN must treat 'Catalog.X' and
        # 'Справочник.X' identically.
        details = self._assert_type_token_identity(
            "get_metadata_details",
            {"projectName": self.config.project,
             "objectFqns": ["Catalog.Catalog"], "full": True},
            {"projectName": self.config.project,
             "objectFqns": ["Справочник.Catalog"], "full": True})
        assert "## Catalog: Catalog" in details, \
            f"EN type token did not resolve the catalog:\n{details}"
        # The synonym map must be keyed by the language CODE (e.g. 'en'), never
        # the Language object's name ('English'). full=true renders the map as a
        # "| <code> | <value> |" table.
        assert "| en | Catalog |" in details, \
            f"Synonym not keyed by language code 'en':\n{details}"

        self._assert_type_token_identity(
            "find_references",
            {"projectName": self.config.project, "objectFqn": "Catalog.Catalog"},
            {"projectName": self.config.project, "objectFqn": "Справочник.Catalog"})

        self._assert_type_token_identity(
            "go_to_definition",
            {"projectName": self.config.project,
             "symbol": "Catalog.Catalog", "includeSource": False},
            {"projectName": self.config.project,
             "symbol": "Справочник.Catalog", "includeSource": False})

        self._assert_type_token_identity(
            "get_project_errors",
            {"projectName": self.config.project, "objects": ["Catalog.Catalog"]},
            {"projectName": self.config.project, "objects": ["Справочник.Catalog"]})

    def test_validate_query(self):
        # 1C QL is bilingual (SELECT/ВЫБРАТЬ, FROM/ИЗ). Validate that an English
        # query, its Russian-keyword equivalent, and a broken query are each
        # handled correctly against the live configuration.
        en_valid = self._call("validate_query", {
            "projectName": self.config.project,
            "queryText": "SELECT Ref FROM Catalog.Catalog",
        })
        self._assert_success(en_valid, "[EN valid]")
        en = self._parse_json_result(en_valid)
        assert en.get("valid") is True, f"EN query should be valid: {en}"
        assert en.get("errorCount") == 0, f"EN query should have 0 errors: {en}"

        # Russian keywords + Russian type token (Справочник) + Russian standard
        # field (Ссылка) must parse and validate identically.
        ru_valid = self._call("validate_query", {
            "projectName": self.config.project,
            "queryText": "ВЫБРАТЬ Ссылка ИЗ Справочник.Catalog",
        })
        self._assert_success(ru_valid, "[RU valid]")
        ru = self._parse_json_result(ru_valid)
        assert ru.get("valid") is True, f"RU query should be valid: {ru}"
        assert ru.get("errorCount") == 0, f"RU query should have 0 errors: {ru}"

        # A query referencing a non-existent field must be flagged invalid.
        broken = self._call("validate_query", {
            "projectName": self.config.project,
            "queryText": "SELECT NoSuchFieldXYZ FROM Catalog.Catalog",
        })
        self._assert_success(broken, "[broken field]")
        bad = self._parse_json_result(broken)
        assert bad.get("valid") is False, f"Broken query should be invalid: {bad}"
        assert bad.get("errorCount", 0) >= 1, f"Broken query should report errors: {bad}"
        issues_text = json.dumps(bad.get("issues", []))
        assert "NoSuchFieldXYZ" in issues_text, \
            f"Broken query issue should name the bad field: {bad}"

    def test_get_problem_summary(self):
        resp = self._call("get_problem_summary", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_project_errors(self):
        resp = self._call("get_project_errors", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_tags(self):
        resp = self._call("get_tags", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_bookmarks(self):
        resp = self._call("get_bookmarks")
        self._assert_success(resp)

    def test_get_tasks(self):
        resp = self._call("get_tasks", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    # Every metadata type the create_metadata_object tool supports.
    CREATE_METADATA_TYPES = [
        "Catalog",
        "Document",
        "InformationRegister",
        "AccumulationRegister",
        "Enum",
        "CommonModule",
        "Report",
        "DataProcessor",
    ]

    @classmethod
    def e2e_artifact_fqns(cls) -> list[str]:
        """FQNs created or touched by metadata-write E2E tests (deterministic names)."""
        fqns = [f"{metadata_type}.E2EChk{metadata_type}"
                for metadata_type in cls.CREATE_METADATA_TYPES]
        fqns.extend([
            "Catalog.E2EChkLangCatalog",
            "Catalog.E2EChkCyrillicCatalog",
            "Catalog.E2EChkAttrCatalog",
            "DataProcessor.E2EChkDeleteTarget",
        ])
        return fqns

    def _cleanup_e2e_artifacts(self) -> bool:
        """After all tests pass: delete E2E objects, clean the project, verify removal."""
        print("\n--- E2E Cleanup (post-pass) ---")
        errors: list[str] = []
        start = time.time()

        for fqn in self.e2e_artifact_fqns():
            resp = self._call("delete_metadata_object", {
                "projectName": self.config.project,
                "objectFqn": fqn,
                "confirm": True,
            })
            try:
                self._assert_success(resp, f"[cleanup delete {fqn}]")
                text = self._get_result_text(resp)
                if not self._is_delete_cleanup_ok(text):
                    errors.append(
                        f"{fqn}: unexpected delete response: {text[:300]}")
                else:
                    print(f"  deleted {fqn}")
            except AssertionError as e:
                errors.append(str(e))

        if not self._run_clean_project_with_retry(errors):
            pass  # errors already recorded

        for fqn in self.e2e_artifact_fqns():
            details = self._call("get_metadata_details", {
                "projectName": self.config.project,
                "objectFqns": [fqn],
            })
            try:
                self._assert_success(details, f"[cleanup verify {fqn}]")
                dtext = self._get_result_text(details)
                if "not found" not in dtext.lower():
                    errors.append(
                        f"{fqn}: still present after cleanup:\n{dtext[:400]}")
                else:
                    print(f"  verified gone {fqn}")
            except AssertionError as e:
                errors.append(str(e))

        duration_ms = (time.time() - start) * 1000
        if errors:
            print(f"  CLEANUP FAIL ({duration_ms:.0f}ms):")
            for err in errors:
                print(f"    - {err}")
            return False

        print(f"  CLEANUP OK ({duration_ms:.0f}ms)")
        return True

    @staticmethod
    def _is_delete_cleanup_ok(text: str) -> bool:
        """Delete succeeded, or object was already removed."""
        lower = text.lower()
        if "executed" in lower or "completed" in lower or "not found" in lower:
            return True
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            return False
        if data.get("success"):
            return True
        err = str(data.get("error", "")).lower()
        return "not found" in err

    def _run_clean_project_with_retry(self, errors: list[str],
                                    max_attempts: int = 15,
                                    delay_sec: float = 2.0) -> bool:
        """Retry clean_project while EDT reports the project is still building."""
        for attempt in range(1, max_attempts + 1):
            resp = self._call("clean_project", {"projectName": self.config.project})
            try:
                self._assert_success(resp, "[cleanup clean_project]")
            except AssertionError as e:
                errors.append(str(e))
                return False
            text = self._get_result_text(resp)
            if self._is_clean_project_ok(resp, text):
                print("  clean_project OK")
                return True
            if self._is_project_building(text) and attempt < max_attempts:
                time.sleep(delay_sec)
                continue
            errors.append(f"clean_project failed: {text[:300]}")
            return False
        errors.append("clean_project failed: project still building after retries")
        return False

    @staticmethod
    def _is_project_building(text: str) -> bool:
        lower = text.lower()
        if "building" in lower or "is building" in lower:
            return True
        try:
            err = str(json.loads(text).get("error", "")).lower()
            return "building" in err
        except json.JSONDecodeError:
            return False

    @staticmethod
    def _is_clean_project_ok(resp: dict, text: str) -> bool:
        sc = resp.get("result", {}).get("structuredContent")
        if isinstance(sc, dict) and sc.get("success"):
            return True
        try:
            return bool(json.loads(text).get("success"))
        except json.JSONDecodeError:
            return '"success":true' in text.replace(" ", "")

    def _create_metadata_object(self, metadata_type: str):
        """Create one object of the given type, then read it back via EDT MCP
        (get_metadata_details) and verify the attributes that were set at
        creation (synonym, comment). Tolerant to re-runs: a second invocation
        returns an "already exists" tool-level message, which is still a valid
        (non-error) JSON-RPC response — the existing object keeps the same
        deterministic synonym/comment, so the read-back assertions still hold."""
        name = "E2EChk" + metadata_type
        synonym = "E2EChk " + metadata_type + " Synonym"
        comment = "E2EChk " + metadata_type + " Comment"
        fqn = metadata_type + "." + name

        # 1. Create with synonym + comment
        resp = self._call("create_metadata_object", {
            "projectName": self.config.project,
            "metadataType": metadata_type,
            "name": name,
            "synonym": synonym,
            "comment": comment,
        })
        self._assert_success(resp, f"[create {metadata_type}]")
        text = self._get_result_text(resp)
        assert (name in text or "already exists" in text), \
            f"Unexpected create result for {metadata_type}: {text}"

        # 2. Read the object back via EDT MCP and verify the attributes set at creation.
        details = self._call("get_metadata_details", {
            "projectName": self.config.project,
            "objectFqns": [fqn],
        })
        self._assert_success(details, f"[read {metadata_type}]")
        dtext = self._get_result_text(details)
        assert "not found" not in dtext.lower(), \
            f"{fqn}: object not found when reading back:\n{dtext}"
        assert name in dtext, \
            f"{fqn}: name not present in details:\n{dtext}"
        assert synonym in dtext, \
            f"{fqn}: synonym '{synonym}' not set/returned:\n{dtext}"
        assert comment in dtext, \
            f"{fqn}: comment '{comment}' not set/returned:\n{dtext}"

        # 3. Strong check: the synonym must be stored under the language CODE
        # (e.g. "en", "ru"), not under the Language object's NAME (e.g. "English").
        # get_metadata_details applies a language fallback, so the value alone does
        # not prove the key is correct — inspect the full synonym map instead, where
        # full=true renders it as a "| <language> | <value> |" table.
        self._assert_synonym_language_code(fqn, synonym)

    # A 1C language code: short ISO-like code, optionally with a region suffix
    # (e.g. "en", "ru", "en_US", "zh-Hans"). The Language object's *name*
    # ("English", "Русский") never matches this.
    _LANG_CODE_RE = re.compile(r"^[a-z]{2,3}([_-][A-Za-z]{2,4})?$")

    def _assert_synonym_language_code(self, fqn: str, synonym: str):
        details = self._call("get_metadata_details", {
            "projectName": self.config.project,
            "objectFqns": [fqn],
            "full": True,
        })
        self._assert_success(details, f"[read-full {fqn}]")
        text = self._get_result_text(details)

        # Collect every table row "| KEY | VALUE |" whose VALUE is our synonym.
        keys_for_synonym = []
        for line in text.splitlines():
            cells = [c.strip() for c in line.split("|")]
            # "| KEY | VALUE |" -> ['', 'KEY', 'VALUE', '']
            if len(cells) >= 4 and cells[2] == synonym:
                keys_for_synonym.append(cells[1])

        # The synonym map row (exclude the "Basic Properties" row keyed by the
        # literal property name "Synonym").
        map_keys = [k for k in keys_for_synonym if k != "Synonym"]
        assert map_keys, (
            f"{fqn}: synonym map row not found in full details "
            f"(synonym '{synonym}' not rendered as a language/value entry):\n{text}")
        for key in map_keys:
            assert self._LANG_CODE_RE.match(key), (
                f"{fqn}: synonym stored under '{key}', which is not a language code "
                f"(expected e.g. 'en'/'ru' — got the Language name instead?)")

    def test_create_metadata_object(self):
        # Create one object of every supported metadata type.
        failures = []
        for metadata_type in self.CREATE_METADATA_TYPES:
            try:
                self._create_metadata_object(metadata_type)
            except AssertionError as e:
                failures.append(str(e))
        assert not failures, "Failed types:\n  " + "\n  ".join(failures)

    def test_create_metadata_object_invalid_name(self):
        # Invalid identifier must be rejected at the tool level (no mutation).
        resp = self._call("create_metadata_object", {
            "projectName": self.config.project,
            "metadataType": "Catalog",
            "name": "1Invalid Name"
        })
        self._assert_success(resp)
        text = self._get_result_text(resp)
        assert "Invalid object name" in text or "error" in text.lower(), \
            f"Expected validation error, got: {text}"

    def test_create_metadata_object_with_language(self):
        """Passing an explicit 'language' must store the synonym under that exact
        language CODE key (covers the resolveLanguage explicit-parameter branch,
        instead of the configuration default / fallback)."""
        name = "E2EChkLangCatalog"
        fqn = "Catalog." + name
        synonym = "E2EChk Lang Synonym"

        resp = self._call("create_metadata_object", {
            "projectName": self.config.project,
            "metadataType": "Catalog",
            "name": name,
            "synonym": synonym,
            "language": "en",
        })
        self._assert_success(resp, "[create with language]")
        text = self._get_result_text(resp)
        assert (name in text or "already exists" in text), \
            f"Unexpected create result: {text}"

        # The synonym map must contain a row "| en | <synonym> |".
        details = self._call("get_metadata_details", {
            "projectName": self.config.project,
            "objectFqns": [fqn],
            "full": True,
        })
        self._assert_success(details, "[read-full lang]")
        dtext = self._get_result_text(details)
        found = False
        for line in dtext.splitlines():
            cells = [c.strip() for c in line.split("|")]
            if len(cells) >= 4 and cells[2] == synonym and cells[1] == "en":
                found = True
                break
        assert found, \
            f"{fqn}: synonym '{synonym}' not stored under explicit 'en' key:\n{dtext}"

    def test_create_metadata_object_cyrillic_synonym(self):
        """Create a catalog with a Cyrillic synonym value and verify round-trip.
        Language code still follows configuration default (en in TestConfiguration);
        the test ensures non-Latin synonym text is stored and read back correctly."""
        name = "E2EChkCyrillicCatalog"
        fqn = "Catalog." + name
        synonym = "Проверка синонима E2E"
        comment = "Комментарий E2E на кириллице"

        resp = self._call("create_metadata_object", {
            "projectName": self.config.project,
            "metadataType": "Catalog",
            "name": name,
            "synonym": synonym,
            "comment": comment,
        })
        self._assert_success(resp, "[create cyrillic synonym]")
        text = self._get_result_text(resp)
        assert (name in text or "already exists" in text), \
            f"Unexpected create result: {text}"

        details = self._call("get_metadata_details", {
            "projectName": self.config.project,
            "objectFqns": [fqn],
        })
        self._assert_success(details, "[read cyrillic synonym]")
        dtext = self._get_result_text(details)
        assert synonym in dtext, \
            f"{fqn}: Cyrillic synonym not returned:\n{dtext}"
        assert comment in dtext, \
            f"{fqn}: Cyrillic comment not returned:\n{dtext}"

        details_full = self._call("get_metadata_details", {
            "projectName": self.config.project,
            "objectFqns": [fqn],
            "full": True,
        })
        self._assert_success(details_full, "[read-full cyrillic synonym]")
        ftext = self._get_result_text(details_full)
        found = any(
            len([c.strip() for c in line.split("|")]) >= 4
            and [c.strip() for c in line.split("|")][2] == synonym
            for line in ftext.splitlines()
        )
        assert found, \
            f"{fqn}: Cyrillic synonym not in full synonym map:\n{ftext}"
        self._assert_synonym_language_code(fqn, synonym)

    def test_add_metadata_attribute(self):
        """Add an attribute to a catalog, then read it back to confirm it landed.
        Tolerant to re-runs: a second invocation returns an 'already exists'
        tool-level message, which is still a valid (non-error) response."""
        parent_name = "E2EChkAttrCatalog"
        parent_fqn = "Catalog." + parent_name
        attr = "E2EChkWeight"

        # Ensure the parent object exists (ignore result; may already exist).
        self._call("create_metadata_object", {
            "projectName": self.config.project,
            "metadataType": "Catalog",
            "name": parent_name,
        })

        resp = self._call("add_metadata_attribute", {
            "projectName": self.config.project,
            "parentFqn": parent_fqn,
            "attributeName": attr,
        })
        self._assert_success(resp, "[add_metadata_attribute]")
        text = self._get_result_text(resp)
        assert ("added successfully" in text or "already exists" in text), \
            f"Unexpected add result: {text}"

        # Read back: the attribute must appear in the full details.
        details = self._call("get_metadata_details", {
            "projectName": self.config.project,
            "objectFqns": [parent_fqn],
            "full": True,
        })
        self._assert_success(details, "[read attribute]")
        dtext = self._get_result_text(details)
        assert attr in dtext, \
            f"{parent_fqn}: attribute '{attr}' not found in details:\n{dtext}"

    def test_delete_metadata_object(self):
        """Preview then execute a delete refactoring, and verify the object is
        gone. Tolerant to re-runs: the target is (re)created up front."""
        target_name = "E2EChkDeleteTarget"
        target_fqn = "DataProcessor." + target_name

        # (Re)create the throwaway target (ignore result; may already exist).
        self._call("create_metadata_object", {
            "projectName": self.config.project,
            "metadataType": "DataProcessor",
            "name": target_name,
        })

        # 1. Preview (confirm omitted -> default false): must succeed, not delete.
        preview = self._call("delete_metadata_object", {
            "projectName": self.config.project,
            "objectFqn": target_fqn,
        })
        self._assert_success(preview, "[delete preview]")
        ptext = self._get_result_text(preview)
        assert "preview" in ptext.lower(), \
            f"Expected a preview action, got:\n{ptext}"

        # 2. Execute (confirm=true).
        resp = self._call("delete_metadata_object", {
            "projectName": self.config.project,
            "objectFqn": target_fqn,
            "confirm": True,
        })
        self._assert_success(resp, "[delete execute]")
        text = self._get_result_text(resp)
        assert ("executed" in text.lower() or "completed" in text.lower()), \
            f"Unexpected delete result: {text}"

        # 3. Verify the object is gone.
        details = self._call("get_metadata_details", {
            "projectName": self.config.project,
            "objectFqns": [target_fqn],
        })
        self._assert_success(details, "[verify deleted]")
        dtext = self._get_result_text(details)
        assert "not found" in dtext.lower(), \
            f"{target_fqn}: object still present after delete:\n{dtext}"

    # ──────────────────────────────────────────────────────────────────────
    # BSL Code Tools
    # ──────────────────────────────────────────────────────────────────────

    def test_list_modules(self):
        resp = self._call("list_modules", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_module_structure(self):
        resp = self._call("get_module_structure", {
            "projectName": self.config.project,
            "modulePath": "CommonModules/OK/Module.bsl"
        })
        self._assert_success(resp)

    def test_read_module_source(self):
        resp = self._call("read_module_source", {
            "projectName": self.config.project,
            "modulePath": "CommonModules/OK/Module.bsl"
        })
        self._assert_success(resp)

    def test_read_method_source(self):
        resp = self._call("read_method_source", {
            "projectName": self.config.project,
            "modulePath": "CommonModules/Error/Module.bsl",
            "methodName": "Error"
        })
        # May not find a method - that's OK, just check no server error
        self._assert_success(resp)

    def test_search_in_code(self):
        resp = self._call("search_in_code", {
            "projectName": self.config.project,
            "query": "Error"
        })
        self._assert_success(resp)

    def test_search_in_code_count(self):
        resp = self._call("search_in_code", {
            "projectName": self.config.project,
            "query": "Error",
            "outputMode": "count"
        })
        self._assert_success(resp)

    # ──────────────────────────────────────────────────────────────────────
    # Advanced Tools
    # ──────────────────────────────────────────────────────────────────────

    def test_find_references(self):
        resp = self._call("find_references", {
            "projectName": self.config.project,
            "objectFqn": "CommonModule.OK"
        })
        self._assert_success(resp)

    def test_get_applications(self):
        resp = self._call("get_applications", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_form_screenshot(self):
        resp = self._call("get_form_screenshot", {
            "projectName": self.config.project,
            "formPath": "CommonForm.Form"
        }, timeout=180)
        self._assert_success(resp)


# ──────────────────────────────────────────────────────────────────────────────
# MCP error codes (mirrored from Java)
# ──────────────────────────────────────────────────────────────────────────────

class McpConstants:
    ERROR_PARSE = -32700
    ERROR_INVALID_REQUEST = -32600
    ERROR_METHOD_NOT_FOUND = -32601
    ERROR_INVALID_PARAMS = -32602
    ERROR_INTERNAL = -32603


# Fix typo compatibility
AssertionError = AssertionError if 'AssertionError' in dir() else AssertionError


# ──────────────────────────────────────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────────────────────────────────────

def wait_for_server(url: str, timeout_sec: int = 300) -> bool:
    """Wait for the MCP server to become available."""
    print(f"Waiting for MCP server at {url} ...")
    start = time.time()
    while time.time() - start < timeout_sec:
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    print(f"  Server available after {time.time()-start:.0f}s")
                    return True
        except Exception:
            pass
        time.sleep(2)
    print(f"  Timeout after {timeout_sec}s")
    return False


def main():
    parser = argparse.ArgumentParser(description="EDT MCP Server E2E Tests")
    parser.add_argument("--host", default=os.environ.get("MCP_HOST", "localhost"),
                        help="MCP server host")
    parser.add_argument("--port", type=int,
                        default=int(os.environ.get("MCP_PORT", "8765")),
                        help="MCP server port")
    parser.add_argument("--project", default=os.environ.get("MCP_PROJECT", "TestConfiguration"),
                        help="EDT project name for testing")
    parser.add_argument("--wait", type=int, default=0,
                        help="Seconds to wait for server to become available (0=no wait)")
    parser.add_argument("--junit-xml", default=None,
                        help="Write JUnit XML report to file")
    args = parser.parse_args()

    config = Config(host=args.host, port=args.port, project=args.project)

    if args.wait > 0:
        if not wait_for_server(config.health_url, args.wait):
            print("FATAL: Server did not become available in time")
            sys.exit(2)

    runner = TestRunner(config)
    success = runner.run_all()

    # Optional JUnit XML output for CI
    if args.junit_xml:
        write_junit_xml(runner.results, args.junit_xml)

    sys.exit(0 if success else 1)


def write_junit_xml(results: list[TestResult], path: str):
    """Write JUnit-compatible XML report."""
    from xml.etree.ElementTree import Element, SubElement, ElementTree

    suite = Element("testsuite", {
        "name": "EDT-MCP-E2E",
        "tests": str(len(results)),
        "failures": str(sum(1 for r in results if not r.passed)),
        "time": f"{sum(r.duration_ms for r in results)/1000:.3f}"
    })

    for r in results:
        tc = SubElement(suite, "testcase", {
            "name": r.name,
            "classname": "e2e",
            "time": f"{r.duration_ms/1000:.3f}"
        })
        if not r.passed:
            fail = SubElement(tc, "failure", {"message": r.message})
            fail.text = r.message

    tree = ElementTree(suite)
    tree.write(path, encoding="unicode", xml_declaration=True)
    print(f"JUnit XML report written to {path}")


if __name__ == "__main__":
    main()
