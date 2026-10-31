"""評価に使うプロンプトと JSON スキーマ。全モデルで同じものを使う。

test の結果を見てからは変えない。調整は split: dev の会話だけで行う。
"""
import datetime
import hashlib

WEEKDAY = "月火水木金土日"

SYSTEM = (
    "あなたは個人用メモアプリの整理係です。"
    "入力は音声の自動文字起こしで、誤変換、言いよどみ、話者ラベルの誤りが含まれます。"
    "「自分」は端末の持ち主です。"
    "書かれていないことを補ったり推測したりしないでください。"
)


def today(item) -> str:
    d = item["date"]
    return f"{d.isoformat()}({WEEKDAY[d.weekday()]}曜日)"


def calendar(item) -> str:
    """日付の計算をモデルに任せず、対応表を渡す。小型モデルは曜日の暗算を誤る。"""
    start = item["date"]
    rows = []
    for i in range(0, 22):
        d = start + datetime.timedelta(days=i)
        label = {0: "今日", 1: "明日", 2: "あさって"}.get(i, "")
        rows.append(f"{d.isoformat()} {WEEKDAY[d.weekday()]}" + (f" {label}" if label else ""))
    return "\n".join(rows)


def summary(item) -> str:
    return f"""今日は {today(item)} です。次の文字起こしを、あとで読み返すためのメモに要約してください。

- 日本語の箇条書きで3〜6行。各行は「- 」で始める
- 決まったこと、日時、金額、人や場所の名前を残す
- 誤変換は正しい言葉に直して書く
- 箇条書きだけを出力する

文字起こし:
{item['transcript'].strip()}"""


TODO_SCHEMA = {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "task": {"type": "string"},
                    "kind": {"type": "string", "enum": ["todo", "reminder"]},
                    "due": {"type": ["string", "null"]},
                },
                "required": ["task", "kind", "due"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["items"],
    "additionalProperties": False,
}


def todo(item) -> str:
    tomorrow = (item["date"] + datetime.timedelta(days=1)).isoformat()
    return f"""今日は {today(item)} です。次の文字起こしから、「自分」に関するものだけを抽出してください。

抽出するもの:
- todo: 自分がこれからやること
- reminder: 自分が日時を覚えておくべき予定

抽出しないもの:
- 自分以外の人がやること
- もう終わったこと
- 取り消したこと、やめたこと
- 願望、仮定、冗談、雑談

出力の形式:
- JSON だけを出力する。説明やコードブロックは書かない
- kind は todo か reminder
- due は YYYY-MM-DD 形式の期限や予定の日付。日付が分からなければ null
- 該当するものがなければ {{"items": []}}

例: 「[09:00] 話者1: 請求書、明日までに送ってくれる? [09:00] 自分: はい、送ります。」の出力は
{{"items": [{{"task": "請求書を送る", "kind": "todo", "due": "{tomorrow}"}}]}}

カレンダー(日付は必ずここから選ぶ。週は月曜始まり。範囲外の日付は月日から直接書く):
{calendar(item)}

文字起こし:
{item['transcript'].strip()}"""


SPEAKER_SCHEMA = {
    "type": "object",
    "properties": {
        "speakers": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "label": {"type": "string"},
                    "evidence": {"type": "string"},
                    "name": {"type": ["string", "null"]},
                },
                "required": ["label", "evidence", "name"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["speakers"],
    "additionalProperties": False,
}


def speakers(item) -> str:
    labels = "、".join(item["speakers"])
    return f"""次の文字起こしの話者ラベル({labels})それぞれの名前を推定してください。

根拠にしてよいもの:
- 本人の名乗り(「〜です」「〜と申します」)。名乗った話者の名前になる
- 呼びかけ(「〜さん、どう?」)。呼びかけられた名前は、話した本人ではなく、次に答えた話者の名前になる

注意:
- 話の中に出てくるだけの、その場にいない人の名前を当てない
- 「自分」への呼びかけの名前を、他の話者に当てない
- 根拠がなければ name は null にする。無理に当てない
- 指定した話者ラベルを1回ずつ出力する。「自分」は出力しない

出力の形式:
- JSON だけを出力する。説明やコードブロックは書かない
- {{"speakers": [{{"label": "話者ラベル", "evidence": "根拠の発言。なければ空", "name": null}}]}}
- name は名字または呼び名だけにして、「さん」を付けない

文字起こし:
{item['transcript'].strip()}"""


def _distractors(item, items) -> list:
    """検索の上位に別の会話も混ざる状況を再現する。同じ入力なら常に同じものを選ぶ。"""
    others = sorted(
        (x for x in items if x["id"] != item["id"] and x["split"] == item["split"]),
        key=lambda x: hashlib.sha256((item["id"] + x["id"]).encode()).hexdigest(),
    )
    return others[:2]


def qa(item, question, items) -> str:
    docs = _distractors(item, items)
    pos = int(hashlib.sha256((item["id"] + question).encode()).hexdigest(), 16) % (len(docs) + 1)
    docs.insert(pos, item)
    ctx = "\n\n".join(f"--- メモ {i + 1}({today(d)})---\n{d['transcript'].strip()}" for i, d in enumerate(docs))
    return f"""次のメモだけを根拠に、質問に1〜2文の日本語で答えてください。
メモに書かれていなければ、「不明」とだけ答えてください。

{ctx}

質問: {question}"""
