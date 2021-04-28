;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.events
  (:require
   ["ua-parser-js" :as UAParser]
   [lambdaisland.uri :as u]
   [app.config :as cf]
   [app.common.data :as d]
   [app.main.store :as st]
   [app.util.storage :refer [storage]]
   [app.util.globals :as g]
   [app.util.time :as dt]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; NOTE: this ns is explicitly empty for use as namespace where attach
;; event related keywords.

(defn with-latest-from
  "Merges the specified observable sequences into one observable
  sequence by using the selector function only when the source
  observable sequence (the instance) produces an element."
  ([other source]
   (let [wlf (.-withLatestFrom rx/rxop)
         cmb (cond
               (rx/observable? other) (wlf other)
               (array? other)         (.apply wlf nil other)
               (sequential? other)    (apply wlf other)
               :else                  (throw (ex-info "Invalid argument" {:type ::invalid-argument})))]
     (rx/pipe source cmb)))
  ([o1 o2 source]
   (rx/pipe source (.withLatestFrom rx/rxop o1 o2)))
  ([o1 o2 o3 source]
   (rx/pipe source (.withLatestFrom rx/rxop o1 o2 o3)))
  ([o1 o2 o3 o4 source]
   (rx/pipe source (.withLatestFrom rx/rxop o1 o2 o3 o4)))
  ([o1 o2 o3 o4 o5 source]
   (rx/pipe source (.withLatestFrom rx/rxop o1 o2 o3 o4 o5)))
  ([o1 o2 o3 o4 o5 o6 source]
   (rx/pipe source (.withLatestFrom rx/rxop o1 o2 o3 o4 o5 o6))))

(defn- collect-context
  []
  (let [uagent (UAParser.)]
    (d/merge
     (let [browser (.getBrowser uagent)]
       {:browser (obj/get browser "name")
        :browser-version (obj/get browser "version")})
     (let [engine (.getEngine uagent)]
       {:engine (obj/get engine "name")
        :engine-version (obj/get engine "version")})
     (let [os      (.getOS uagent)
           name    (obj/get os "name")
           version (obj/get os "version")]
       {:os (str name " " version)
        :os-version version})
     (let [device (.getDevice uagent)]
       (if-let [type (obj/get device "type")]
         {:device-type type
          :device-vendor (obj/get device "vendor")
          :device-model (obj/get device "model")}
         {:device-type "unknown"}))
     (let [screen      (obj/get g/window "screen")
           orientation (obj/get screen "orientation")]
       {:screen-width (obj/get screen "width")
        :screen-height (obj/get screen "height")
        :screen-color-depth (obj/get screen "colorDepth")
        :screen-orientation (obj/get orientation "type")})
     (let [cpu (.getCPU uagent)]
       {:device-arch (obj/get cpu "architecture")}))))

(def context
  (delay (d/without-nils (collect-context))))

(defn- navigate->event
  [event]
  (let [data (deref event)]
    {:type :screen
     :properties {:id (:id data)}}))

(defn- action->event
  [event]
  (let [data (deref event)]
    ;; The `:name` is mandatory
    (when (:name data)
      {:type :action
       :properties data})))

(defn- profile-fetched->event
  [event]
  (let [data  (deref event)
        mdata (meta data)
        props [:email
               :auth-backend
               :fullname
               :is-muted
               :default-team-id
               :default-project-id]]
    {:type :identify
     :properties (select-keys data props)
     :context (cond-> @context
                (::source mdata)
                (assoc :source (::source mdata)))}))

(def ^:private events
  {::action action->event
   :app.util.router/navigate navigate->event
   :app.main.data.users/profile-fetched profile-fetched->event})

;; Defines the maximum buffer size, after events start discarding.
(def max-buffer-size 1024)

;; Defines the maximum number of events that can go in a single batch.
(def max-chunk-size 100)

(defn- append-to-buffer
  [buffer item]
  (if (>= (count buffer) max-buffer-size)
    buffer
    (conj buffer item)))

(defn- remove-from-buffer
  [buffer items]
  (into #queue [] (drop items) buffer))

(defn- persist-events
  [events]
  (let [uri (u/join cf/public-uri "telemetry")]
    (if (odd? (rand-int 1000))
      (->> (rx/of uri)
           (rx/delay 1000))
      (rx/throw (ex-info "foobar" {})))))

(defmethod ptk/resolve ::persistence
  [_ {:keys [buffer] :as params}]
  (ptk/reify ::persistence
    ptk/EffectEvent
    (effect [_ state stream]
      (let [events (into [] (take max-chunk-size) @buffer)]
        (when-not (empty? events)
          (->> (persist-events events)
               (rx/subs (fn [_]
                          (swap! buffer remove-from-buffer (count events)))
                        (fn [e]
                          (swap! buffer identity)))))))))

(defmethod ptk/resolve ::initialize
  [_ params]
  (let [buffer (atom #queue [])]
    (ptk/reify ::initialize
      ptk/WatchEvent
      (watch [_ state stream]
        (->> (rx/from-atom buffer)
             (rx/filter #(pos? (count %)))
             (rx/debounce 2000)
             (rx/map #(ptk/event ::persistence {:buffer buffer}))))

      ptk/EffectEvent
      (effect [_ state stream]
        (let [profile (->> (rx/from-atom storage)
                           (rx/map :profile)
                           (rx/map :id)
                           (rx/dedupe))

              source  (->> stream
                           (rx/map (fn [event]
                                     (let [type    (ptk/type event)
                                           impl-fn (get events type)]
                                       (when (fn? impl-fn)
                                         (impl-fn event)))))
                           (rx/filter some?))]

          (->> source
               (with-latest-from profile)
               (rx/map (fn [result]
                         (let [event      (aget result 0)
                               profile-id (aget result 1)]
                           (assoc event :profile-id profile-id))))
               (rx/filter :profile-id)
               (rx/subs (fn [result]
                          (swap! buffer append-to-buffer result))
                        (fn [error]
                          (js/console.log "error" error)))))))))
