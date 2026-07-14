(ns mining-managers.store
  "Store protocol and in-memory implementation for mining manager administrative
  support. Maintains registered managers, registered mine-sites, committed
  operational records, and an append-only audit ledger.

  The Store is the system of record and never exposed to the Advisor; only the
  Governor and Actor have store access (itonami actor pattern).")

(defprotocol Store
  "Operational store for mining manager administrative support."

  (manager [store manager-id]
    "Returns the registered manager record or nil.")

  (register-manager! [store record]
    "Registers a new manager {manager-id, name, certifications, experience-years}.
     Returns the updated store.")

  (mine-site [store site-id]
    "Returns the registered mine-site record or nil.")

  (register-site! [store record]
    "Registers a new mine-site {site-id, operator-id, location, risk-level}.
     Returns the updated store.")

  (log-record! [store record-type record-data]
    "Appends an operational record (shift, production, maintenance, incident).
     Always append-only; returns updated store.")

  (get-audit-log [store]
    "Returns the full append-only audit ledger (immutable vector)."))

(defrecord MemStore [a]
  Store
  (manager [_ manager-id]
    (get-in @a [:managers manager-id]))

  (register-manager! [s record]
    (swap! a assoc-in [:managers (:manager-id record)] record) s)

  (mine-site [_ site-id]
    (get-in @a [:mine-sites site-id]))

  (register-site! [s record]
    (swap! a assoc-in [:mine-sites (:site-id record)] record) s)

  (log-record! [s record-type record-data]
    (let [entry {:timestamp #?(:clj (java.time.Instant/now)
                               :cljs (js/Date.))
                 :type record-type
                 :data record-data}]
      (swap! a update :audit-log (fnil conj []) entry) s))

  (get-audit-log [_]
    (:audit-log @a)))

(defn mem-store
  ([] (mem-store {}))
  ([seed]
   (->MemStore (atom (merge {:managers {} :mine-sites {} :audit-log []} seed)))))
