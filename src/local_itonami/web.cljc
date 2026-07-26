(ns local-itonami.web
  "SSR the cockpit into the web bundle `kotoba-lang/shell` actually loads.

  ## Why this exists even though the manifest says :surface :kotoba/dom

  `app.kotoba.edn` declares the kotoba:dom operation vocabulary, and
  `local-itonami.shell-app/render` really does produce it. But what
  `kotoba-shell app scaffold` generates today is a WKWebView host that serves
  `Resources/WebBundle/` over the `kotoba-webbundle://` scheme — shell's own
  README is explicit that WKWebView / android.webkit.WebView is the render
  substrate now, not dom-gpu/browser (ADR-2607081015). Scaffolding without a
  `:web/dist-dir` writes a placeholder page, which is exactly what the first
  scaffold of this repo produced (`:web-assets {:source nil :placeholder? true}`).

  So the same `local-itonami.view/screen` hiccup goes two ways:

    view/screen ─┬─ local-itonami.dom/tree->ops  → kotoba:dom (native surface)
                 └─ this ns                      → HTML (WKWebView bundle, today)

  That is the point of keeping views as pure hiccup rather than writing
  against either surface directly — one view, and the substrate is a
  packaging decision rather than a rewrite.

  ## CSS

  `jp-go-dds` ships the vendored DADS stylesheet as a resource and keeps its
  own page fn pure by making the caller read it. `jp-go-dds.tokens/skin-css`
  supplies the `--hig-*` bridge plus the design-quality corrections. App CSS
  comes last so it wins."
  (:require [clojure.java.io :as io]
            [jp-go-dds.page :as dds-page]
            [jp-go-dds.tokens :as tokens]
            [local-itonami.shell-app :as shell-app]
            [local-itonami.view :as view]))

(def app-css
  "Only layout/structure. No colour and no font-size literals — those come
  from the `--hig-*` tokens the skin bridges (kotoba-uiux rule 2)."
  (str
   ".itonami-app{min-height:100dvh;background:var(--hig-color-system-background)}"
   ".itonami-header{border-bottom:1px solid var(--hig-color-separator);"
   "padding-block:0.75rem;"
   "padding-top:calc(0.75rem + env(safe-area-inset-top,0px));"
   "background:var(--hig-color-system-background)}"
   ".itonami-header .dds-ext-container{display:flex;align-items:center;"
   "justify-content:space-between;gap:1rem}"
   ".itonami-brand{font-weight:700;color:var(--hig-color-label)}"
   ".itonami-scope{color:var(--hig-color-secondary-label);"
   "font-family:var(--hig-font-mono)}"
   ".itonami-metrics{display:grid;gap:0.75rem;"
   "grid-template-columns:repeat(auto-fit,minmax(9rem,1fr))}"
   ".itonami-metric{display:flex;flex-direction:column;gap:0.25rem}"
   ".itonami-metric__label{color:var(--hig-color-secondary-label)}"
   ".itonami-metric__value{font-size:1.75rem;line-height:1.1;"
   "color:var(--hig-color-label)}"
   ".itonami-metric__detail{color:var(--hig-color-tertiary-label)}"
   ".itonami-effects{display:flex;flex-direction:column;gap:0.5rem}"
   ".itonami-effect{border:1px solid var(--hig-color-separator);"
   "border-radius:0.5rem;padding:0.75rem;display:flex;"
   "flex-direction:column;gap:0.5rem}"
   ".itonami-effect--attention{border-left:4px solid var(--hig-color-tint)}"
   ".itonami-effect__head{display:flex;align-items:center;"
   "justify-content:space-between;gap:0.5rem}"
   ".itonami-effect__kind{font-weight:700;color:var(--hig-color-label)}"
   ".itonami-effect__meta{display:flex;gap:0.75rem;"
   "color:var(--hig-color-secondary-label)}"
   ".itonami-effect__id{font-family:var(--hig-font-mono)}"
   ".itonami-empty{color:var(--hig-color-secondary-label)}"))

(defn dds-css
  "Read the vendored DADS stylesheet off the classpath. jp-go-dds keeps its
  page fn pure by never doing this itself, so the consumer does it."
  []
  (slurp (io/resource "jp_go_dds/dds.css")))

(defn ->html
  "Render one cockpit state to a complete HTML document."
  [state]
  (dds-page/->page
   {:title "itonami"
    :description "itonami.cloud の承認キュー"
    :css (dds-css)
    :app-css (str tokens/skin-css app-css)}
   (view/screen state)))

(defn -main
  "Write the web bundle `:web/dist-dir` points at.

    clojure -M:web [out-dir]   (default: dist)

  Renders `shell-app/initial-state`. There is no data fetch here — wiring the
  API client is the named gap in ADR-2607262000, so what ships today is the
  cockpit chrome plus its 読み込み中 / empty states, not live numbers."
  [& [out-dir]]
  (let [dir (io/file (or out-dir "dist"))
        html (->html shell-app/initial-state)
        out (io/file dir "index.html")]
    (.mkdirs dir)
    (spit out html)
    (println (str "wrote " (.getPath out) " (" (count html) " bytes)"))))
