(ns witan.httpapi.api-test
  (:require [witan.httpapi.api :refer :all]
            [witan.httpapi.test-base :refer :all]
            [witan.httpapi.system :as sys]
            [witan.httpapi.mocks :as mocks]
            [witan.httpapi.spec :as s]
            [clojure.spec.alpha :as spec]
            [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [environ.core :refer [env]]
            [taoensso.timbre :as log]
            [aleph.http :as http]
            [cheshire.core :as json]))

(def user-id (uuid))

(defn start-system
  [all-tests]
  (let [sys (atom (component/start
                   (with-redefs [sys/new-requester (fn [config] (mocks/->MockRequester))
                                 sys/new-authenticator (fn [config] (mocks/->MockAuthenticator user-id nil))]
                     (sys/new-system profile))))
        peripherals (atom (component/start
                           (component/system-map
                            :datastore (mocks/->MockDatastore (:comms @sys)))))]
    (all-tests)
    (component/stop @sys)
    (component/stop @peripherals)))

(use-fixtures :once start-system)

(defn local-url
  [method]
  (str "http://localhost:8015" method))

(defn ->result
  [resp]
  ((juxt :body :status) @resp))

(defn with-default-opts
  [opts]
  (merge {:content-type :json
          :as :json
          :accept :json
          :throw-exceptions false}
         opts))

(defmacro is-spec
  [spec r]
  `(is (spec/valid? ~spec ~r)
       (str (spec/explain-data ~spec ~r))))

(defn get-auth-tokens
  []
  (let [[r s] (->result
               (http/post (local-url "/api/login")
                          (with-default-opts
                            {:form-params
                             {:username "test@mastodonc.com"
                              :password "Secret123"}})))]
    (is (= 201 s))
    (:token-pair r)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest healthcheck-test
  (let [[r s] (->result (http/get (local-url "/healthcheck")))]
    (is (=  200 s))
    (is (= "hello" (slurp r)))))

(deftest noexist-404-test
  (let [[r s] (->result (http/get (local-url "/notexist")
                                  {:throw-exceptions false}))]
    (is (= 404 s))))

;; Currently doesn't pass and there doesn't appear to be a
;; nice way to achieve this
#_(deftest nomethod-405-test
    (let [[r s] (->result (http/post (local-url "/healthcheck")
                                     {:throw-exceptions false}))]
      (is (= 405 s))))

(deftest login-test
  (let [[r s] (->result
               (http/post (local-url "/api/login")
                          (with-default-opts
                            {:form-params
                             {:username "test@mastodonc.com"
                              :password "Secret123"}})))]
    (is (= 201 s))
    (is-spec ::s/token-pair-container r)))

(deftest refresh-test
  (let [[r s] (->result
               (http/post (local-url "/api/refresh")
                          (with-default-opts
                            {:form-params
                             {:token-pair
                              {:auth-token "012"
                               :refresh-token "345"}}})))]
    (is (= 201 s))
    (is-spec ::s/token-pair-container r)))

(deftest swagger-test
  (let [[r s] (->result
               (http/get (local-url "/swagger.json")
                         {:throw-exceptions false
                          :as :json}))]
    (is (= 200 s) "An error here could indicate a problem generating the swagger JSON")
    (is (= "2.0" (:swagger r)))))

(deftest upload-file-test
  (let [auth (get-auth-tokens)
        resp @(http/post (local-url "/api/files/upload")
                         {:throw-exceptions false
                          :as :json
                          :headers {:authorization {:witan.httpapi.spec/auth-token (:auth-token auth)}}})]
    (is (= 202 (:status resp)))
    (when-let [receipt-id (get-in resp [:body :receipt])]
      (log/debug "upload-file-test - Got receipt ID" receipt-id)
      (loop [tries 10]
        (log/debug "upload-file-test - Checking receipt ..." tries)
        (let [url (local-url (str "/api/receipts/" receipt-id))
              receipt-resp @(http/get url
                                      {:throw-exceptions false
                                       :as :json
                                       :headers {:authorization {:witan.httpapi.spec/auth-token (:auth-token auth)}}})]
          (if (not= 200 (:status receipt-resp))
            (do
              (log/debug "upload-file-test - ...got" (:status receipt-resp))
              (if (pos? tries)
                (recur (dec tries))
                (is false (str "Never returned a 200: " url " - "receipt-resp))))
            (do
              (is (= 200 (:status receipt-resp)))
              (let [body (:body receipt-resp)
                    uri (:witan.httpapi.spec/uri body)
                    file-id (:kixi.datastore.filestore/id body)]
                (is uri)
                (is file-id (prn-str body))))))))))

(deftest file-errors-test
  "Trigger an error by trying to PUT metadata that doesn't exist"
  (let [auth (get-auth-tokens)
        file-id (uuid)
        resp @(http/put (local-url (str "/api/files/" file-id "/metadata"))
                        {:form-params
                         {:kixi.datastore.metadatastore/size-bytes 123
                          :kixi.datastore.metadatastore/file-type "foo"
                          :kixi.datastore.metadatastore/header false
                          :kixi.datastore.metadatastore/name "fail"
                          :kixi.datastore.metadatastore/description "please"}
                         :throw-exceptions false
                         :content-type :json
                         :as :json
                         :headers {:authorization {:witan.httpapi.spec/auth-token (:auth-token auth)}}})]
    (is (= 202 (:status resp)))
    (when-let [receipt-id (get-in resp [:body :receipt])]
      (log/debug "file-errors-test - Got receipt ID" receipt-id)
      (loop [tries 10]
        (log/debug "file-errors-test - Checking receipt ..." tries)
        (let [url (local-url (str "/api/receipts/" receipt-id))
              receipt-resp @(http/get url
                                      {:throw-exceptions false
                                       :as :json
                                       :headers {:authorization {:witan.httpapi.spec/auth-token (:auth-token auth)}}})]
          (if (not= 200 (:status receipt-resp))
            (do
              (log/debug "file-errors-test - ...got" (:status receipt-resp))
              (if (pos? tries)
                (recur (dec tries))
                (is false (str "file-errors-test - Never returned a 200: " url " - "receipt-resp))))
            (do
              (is (= 200 (:status receipt-resp)))
              (let [body (:body receipt-resp)
                    reason (get-in body [:error :witan.httpapi.spec/reason])]
                (is (= "mock-fail" reason))))))))))
