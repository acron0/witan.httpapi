(ns witan.httpapi.components.activities
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre            :as log]
            [clojure.spec.alpha :as s]
            [com.gfredericks.schpec :as sh]
            [kixi.comms :as comms]
            [witan.httpapi.components.database :as database]
            [witan.httpapi.spec :as spec]))

(sh/alias 'command 'kixi.command)

(def receipts-table "receipts")
(def upload-links-table "upload-links")

(defmethod database/table-spec
  [:put receipts-table] [& _] ::spec/receipt)

(defmethod database/table-spec
  [:update receipts-table] [& _] ::spec/receipt-update)

(defmethod database/table-spec
  [:put upload-links-table] [& _] ::spec/upload-link)

(defn send-valid-command!*
  "Eventually deprecate this function for comms/send-valid-command!"
  [comms command opts]
  (let [cmd-with-id (assoc command ::command/id
                           (or (::command/id command)
                               (comms/uuid))
                           :kixi.message/type :command
                           ::command/created-at (comms/timestamp))
        {:keys [kixi.command/type
                kixi.command/version
                kixi.command/id
                kixi/user]} cmd-with-id
        {:keys [partition-key]} opts]
    (when-not (s/valid? :kixi/command cmd-with-id)
      (throw (ex-info "Invalid command" (s/explain-data :kixi/command cmd-with-id))))
    (when-not (s/valid? ::command/options opts)
      (throw (ex-info "Invalid command options" (s/explain-data ::command/options opts))))
    (comms/send-command! comms type version user (dissoc cmd-with-id
                                                         ::command/id
                                                         ::command/type
                                                         ::command/version
                                                         ::command/created-at
                                                         :kixi.message/type
                                                         :kixi/user)
                         {:kixi.comms.command/partition-key partition-key
                          :kixi.comms.command/id id})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Receipts

(defn create-receipt! [database user id]
  (let [spec-receipt {::spec/id id
                      :kixi.user/id (:kixi.user/id user)
                      ::spec/status "pending"
                      ::spec/created-at (comms/timestamp)
                      ::spec/last-updated-at (comms/timestamp)}]
    (database/put-item database receipts-table spec-receipt nil)    ))

(defn retreive-receipt
  [db id]
  (database/get-item db receipts-table {::spec/id id} nil))

(defn get-receipt-response
  [act user id]
  ;;
  (let [receipt (retreive-receipt (:database act) id)]
    (cond
      (nil? receipt)                                      [404 nil nil]
      (not= (:kixi.user/id receipt) (:kixi.user/id user)) [401 nil nil]
      (= "pending" (::spec/status receipt))               [202 nil nil]
      :else [303 nil {"Location" (::spec/uri receipt)}])))

(defn complete-receipt!
  [db id uri]
  (database/update-item
   db
   receipts-table
   {::spec/id id}
   {::spec/uri uri
    ::spec/status "complete"}
   nil))

(defn create-upload-link! [database user id file-id upload-link]
  (let [spec-upload-link {::spec/id id
                          :kixi.user/id (:kixi.user/id user)
                          :kixi.datastore.filestore/id file-id
                          ::spec/created-at (comms/timestamp)
                          ::spec/uri upload-link}]
    (database/put-item database upload-links-table spec-upload-link nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Uploads

(defn create-file-upload!
  [{:keys [comms database]} user]
  (let [id (comms/uuid)]
    (create-receipt! database user id)
    (send-valid-command!* comms {::command/id id
                                 ::command/type :kixi.datastore.filestore/create-upload-link
                                 ::command/version "1.0.0"
                                 :kixi/user user}
                          {:partition-key id})
    [202
     {:receipt id}
     {"Location" (str "/receipts/" id)}]))

(defn retreive-upload-link
  [db id]
  (database/get-item db upload-links-table {::spec/id id} nil))

(defn get-upload-link-response
  [act user id]
  (let [row (retreive-upload-link (:database act) id)]
    (cond
      (nil? row)                                          [404 nil nil]
      (not= (:kixi.user/id row) (:kixi.user/id user)) [401 nil nil]
      :else [200 {:upload-link (::spec/uri row)} nil])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(defmulti on-event
  (fn [_ {:keys [kixi.comms.event/key
                 kixi.comms.event/version]}] [key version]))

(defmethod on-event
  [:kixi.datastore.filestore/upload-link-created "1.0.0"]
  [db {:keys [kixi.comms.event/payload] :as event}]
  (let [command-id (:kixi.comms.command/id event)]
    (when-let [receipt (retreive-receipt db command-id)]
      (let [{:keys [kixi.datastore.filestore/upload-link
                    kixi.datastore.filestore/id]} payload]
        (create-upload-link! db
                             (select-keys payload [:kixi.user/id])
                             command-id
                             id
                             upload-link)
        (complete-receipt! db command-id (str "/api/files/upload/" command-id)))))
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defrecord Activities []
  component/Lifecycle
  (start [{:keys [comms database] :as component}]
    (log/info "Starting Activities" (type comms))
    (let [ehs [(comms/attach-event-handler!
                comms
                :witan-httpapi-activity-upload-file
                :kixi.datastore.filestore/upload-link-created
                "1.0.0"
                (partial on-event database))]]
      (assoc component :ehs ehs)))

  (stop [{:keys [comms] :as component}]
    (log/info "Stopping Activities")
    (when-let [ehs (:ehs component)]
      (run! (partial comms/detach-handler! comms) ehs))
    (dissoc component :ehs)))