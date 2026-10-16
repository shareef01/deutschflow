import { describe, expect, it } from "vitest";
import * as fs from "node:fs";
import * as path from "node:path";

function getAllSourceFiles(dir: string): string[] {
  const files: string[] = [];
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...getAllSourceFiles(fullPath));
    } else if (entry.isFile() && (entry.name.endsWith(".ts") || entry.name.endsWith(".tsx"))) {
      files.push(fullPath);
    }
  }
  return files;
}

describe("Security Sinks & Attack Surface Audit (Priority 6)", () => {
  const srcDir = path.resolve(__dirname, "../src");
  const sourceFiles = getAllSourceFiles(srcDir);

  it("finds source files to audit", () => {
    expect(sourceFiles.length).toBeGreaterThan(10);
  });

  it("contains ZERO usages of dangerouslySetInnerHTML", () => {
    const violations: { file: string; line: number }[] = [];
    for (const file of sourceFiles) {
      const content = fs.readFileSync(file, "utf8");
      const lines = content.split("\n");
      lines.forEach((line, index) => {
        if (line.includes("dangerouslySetInnerHTML")) {
          violations.push({ file: path.relative(srcDir, file), line: index + 1 });
        }
      });
    }
    expect(violations).toEqual([]);
  });

  it("contains ZERO usages of .innerHTML assignments", () => {
    const violations: { file: string; line: number }[] = [];
    for (const file of sourceFiles) {
      const content = fs.readFileSync(file, "utf8");
      const lines = content.split("\n");
      lines.forEach((line, index) => {
        if (/\.innerHTML\s*=/.test(line)) {
          violations.push({ file: path.relative(srcDir, file), line: index + 1 });
        }
      });
    }
    expect(violations).toEqual([]);
  });

  it("contains ZERO usages of eval()", () => {
    const violations: { file: string; line: number }[] = [];
    for (const file of sourceFiles) {
      const content = fs.readFileSync(file, "utf8");
      const lines = content.split("\n");
      lines.forEach((line, index) => {
        // Exclude comments
        const clean = line.replace(/\/\/.*$/, "").replace(/\/\*.*?\*\//, "");
        if (/\beval\s*\(/.test(clean)) {
          violations.push({ file: path.relative(srcDir, file), line: index + 1 });
        }
      });
    }
    expect(violations).toEqual([]);
  });

  it("contains ZERO usages of new Function()", () => {
    const violations: { file: string; line: number }[] = [];
    for (const file of sourceFiles) {
      const content = fs.readFileSync(file, "utf8");
      const lines = content.split("\n");
      lines.forEach((line, index) => {
        const clean = line.replace(/\/\/.*$/, "").replace(/\/\*.*?\*\//, "");
        if (/new\s+Function\s*\(/.test(clean)) {
          violations.push({ file: path.relative(srcDir, file), line: index + 1 });
        }
      });
    }
    expect(violations).toEqual([]);
  });

  it("contains ZERO usages of document.write()", () => {
    const violations: { file: string; line: number }[] = [];
    for (const file of sourceFiles) {
      const content = fs.readFileSync(file, "utf8");
      const lines = content.split("\n");
      lines.forEach((line, index) => {
        if (/document\.write\s*\(/.test(line)) {
          violations.push({ file: path.relative(srcDir, file), line: index + 1 });
        }
      });
    }
    expect(violations).toEqual([]);
  });
});
