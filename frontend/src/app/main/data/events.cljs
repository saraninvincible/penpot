;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.events
  (:require
   ["ua-parser-js" :as UAParser]
   [lambdaisland.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.common.data :as d]
   [app.main.store :as st]
   [app.util.storage :refer [storage]]
   [app.util.globals :as g]
   [app.util.time :as dt]
   [app.util.http :as http]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; Defines the maximum buffer size, after events start discarding.
(def max-buffer-size 1024)

;; Defines the maximum number of events that can go in a single batch.
(def max-chunk-size 100)

;; Protocol used for attach additional props to potok events
(defprotocol IProps
  (-props [_] "Get event props"))

(comment
  {:name "register"
   :type "identify"
   :timestamp 10203044
   :profile-id 1
   :props
   {:source "register"
    :email "niwi@niwi.nz"
    :fullname "andrey antukh"}
   :context
   {:app-version "1.1"}}

  {:name "navigate"
   :type "action"
   :props {:route-id "dashboard"}
   :context {}}

  {:name "open-comments"
   :type "action"
   :props {:source "viewer"}
   :context {}})


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


(defn throttle
  "Returns an observable sequence that emits only the
  first item emitted by the source Observable during
  sequential time windows of a specified duration."
  ([ms ob]
   (rx/pipe ob (.throttleTime rx/rxop ms)))
  ([ms config ob]
   (let [{:keys [leading trailing]
          :or {leading true trailing false}} config]
     (rx/pipe ob (.throttleTime rx/rxop ms #js {:leading leading :trailing trailing})))))

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

(defn- navigate->event
  [event]
  (let [match (deref event)
        props {:route (name (get-in match [:data :name]))
               :path (:path match)
               :path-params (:path-params match)
               :query-params (:query-params match)}]
    {:name "navigate"
     :type "action"
     :timestamp (dt/now)
     :props (d/without-nils props)}))

(defn- action->event
  [event]
  (let [data (deref event)]
    ;; The `:name` is mandatory
    (when (:name data)
      {:type :action
       :name (:name data)
       :props (dissoc data :name)})))

(defn- logged-in->event
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

(defn- generic-action
  [name]
  (fn [event]
    {:type "action"
     :name name
     :props (if (satisfies? IProps event)
              (-props event)
              {})}))

(def ^:private events
  {::action action->event
   :app.util.router/navigated navigate->event
   :app.main.data.users/logout (generic-action "logout")
   :app.main.data.users/logged-in logged-in->event
   :app.main.data.dashboard/create-team (generic-action "create-team")
   })

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
                           (rx/dedupe))]
          (->> stream
               (with-latest-from profile)
               (rx/map (fn [result]
                         (let [event      (aget result 0)
                               profile-id (aget result 1)
                               type       (ptk/type event)
                               impl-fn    (get events type)]
                           (when (fn? impl-fn)
                             (-> (impl-fn event)
                                 (update :profile-id #(or % profile-id))
                                 (assoc :timestamp (dt/now))
                                 #_(update :context d/merge @context))))))
               (rx/filter some?)
               (rx/filter :profile-id)
               (rx/subs (fn [event]
                          (swap! buffer append-to-buffer event))
                        (fn [error]
                          (js/console.log "error" error)))))))))
