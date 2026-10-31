import { describe, expect, it } from "vitest";

import { colors, contrastPairs, type ColorScheme } from "./tokens.ts";

// WCAG 2 のコントラスト比
function luminance(hex: string): number {
  const [r = 0, g = 0, b = 0] = [1, 3, 5]
    .map((start) => Number.parseInt(hex.slice(start, start + 2), 16) / 255)
    .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

describe.each<ColorScheme>(["light", "dark"])("%s の色", (scheme) => {
  it.each(contrastPairs)("$fg は $bg の上で読める", ({ fg, bg, min }) => {
    expect(contrast(colors[scheme][fg], colors[scheme][bg])).toBeGreaterThanOrEqual(min);
  });
});

it("明暗で同じ色の名前を持つ", () => {
  expect(Object.keys(colors.dark).sort()).toEqual(Object.keys(colors.light).sort());
});
