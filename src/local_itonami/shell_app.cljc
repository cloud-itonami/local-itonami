(ns local-itonami.shell-app
  "kotoba-lang/shell native entry for local-itonami.

  Pure by construction: application state enters as data and `kotoba:dom`
  operations leave as data. `kotoba-lang/shell` owns the native host, the
  window, and every provider effect (network, Keychain, filesystem). No
  React, no browser DOM, no Tauri API is referenced here — the same contract
  `manimani.shell-app` establishes.

  ## What this app is

  A native client for `itonami.cloud`'s approval surface. The cockpit's job
  is the queue: which effects are waiting on a human, what risk each one
  carries, and what it would do. It is deliberately read-and-decide, not a
  second place to author business logic — the governors that decide live
  server-side in `gftdcojp/cloud-itonami` and its vertical actors.

  ## Auth

  Sessions are CACAO/`did:key`, held in the macOS Keychain by the shell host
  (`:macos/auth-bridge :keychain-cacao` in `app.kotoba.edn`). This namespace
  never sees a key: it receives whatever the host already resolved as
  `:session` in the state map. That is the safety floor from the root
  CLAUDE.md — credential material is read by a credential-specific tool, not
  typed into a form by the app."
  (:require [local-itonami.dom :as dom]
            [local-itonami.view :as view]))

(def initial-state
  "State before the host has resolved anything. `:status :booting` is what
  `view/screen` renders as 読み込み中 rather than as an empty queue — an
  operator must be able to tell 'not loaded yet' from 'nothing waiting'."
  {:status :booting
   :scope {:org "gftdcojp" :repo "gftdcojp"}
   :metrics []
   :effects nil
   :session nil})

(defn metrics-of
  "Project the API's tenant metrics into the cockpit's headline numbers.

  Reads the shape `/api/{org}/{repo}/metrics` actually returns (verified
  2026-07-26 against itonami.cloud v0.1.953): `:tenants` with
  `:externalTotal`/`:externalPaid`/`:selfRegisteredOwners`, and
  `:thisTenant` with `:claimed`. Unknown/missing values render as \"—\",
  never as 0 — a zero an operator cannot distinguish from 'not reported' is
  worse than an obvious blank."
  [{:keys [tenants thisTenant]}]
  (let [n (fn [v] (if (number? v) (str v) "—"))]
    [{:label "外部テナント" :value (n (:externalTotal tenants)) :detail "self-registered 含む"}
     {:label "有料" :value (n (:externalPaid tenants)) :detail "Stripe 課金中"}
     {:label "このテナント"
      :value (case (:claimed thisTenant) true "claimed" false "未claim" "—")
      :detail "gftdcojp/gftdcojp"}]))

(defn apply-metrics
  [state payload]
  (assoc state :status :ready :metrics (metrics-of payload)))

(defn apply-effects
  [state effects]
  (assoc state :status :ready :effects (vec effects)))

(defn apply-error
  "A failed fetch must not silently keep stale numbers on screen looking
  current. Metrics are cleared; the queue is left as-is only when it has
  never loaded (nil), so the error notice is the whole story."
  [state _error]
  (assoc state :status :error :metrics []))

(defn render
  "state → kotoba:dom operations. The one function the native host calls."
  [state]
  (dom/tree->ops (view/screen state)))

(defn start
  "kotoba-lang/shell entry point (`:runtime :start` in `app.kotoba.edn`).

  Returns the first surface commit. The host drives subsequent commits by
  calling `render` with updated state; this function does not loop, sleep, or
  open a socket of its own."
  ([] (start {}))
  ([opts]
   (render (merge initial-state opts))))
