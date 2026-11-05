#!/usr/bin/env python3
"""
Generates release/DingJson.java — the single-file edition of DingJson.

Usage:  python3 tools/generate_single_file.py
Output: release/DingJson.java

All types become nested classes of DingJson (Java allows one public top-level
type per file). Fully-qualified references (com.dingjson.parser.X etc.) are
rewritten to simple names.
"""
import re
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src/main/java/com/dingjson")

# Nested classes in definition order (dependency order for readability)
NESTED = [
    "exception/JSONException.java", "element/JsonElement.java",
    "element/JSONObject.java", "element/JSONArray.java",
    "annotation/JSONField.java", "TypeReference.java",
    "DingJsonConfig.java", "JSONPath.java",
    "parser/JSONTokener.java", "parser/JSONParser.java",
    "parser/JSONReader.java", "serializer/UnsafeFields.java",
    "serializer/JSONWriter.java", "serializer/JSONSerializer.java",
    "DingJsonMapper.java",
]


def strip_header(lines):
    return [ln for ln in lines
            if not (ln.strip().startswith("package ") or ln.strip().startswith("import "))]


def transform_class_decl(lines, indent="    "):
    out = []
    for ln in lines:
        s = ln.strip()
        m = re.match(r'^(public\s+)?(final\s+|abstract\s+)?(@interface|interface|class)\s+(\w+)', s)
        if m:
            vis, mods, kind, name = m.group(1) or "", m.group(2) or "", m.group(3), m.group(4)
            rest = s[m.end():]
            static = "" if kind == "@interface" else "static "
            out.append(indent + f"{vis}{static}{mods}{kind} {name}{rest}\n")
        else:
            out.append(ln)
    return out


def read_java(path):
    with open(os.path.join(SRC, path), "r", encoding="utf-8") as f:
        return f.readlines()


def extract_members(lines, class_name):
    """Strip package/import, the class declaration, and the final closing brace."""
    body = []
    for ln in lines:
        s = ln.strip()
        if s.startswith("package ") or s.startswith("import "):
            continue
        if re.match(r'^(public\s+)?(final\s+|abstract\s+)?(class)\s+' + class_name + r'\b', s):
            continue
        body.append(ln)
    for i in range(len(body) - 1, -1, -1):
        if body[i].strip() == "}":
            body = body[:i] + body[i + 1:]
            break
    return body


def fix_qualified_names(text):
    text = re.sub(r'com\.dingjson\.(?:annotation|exception|element|parser|serializer)\.(\w+)', r'\1', text)
    text = re.sub(r'com\.dingjson\.(DingJsonConfig|DingJsonMapper|TypeReference|JSONPath)', r'\1', text)
    return text


def main():
    out = []
    out.append("package com.dingjson;\n\n")
    out.append("import sun.misc.Unsafe;\n")
    out.append("import java.io.*;\n")
    out.append("import java.math.BigDecimal;\nimport java.math.BigInteger;\n")
    out.append("import java.nio.*;\nimport java.nio.charset.*;\nimport java.nio.file.*;\n")
    out.append("import java.time.Instant;\nimport java.time.LocalDate;\nimport java.time.LocalDateTime;\n")
    out.append("import java.util.*;\nimport java.util.concurrent.ConcurrentHashMap;\n")
    out.append("import java.lang.reflect.*;\nimport java.lang.annotation.*;\n\n")
    out.append("/**\n * DingJson — single-file edition.\n * <p>\n * All types are nested inside this class; usage differs slightly from the\n * multi-file artifact:\n * <pre>{@code\n * DingJson.JSONObject obj = DingJson.parseObject(\"...\");\n * User u = DingJson.parseObject(json, User.class);\n * DingJson.JSONArray arr = DingJson.parseArray(\"[1,2]\");\n * List<User> users = DingJson.parseObject(json, new DingJson.TypeReference<List<User>>() {});\n * // annotations: @DingJson.JSONField(name = \"user_name\")\n * // exceptions:   catch (DingJson.JSONException e)\n * // config:       DingJson.mapper(DingJson.DingJsonConfig.builder()...build())\n * // streaming:    DingJson.JSONReader r = DingJson.createReader(json);\n * //               DingJson.JSONWriter w = DingJson.JSONWriter.create();\n * //               DingJson.JSONPath.eval(tree, \"$.a[0]\")\n * }</pre>\n * <p>\n * Generated from the multi-file source; keep them in sync.\n */\n")
    out.append("public final class DingJson {\n\n")

    for rel in NESTED:
        lines = read_java(rel)
        lines = strip_header(lines)
        lines = transform_class_decl(lines)
        out.append("    // ── " + os.path.basename(rel) + " ──────────────────────────────\n")
        out.extend(lines)
        out.append("\n")

    out.extend(extract_members(read_java("DingJson.java"), "DingJson"))
    out.append("}\n")

    text = fix_qualified_names("".join(out))
    target = os.path.join(ROOT, "release/DingJson.java")
    with open(target, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"Generated {target} ({len(text.splitlines())} lines)")


if __name__ == "__main__":
    main()
