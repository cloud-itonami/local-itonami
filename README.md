# local-itonami

`itonami.cloud` の**承認面のネイティブクライアント**。
[`kotoba-lang/shell`](https://github.com/kotoba-lang/shell) が native host /
window / provider effect（ネットワーク・Keychain・ファイル）を所有し、この repo は
その host が呼ぶ**純粋なエントリ**だけを供給する。React バンドルも system WebView
コンテンツも native runtime に含めない（`gftdcojp/local-manimani` と同じ契約）。

設計記録: ADR-2607262000（`com-junkawasaki/root` の `90-docs/adr/`）。

```sh
kotoba-shell app scaffold      # app.kotoba.edn から Xcode/Gradle プロジェクトを生成
kotoba-shell app build         # ネイティブビルド
kbb -M:test                # 18 tests / 62 assertions
```

## このアプリの役割

**承認キュー**。どの effect が人間待ちで、どの risk を持ち、何をするのか。
決めるのはサーバ側の governor（`gftdcojp/cloud-itonami` と各 vertical actor）で、
ここは read-and-decide に徹する — 業務ロジックの二つ目の置き場にはしない。

CRM/marketing の effect kind（`crm/*` · `marketing/*`、ADR-2607261200）も
workspace kind と並んで表示される。

## アーキテクチャ

```
local_itonami/
  shell_app.cljc   kotoba-lang/shell エントリ。state(data) → kotoba:dom ops(data)
  view.cljc        hiccup ビュー。skin-neutral（dads-* も liquid-glass__* も書かない）
  skin.cljc        共通化の接ぎ目。1つの語彙 → 2つのデザインシステム
  dom.cljc         hiccup → kotoba:dom コンパイラ
```

### skin — UI/UX 共通化の接ぎ目

このワークスペースにはデザインシステムが2つある:

- **`kotoba-ui`**（+ `appkit`/`uikit`）— HIG/liquid-glass の paved road。
  ADR-2607122200 が唯一の入口として指定。light + dark。
- **`jp-go-digital-design-system`** — デジタル庁デザインシステム（DADS）。
  ADR-2607141915 が**公共・行政文脈向けの明示的 opt-out 先**として導入。light 固定。

ビューが `dads-*` を直書きすると skin を替えられない。だから `local-itonami.view`
は `local-itonami.skin` の語彙だけを呼び、skin の切替は `*skin*` の差し替えで済む。
これを保証しているのが `view-test/views-name-no-design-system-class-directly`
（skin を替えて DADS クラスが1つでも残っていたら落ちる）。

**色と字は接ぎ目を通らない。** 両 skin とも `--hig-*` token 契約を発行する
（DADS 側は `jp-go-dds.tokens/skin-css` が primitive へ橋渡し）ので、ビューと SVG は
token を直接参照でき、既に skin 非依存。構造だけが接ぎ目を必要とする。

このアプリの既定は **DADS**。itonami は許認可・調達・法人実体・官公庁に隣接する
事業者向け business-OS で、ADR-2607141915 が DADS を導入した文脈そのものだから
（kotoba-uiux 既定からの意図的な opt-out）。

### なぜ cloud-itonami に依存しないのか

`gftdcojp/cloud-itonami` は同じコックピットを既にサーバ側で描画している
（`cloud-itonami.keiei.ui.gftdcojp/screen`）。それでもこの repo はあの classpath
（datomic・langgraph・CRM vertical 2つ・workspace sub-actor 全部）を引かない。
サーバの依存関係一式をデスクトップアプリの背後に置いたら、名ばかりのクライアントに
なる。local-itonami は `itonami.cloud` の普通の HTTP クライアントで、API が返す
JSON の見せ方を自分で持つ。

**その代償は実在する**: 2つのコックピットは drift しうる。それを繋ぎ止めるのは
API 契約（`/api/{org}/{repo}/…`）であって共有レンダリング経路ではない。

## 認証

セッションは CACAO/`did:key`。macOS Keychain に shell host が保持する
（`app.kotoba.edn` の `:macos/auth-bridge :keychain-cacao`）。この repo のコードは
鍵素材を一切見ない — host が解決済みの `:session` を受け取るだけ
（root CLAUDE.md の安全床①: 認証情報は credential 専用経路で扱い、アプリが
フォーム入力しない）。Keychain の service/account 名は cloud-itonami の既存
local surface と揃えてあるので、そちらでサインイン済みなら再認証は起きない。

## 既知のギャップ

- **描画基盤は今日 WKWebView / `android.webkit.WebView`**（`kotoba-lang/shell`
  README、ADR-2607081015）。`:surface :kotoba/dom` は commit する operation 語彙の
  ことで、これは実際に守られている。`:browser-engine :kotoba-lang/browser` は
  長期ターゲットの記述であって現状の主張ではない。
- **`dom.cljc` は hiccup→kotoba:dom コンパイラの2つ目の独立実装**
  （1つ目は `manimani.shell-app/tree->ops`）。`kotoba-lang/shell` が surface 契約を
  所有しているので本来はそちらにあるべき。3つ目を書かせないため、app 固有の知識を
  持たない独立 ns に隔離してある（持ち上げは move であって rewrite ではない）。
- **API クライアントは未実装。** `shell_app.cljc` は state → ops の純粋部分だけを
  持ち、fetch は host provider 側。`metrics-of` は実測した
  `/api/{org}/{repo}/metrics` の shape に合わせてある（itonami.cloud v0.1.953、
  2026-07-26 検証）が、それを呼ぶ配線はまだ無い。
- **`skin.cljc` は app 内にある。** 消費者が1つしかないうちにライブラリへ上げるのは
  speculative generality。昇格トリガは ADR-2607262000 に明記: **skin を切り替えたい
  2つ目のアプリが現れたとき。**
