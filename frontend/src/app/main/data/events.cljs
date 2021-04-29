;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.events
  (:require
   ["ua-parser-js" :as UAParser]
   [app.common.data :as d]
   [app.config :as cf]
   [app.util.globals :as g]
   [app.util.http :as http]
   [app.util.object :as obj]
   [app.util.storage :refer [storage]]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [lambdaisland.uri :as u]
   [potok.core :as ptk]))

;; Defines the maximum buffer size, after events start discarding.
(def max-buffer-size 1024)

;; Defines the maximum number of events that can go in a single batch.
(def max-chunk-size 100)

(defn event
  "A simple action event constructor."
  [{:keys [::name] :as props}]
  (ptk/data-event ::generic-event props))

;; --- CONTEXT

(defn- collect-context
  []
  (let [uagent (UAParser.)]
    (d/merge
     {:app-version (:full @cf/version)}
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

;; --- EVENT TRANSLATION

(defmulti ^:private process-event ptk/type)
(defmethod process-event :default [_] nil)

(defmethod process-event ::generic-event
  [event]
  (let [data (deref event)]
    (when (::name data)
      (d/without-nils
       {:type    (::type data "action")
        :name    (::name data)
        :context (::context data)
        :props   (dissoc data ::name ::type ::context)}))))

(defmethod process-event :app.util.router/navigated
  [event]
  (let [match (deref event)
        route (get-in match [:data :name])
        props {:route      (name route)
               :team-id    (get-in match [:path-params :team-id])
               :file-id    (get-in match [:path-params :file-id])
               :project-id (get-in match [:path-params :project-id])}]
    {:name "navigate"
     :type "action"
     :timestamp (dt/now)
     :props (d/without-nils props)}))

(defmethod process-event :app.main.data.users/logged-in
  [event]
  (let [data  (deref event)
        mdata (meta data)
        props [:email
               :auth-backend
               :fullname
               :is-muted
               :default-team-id
               :default-project-id]]
    {:name "signin"
     :type "identify"
     :profile-id (:id data)
     :props (-> (select-keys data props)
                (assoc :signin-source (::source mdata)))}))

(defmethod process-event :app.main.data.dashboard/project-created
  [event]
  (let [data (deref event)]
    {:type "action"
     :name "create-page"
     :props {:name (:name data)
             :team-id (:team-id data)}}))

(defn- event->generic-action
  [event name]
  {:type "action"
   :name name
   :props {}})

(defmethod process-event :app.main.data.users/logout
  [event]
  (event->generic-action event "logout"))


;; --- MAIN LOOP

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
  (let [uri    (u/join cf/public-uri "events")
        params {:events events}]
    (http/send! {:uri uri
                 :method :post
                 :body (http/transit-data params)})))

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
        (let [profile (->> (rx/from-atom storage {:emit-current-value? true})
                           (rx/map :profile)
                           (rx/map :id)
                           (rx/dedupe))
              events  (methods process-event)]
          (->> stream
               (rx/with-latest-from profile)
               (rx/map (fn [result]
                         (let [event      (aget result 0)
                               profile-id (aget result 1)
                               type       (ptk/type event)
                               impl-fn    (get events type)]
                           (when (fn? impl-fn)
                             (some-> (impl-fn event)
                                     (update :profile-id #(or % profile-id))
                                     (assoc :timestamp (dt/now)))))))
               (rx/filter some?)
               (rx/filter :profile-id)
               (rx/tap #(prn "EVT" (:type %) (:name %) (:props %)))
               (rx/subs (fn [event]
                          (swap! buffer append-to-buffer event))
                        (fn [error]
                          (js/console.log "error" error)))))))))
