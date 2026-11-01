// デザイントークンの正。値はここだけに書く。
// 色は役割の名前で持つ(「緑」ではなく「聞き取り中」)。画面ではこの名前だけを使う。
//
// 見た目の考え方: 紙とインクのメモ帳。地はわずかに暖かい紙の色、文字は真っ黒にしないインク。
// 色を持つのは「主な操作」「聞き取り中」「誤りと止める操作」だけにして、文字起こしの本文を邪魔しない。
// 主な操作の緑はアプリのアイコンと同じ系統にする。

export interface ColorPalette {
  /** 画面の地(紙) */
  canvas: string;
  /** 地の上に置く面(知らせの帯) */
  surface: string;
  /** surface や枠線のボタンを押している間 */
  surfacePressed: string;
  /** 区切り線、面の枠 */
  border: string;
  /** 枠線のボタンの枠。部品として見分けられる濃さにする */
  borderStrong: string;
  /** 本文の文字(インク) */
  fg: string;
  /** 補足の文字(時刻、話者、説明) */
  fgMuted: string;
  /** 主な操作の塗り */
  primary: string;
  /** 主な操作を押している間 */
  primaryPressed: string;
  /** primary の上の文字 */
  onPrimary: string;
  /** 聞き取り中の印と文字。ほかの用途には使わない */
  listening: string;
  /** 聞き取り中の帯の塗り */
  listeningSoft: string;
  /** 誤りと止める操作の文字 */
  danger: string;
  /** 誤りの帯と止める操作の塗り */
  dangerSoft: string;
  /** 止める操作を押している間 */
  dangerSoftPressed: string;
}

export const colors = {
  light: {
    canvas: "#F8F6F1",
    surface: "#EFECE4",
    surfacePressed: "#E4E0D6",
    border: "#DDD8CC",
    borderStrong: "#8F897C",
    fg: "#2C2A26",
    fgMuted: "#6A665D",
    primary: "#2F5D43",
    primaryPressed: "#264B36",
    onPrimary: "#F8F6F1",
    listening: "#3F6B4F",
    listeningSoft: "#E4EBE1",
    danger: "#9C3B2E",
    dangerSoft: "#F4E3DD",
    dangerSoftPressed: "#EBD3CB",
  },
  dark: {
    canvas: "#191816",
    surface: "#25231F",
    surfacePressed: "#312E29",
    border: "#37342E",
    borderStrong: "#7D786C",
    fg: "#E9E5DC",
    fgMuted: "#A5A095",
    primary: "#9CCBA9",
    primaryPressed: "#86B694",
    onPrimary: "#16211A",
    listening: "#93BE9F",
    listeningSoft: "#26302A",
    danger: "#E5A293",
    dangerSoft: "#3A2622",
    dangerSoftPressed: "#4A302B",
  },
} as const satisfies Record<"light" | "dark", ColorPalette>;

export type ColorScheme = keyof typeof colors;
export type ColorName = keyof ColorPalette;

// 4 の倍数で刻む
export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
  xxl: 48,
} as const;

export const radius = {
  sm: 6,
  md: 10,
  lg: 16,
  full: 999,
} as const;

export interface TextStyleToken {
  fontSize: number;
  lineHeight: number;
  fontWeight: "400" | "500" | "600";
  letterSpacing: number;
}

// 書体は端末の標準(Pixel では Noto Sans CJK JP)。
// 長い日本語を読むので、本文の行送りは文字の大きさの 1.7 倍にする
export const typography = {
  /** 画面の見出し */
  title: { fontSize: 22, lineHeight: 30, fontWeight: "600", letterSpacing: 0.2 },
  /** 文字起こしの本文。画面の主役 */
  body: { fontSize: 17, lineHeight: 29, fontWeight: "400", letterSpacing: 0.3 },
  /** ボタンと、本文に次ぐ大きさの文 */
  label: { fontSize: 15, lineHeight: 22, fontWeight: "500", letterSpacing: 0.2 },
  /** 時刻、話者、説明 */
  caption: { fontSize: 13, lineHeight: 18, fontWeight: "400", letterSpacing: 0.2 },
} as const satisfies Record<string, TextStyleToken>;

export type TypographyName = keyof typeof typography;

export const hairline = 1;

// 指で押す部品の最小の高さ(Android の推奨は 48dp)
export const minTouchSize = 48;

// 文字と地の組み合わせ。コントラスト比を src/tokens.test.ts で確かめる。
// min は WCAG 2 の AA(本文 4.5、部品の印 3)
export const contrastPairs: { fg: ColorName; bg: ColorName; min: number }[] = [
  { fg: "fg", bg: "canvas", min: 4.5 },
  { fg: "fgMuted", bg: "canvas", min: 4.5 },
  { fg: "fg", bg: "surface", min: 4.5 },
  { fg: "fgMuted", bg: "surface", min: 4.5 },
  { fg: "fg", bg: "surfacePressed", min: 4.5 },
  { fg: "onPrimary", bg: "primary", min: 4.5 },
  { fg: "onPrimary", bg: "primaryPressed", min: 4.5 },
  { fg: "listening", bg: "listeningSoft", min: 4.5 },
  { fg: "listening", bg: "canvas", min: 4.5 },
  { fg: "danger", bg: "dangerSoft", min: 4.5 },
  { fg: "danger", bg: "dangerSoftPressed", min: 4.5 },
  { fg: "borderStrong", bg: "canvas", min: 3 },
];
