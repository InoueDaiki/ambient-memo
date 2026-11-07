import { describe, expect, it } from "@jest/globals";

import { speakerAvatar } from "./format.ts";

describe("話者の丸い印", () => {
  it("仮の話者は番号だけを出す", () => {
    expect(speakerAvatar({ speaker: "other", speakerName: "話者1" })).toBe("1");
    expect(speakerAvatar({ speaker: "other", speakerName: "話者12" })).toBe("12");
  });

  it("名前を付けた話者は頭文字を出す", () => {
    expect(speakerAvatar({ speaker: "other", speakerName: "田中さん" })).toBe("田");
    expect(speakerAvatar({ speaker: "other", speakerName: " alice" })).toBe("a");
    expect(speakerAvatar({ speaker: "self", speakerName: "自分" })).toBe("自");
  });

  it("サロゲートペアの文字を割らない", () => {
    expect(speakerAvatar({ speaker: "other", speakerName: "𠮷野" })).toBe("𠮷");
  });

  it("話者が分からない発話は ? を出す", () => {
    expect(speakerAvatar({ speaker: "unknown" })).toBe("?");
  });
});
