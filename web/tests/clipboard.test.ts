import { describe, expect, it, vi } from "vitest";
import { copyToClipboard } from "@/hooks/useClipboard";

describe("copyToClipboard", () => {
  it("returns success with default variant when writeText succeeds", async () => {
    const writeTextMock = vi.fn().mockResolvedValue(undefined);
    const mockClipboard = { writeText: writeTextMock };

    const result = await copyToClipboard("Guten Tag", mockClipboard);

    expect(result).toEqual({
      success: true,
      messageKey: "action.copied",
      variant: "default",
    });
    expect(writeTextMock).toHaveBeenCalledWith("Guten Tag");
  });

  it("returns failure with error variant when writeText rejects", async () => {
    const writeTextMock = vi.fn().mockRejectedValue(new Error("Permission denied"));
    const mockClipboard = { writeText: writeTextMock };

    const result = await copyToClipboard("Failed text", mockClipboard);

    expect(result).toEqual({
      success: false,
      messageKey: "action.copyFailed",
      variant: "error",
    });
  });

  it("returns failure with error variant when clipboard API is missing", async () => {
    const result = await copyToClipboard("No API", undefined);

    expect(result).toEqual({
      success: false,
      messageKey: "action.copyFailed",
      variant: "error",
    });
  });
});
