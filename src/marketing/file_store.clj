(ns marketing.file-store
  "A genuinely disk-durable `marketing.store/Store` implementation: a full
  EDN snapshot written to a local file on every mutating call, reloaded
  from that file on construction. State survives a process restart —
  verified end-to-end (not just unit-tested in-process) by actually
  starting `marketing.http/-main` as a real OS process against a temp
  file, committing data over real HTTP, killing the process, restarting
  it, and confirming the data is still there (see this repo's PR/commit
  description for the transcript; `test/marketing/file_store_test.clj`
  covers the same contract at the Store level, in-process, for fast
  regression coverage). Mirrors `cloud-itonami-isic-5820`'s
  `crm.file-store` (same write-then-rename EDN snapshot design, same
  load-or-seed behavior), adapted to this actor's own `marketing.store/
  Store` protocol method list.

  JVM-only (`.clj`, not `.cljc`) for the same reason `marketing.http` is:
  file I/O (`clojure.java.io`, `java.io.File`) has no portable equivalent
  at the kotoba-wasm/clojurewasm/cljs/nbb tier this fleet prefers, and
  this namespace is infrastructure glue over the already-portable
  `marketing.store/Store` protocol + `MemStore` implementation, not a
  reimplementation of any governance/domain logic.

  ## Why this exists instead of wiring `marketing.store/datomic-store`

  `marketing.store/DatomicStore` (`marketing.store/datomic-store`) has
  the SAME non-durability issue `cloud-itonami-isic-5820`'s
  `crm.store/DatomicStore` was found to have — this is not assumed by
  analogy, it was checked directly: its constructor is
  `(->DatomicStore (langchain.db/create-conn schema))` (see
  `marketing.store/datomic-store`) — `langchain.db/create-conn` returns
  a plain `(atom {:db ... :log []})`; there is no connection URI, no
  socket, no file, nothing that outlives the JVM heap. It is
  Datomic-API-*shaped* (via `langchain.db`, a pure in-process EAV
  emulation — see that ns's docstring), not Datomic-*backed*.
  `test/marketing/store_contract_test.clj` proves `MemStore` and
  `DatomicStore` are read/write-equivalent, which is true and valuable
  for a future backend swap — but neither one persists past the
  process, so picking `DatomicStore` for `-main` would not have fixed
  the honest-scope gap this file exists to close (this actor's
  `marketing.http/-main` docstring, prior to this fix, told operators
  wanting persistence to instantiate `DatomicStore` themselves — that
  advice was exactly as inaccurate as `cloud-itonami-isic-5820`'s prior
  revision, and is corrected as part of this fix).

  Making `DatomicStore` genuinely durable would need two things this
  sandbox does not have: (1) `marketing.store` refactored so
  `DatomicStore` talks to an injected `:db-api` map (`langchain.db/api`'s
  own shape, per that ns's docstring) instead of hardcoding calls to
  `langchain.db` directly, and (2) a live Datomic Local process or a
  reachable kotoba-server pod (`langchain.kotoba-db/kotoba-api`) to
  point that `:db-api` at — the latter needs a running server +
  credentials neither present nor stand-up-able here. Rather than fake
  either of those (or quietly wire `DatomicStore` under a
  durability-implying env var name when it provides none), this file
  takes the path that IS honestly achievable and verifiable in this
  sandbox: real bytes on real disk.

  ## What this is NOT

  - NOT multi-writer-safe: two `-main` processes must never point at the
    same `path` concurrently — there is no file lock, no CAS, no
    coordination. Single-process, single-writer only (same single-process
    scope `docs/api.md` already documents for this HTTP layer generally).
  - NOT a query engine, NOT transactional history, NOT `as-of`/audit-log
    replay of the snapshot itself (the domain-level audit ledger
    `marketing.store/ledger` is unaffected — it round-trips through the
    same snapshot like every other field).
  - NOT crash-atomic against OS/disk failure beyond a single
    write-then-rename per mutation (see `persist!`) — good enough for a
    single operator's dev/small-deployment durability, not a
    replacement for a real transactional database."
  (:require [clojure.edn :as edn]
            [marketing.store :as store])
  (:import (java.io File)))

(defn- persist!
  "Writes `db` (the full in-memory map: :contacts :campaigns :sends
  :engagement :ledger) as one EDN snapshot to `path`. Writes to a
  sibling `.tmp` file first and renames it over `path` so a crash
  mid-write can never leave `path` holding a truncated snapshot — the
  previous good snapshot stays live at `path` until the new one has
  fully landed on disk."
  [path db]
  (let [tmp (File. (str path ".tmp"))]
    (spit tmp (pr-str db))
    (.renameTo tmp (File. (str path)))))

(defn- load-or-seed!
  "Loads `path`'s EDN snapshot if it exists; otherwise seeds it with
  `marketing.store/demo-data` (the same fictitious dataset
  `marketing.store/seed-db` uses) and writes that as the first
  snapshot, so a brand-new path behaves like a fresh `seed-db` on first
  boot but is durable from then on."
  [path]
  (let [f (File. (str path))]
    (if (.exists f)
      (edn/read-string (slurp f))
      (let [db (assoc (store/demo-data) :ledger [])]
        (persist! path db)
        db))))

(defrecord FileStore [mem path]
  store/Store
  (contact [_ id] (store/contact mem id))
  (all-contacts [_] (store/all-contacts mem))
  (campaign [_ id] (store/campaign mem id))
  (all-campaigns [_] (store/all-campaigns mem))
  (send-record [_ campaign-id contact-id] (store/send-record mem campaign-id contact-id))
  (engagement-history [_ contact-id] (store/engagement-history mem contact-id))
  (ledger [_] (store/ledger mem))
  (commit-record! [s record]
    (store/commit-record! mem record)
    (persist! path @(:a mem))
    s)
  (append-ledger! [_ fact]
    (let [f (store/append-ledger! mem fact)]
      (persist! path @(:a mem))
      f))
  (with-contacts [s cs]
    (store/with-contacts mem cs) (persist! path @(:a mem)) s)
  (with-campaigns [s cs]
    (store/with-campaigns mem cs) (persist! path @(:a mem)) s)
  (with-sends [s sds]
    (store/with-sends mem sds) (persist! path @(:a mem)) s)
  (with-engagement [s eng]
    (store/with-engagement mem eng) (persist! path @(:a mem)) s))

(defn file-store!
  "Opens (or creates) a disk-durable `Store` at `path` (a plain
  filesystem path string). If `path` already holds a snapshot, loads it;
  otherwise seeds it with the same demo dataset `marketing.store/seed-db`
  uses and writes that as the first snapshot. Every mutating call
  (`commit-record!`, `append-ledger!`, `with-contacts`/`with-campaigns`/
  `with-sends`/`with-engagement`) persists a fresh full snapshot to
  `path` before returning — the next `file-store!` on the same `path`
  (e.g. after a process restart) picks up exactly that state."
  [path]
  (->FileStore (store/->MemStore (atom (load-or-seed! path))) path))
