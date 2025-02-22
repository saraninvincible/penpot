;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.loader
  (:require
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i]
   [app.main.store :as st]))

;; --- Component

(mf/defc loader
  []
  (when (mf/deref st/loader)
    [:div.loader-content i/loader]))
