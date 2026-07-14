(ns mining-managers.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [mining-managers.actor :as actor]
            [mining-managers.advisor :as advisor]
            [mining-managers.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-manager! st {:manager-id "manager-1" :name "Alice Chen" :certifications ["MSHA"] :experience-years 12})
    (store/register-site! st {:site-id "site-001" :operator-id "operator-1" :location "Nevada" :risk-level :medium})
    st))

(deftest graph-build-succeeds
  (let [st (fresh-store)
        advisor-fn (advisor/mock-advisor)
        store-fn (fn [] st)
        graph (actor/build-graph advisor-fn store-fn)]
    (is (contains? graph :nodes))
    (is (contains? graph :edges))
    (is (contains? graph :start-node))))

(deftest run-request-accepts-clean-proposal
  (let [st (fresh-store)
        adv (advisor/mock-advisor)
        request {:manager-id "manager-1" :op :schedule-shift :stake :low}
        state (actor/run-request! {:advisor adv} request {} st)]
    (is (contains? state :proposal))
    (is (contains? state :verdict))))

(deftest approve-marks-state-as-committed
  (let [state {:outcome :waiting-approval}
        approved (actor/approve! state)]
    (is (= :committed (:outcome approved)))
    (is (contains? approved :approval-timestamp))))
