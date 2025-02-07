;;
;; Copyright (c) Two Sigma Open Source, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns waiter.main
  (:require [clj-time.core :as t]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [metrics.jvm.core :as jvm-metrics]
            [plumbing.core :as pc]
            [plumbing.graph :as graph]
            [qbits.jet.server :as server]
            [schema.core :as s]
            [waiter.config :as config]
            [waiter.cors :as cors]
            [waiter.core :as core]
            [waiter.correlation-id :as cid]
            [waiter.request-log :as rlog]
            [waiter.settings :as settings]
            [waiter.util.date-utils :as du]
            [waiter.util.utils :as utils])
  (:import clojure.core.async.impl.channels.ManyToManyChannel
           java.io.IOException
           javax.servlet.ServletInputStream)
  (:gen-class))

(defn retrieve-git-version []
  (try
    (let [git-log-resource (io/resource "git-log")
          git-version-str (if git-log-resource
                            (do
                              (log/info "Retrieving version from git-log file")
                              (slurp git-log-resource))
                            (do
                              (log/info "Attempting to retrieve version from git repository")
                              (str/trim (:out (sh/sh "git" "rev-parse" "HEAD")))))]
      (log/info "Git version:" git-version-str)
      git-version-str)
    (catch Exception e
      (log/error e "version unavailable")
      "n/a")))

(defn- consume-request-stream [handler]
  (fn [{:keys [body] :as request}]
    (let [resp (handler request)]
      (if (and (instance? ServletInputStream body)
               (not (.isFinished ^ServletInputStream body))
               (not (instance? ManyToManyChannel resp)))
        (try
          (slurp body)
          (catch IOException e
            (log/error e "Unable to consume request stream"))))
      resp)))

(defn wire-app
  [settings]
  {:curator core/curator
   :daemons core/daemons
   :handlers core/request-handlers
   :routines core/routines
   :scheduler core/scheduler
   :settings (pc/fnk dummy-symbol-for-fnk-schema-logic :- settings/settings-schema [] settings)
   :state core/state
   :http-server (pc/fnk [[:routines generate-log-url-fn waiter-request?-fn websocket-request-acceptor]
                         [:settings cors-config host port server-options support-info websocket-config]
                         [:state cors-validator router-id server-name]
                         handlers] ; Insist that all systems are running before we start server
                  (let [options (merge (cond-> server-options
                                         (:ssl-port server-options) (assoc :ssl? true))
                                       websocket-config
                                       {:ring-handler (-> (core/ring-handler-factory waiter-request?-fn handlers)
                                                          (cors/wrap-cors-preflight cors-validator (:max-age cors-config))
                                                          core/wrap-error-handling
                                                          (core/wrap-debug generate-log-url-fn)
                                                          rlog/wrap-log
                                                          core/correlation-id-middleware
                                                          (core/wrap-request-info router-id support-info)
                                                          (core/attach-server-header-middleware server-name)
                                                          consume-request-stream)
                                        :websocket-acceptor websocket-request-acceptor
                                        :websocket-handler (-> (core/websocket-handler-factory handlers)
                                                               rlog/wrap-log
                                                               core/correlation-id-middleware
                                                               (core/wrap-request-info router-id support-info))
                                        :host host
                                        :join? false
                                        :port port
                                        :send-server-version? false})]
                    (server/run-jetty options)))})

(defn start-waiter [config-file]
  (try
    (cid/replace-pattern-layout-in-log4j-appenders)
    (log/info "starting waiter...")
    (let [async-threads (System/getProperty "clojure.core.async.pool-size")
          settings (assoc (settings/load-settings config-file (retrieve-git-version))
                     :async-threads async-threads
                     :started-at (du/date-to-str (t/now)))]
      (log/info "core.async threadpool configured to use" async-threads "threads.")
      (log/info "loaded settings:\n" (with-out-str (clojure.pprint/pprint settings)))
      (config/initialize-config settings)
      (let [app-map (wire-app settings)]
        ((graph/eager-compile app-map) {})))
    (catch Throwable e
      (log/fatal e "encountered exception starting waiter")
      (utils/exit 1 (str "Exiting: " (.getMessage e))))))

(defn validate-config-schema
  [config-file]
  (println "Validating schema of" config-file "...")
  (try
    (s/validate settings/settings-schema (settings/load-settings config-file (retrieve-git-version)))
    (utils/exit 0 "Schema is valid.")
    (catch Throwable e
      (println "Schema is invalid.")
      (utils/exit 1 (.getMessage e)))))

(def cli-options
  [["-s" "--schema-validate" "Only validate the configuration schema"]])

(defn parse-options
  [args]
  (let [{:keys [options]} (cli/parse-opts args cli-options)
        validate-config (:schema-validate options)]
    {:validate-config validate-config}))

(defn -main
  [config & args]
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread throwable]
        (log/error throwable (str (.getName thread) " threw exception: " (.getMessage throwable))))))
  (jvm-metrics/instrument-jvm)
  (let [{:keys [validate-config]} (parse-options args)]
    (if validate-config
      (validate-config-schema config)
      (start-waiter config))))
