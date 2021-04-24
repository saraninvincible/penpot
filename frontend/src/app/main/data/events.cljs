;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.events
  (:require
   [app.main.store :as st]
   [beicon.core :as rx]
   [goog.events]
   [potok.core :as ptk]))

;; NOTE: this ns is explicitly empty for use as namespace where attach
;; event related keywords.

(def capture-events
  #{:app.main.data.dashboard/file-created
    :app.main.data.users/profile-fetched
    :app.main.data.dashboard/fetch-bundle})

(defmethod ptk/resolve ::initialize
  [_ params]
  (letfn [(on-navigate [event]
            (let [data (deref event)]
              (prn "NAV" (:id data))))

          (on-action [event]
            (prn "ACTION" (deref event)))]
    (ptk/reify ::initialize
      ptk/EffectEvent
      (effect [_ state stream]
        (prn ::initialize)
        (let [stoper (rx/filter (ptk/type? ::initialize) stream)]
          ;; Watch for navigation events
          (->> stream
               (rx/filter (ptk/type? :app.util.router/navigate))
               (rx/observe-on :queue)
               (rx/take-until stoper)
               (rx/subs on-navigate))
          (->> stream
               (rx/filter (ptk/type? ::action))
               (rx/subs on-action)))))))
