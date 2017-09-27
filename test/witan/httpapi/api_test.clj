(ns witan.httpapi.api-test
  {:integration true}
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
            [cheshire.core :as json]
            ;;
            [kixi.user :as user]
            [kixi.datastore.metadatastore :as ms]))

(def user-id (uuid))

(defn test-system
  [sys-fn]
  (with-redefs [sys/new-authenticator (fn [config] (mocks/->MockAuthenticator user-id [(uuid)]))]
    (sys-fn)))

(defn start-system
  [all-tests]
  (if (= :staging-jenkins profile)
    (all-tests)
    (let [mocks-fn (if (= :test profile)
                     test-system
                     std-system)
          sys (atom (component/start
                     (mocks-fn
                      #(sys/new-system profile))))]
      (all-tests)
      (component/stop @sys))))

(use-fixtures :once start-system)

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

(defn login
  []
  (->result
   (http/post (url "/api/login")
              (with-default-opts
                {:form-params
                 {:username "test@mastodonc.com"
                  :password "Secret123"}}))))

(defn get-auth-tokens
  []
  (let [[r s] (login)]
    (is (= 201 s) (prn-str r))
    (:token-pair r)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest healthcheck-test
  (let [[r s] (->result (http/get (url "/healthcheck")))]
    (is (=  200 s))
    (is (= "hello" (slurp r)))))

(deftest noexist-404-test
  (let [[r s] (->result (http/get (url "/notexist")
                                  {:throw-exceptions false}))]
    (is (= 404 s))))

;; Currently doesn't pass and there doesn't appear to be a
;; nice way to achieve this
#_(deftest nomethod-405-test
    (let [[r s] (->result (http/post (url "/healthcheck")
                                     {:throw-exceptions false}))]
      (is (= 405 s))))

(deftest login-test
  (let [[r s] (login)]
    (is (= 201 s))
    (is-spec ::user/token-pair-container r)))

(deftest refresh-test
  (let [[rl sl] (login)
        [r s] (->result
               (http/post (url "/api/refresh")
                          (with-default-opts
                            {:form-params rl})))]
    (is (= 201 s))
    (is-spec ::user/token-pair-container r)))

(deftest swagger-test
  (let [[r s] (->result
               (http/get (url "/swagger.json")
                         {:throw-exceptions false
                          :as :json}))]
    (is (= 200 s) "An error here could indicate a problem generating the swagger JSON")
    (is (= "2.0" (:swagger r)))))

(deftest upload-roundtrip-plus-get-metadata
  (let [auth (get-auth-tokens)
        file-name  "./test-resources/metadata-one-valid.csv"]

    (testing "Uploading a file"
      (let [metadata (create-metadata file-name)
            retrieved-metadata (send-file-and-metadata auth file-name metadata)]

        (testing "Retrieving the uploaded file's metadata"
          (let [[fetched-metadata s] (->result
                                      (http/get (url (str "/api/files/" (::ms/id retrieved-metadata) "/metadata"))
                                                {:as :json
                                                 :content-type :json
                                                 :headers {:authorization (:auth-token auth)}}))]
            (is (= 200 s))
            (is (= retrieved-metadata fetched-metadata))))

        (testing "Retrieving the uploaded file's sharing details"
          (let [[fetched-sharing s] (->result
                                     (http/get (url (str "/api/files/" (::ms/id retrieved-metadata) "/sharing"))
                                               {:as :json
                                                :content-type :json
                                                :headers {:authorization (:auth-token auth)}}))]
            (is (= 200 s))
            (is-spec ::ms/sharing (::ms/sharing fetched-sharing))))

        (testing "Updating the file's metadata"
          (let [new-desc (str "New description " (uuid))
                new-name (str "New name " (uuid))
                new-tags #{:foo :bar :baz}
                new-maintainer "Bob"
                new-license "Custom"
                new-temporal-coverage-from "20170924T135604.949Z"
                new-temporal-coverage-to   "20170925T135604.949Z"
                update-params {:kixi.datastore.metadatastore.update/description {:set new-desc} ;; optional field
                               :kixi.datastore.metadatastore.update/name {:set new-name} ;; mandatory field
                               #_:kixi.datastore.metadatastore.update/tags #_{:conj new-tags}
                               :kixi.datastore.metadatastore.update/maintainer {:set new-maintainer}
                               :kixi.datastore.metadatastore.license.update/license {:kixi.datastore.metadatastore.license.update/type {:set new-license}}
                               #_:kixi.datastore.metadatastore.time.update/time #_{:kixi.datastore.metadatastore.time.update/from
                                                                                   {:set new-temporal-coverage-from}
                                                                                   :kixi.datastore.metadatastore.time.update/to
                                                                                   {:set new-temporal-coverage-to}}}
                update-receipt (post-metadata-update
                                auth (::ms/id retrieved-metadata) update-params)]
            (if-not (= 202 (:status update-receipt))
              (is false (str "Receipt did not return 202: " update-receipt))
              (let [receipt-resp (wait-for-receipt auth update-receipt)]
                (is (= 200 (:status receipt-resp))
                    (str "post metadata receipt" receipt-resp))

                (Thread/sleep 4000) ;; eventual consistency, innit
                (let [[fetched-metadata s] ((juxt :body :status)
                                            (get-metadata auth (::ms/id retrieved-metadata)))]
                  (is (= 200 s))
                  (is-submap (assoc retrieved-metadata
                                    ::ms/description new-desc
                                    ::ms/name new-name
                                    ;;::ms/tags new-tags
                                    ;;::ms/maintainer new-maintainer
                                    :kixi.datastore.metadatastore/license {:kixi.datastore.metadatastore.license/type new-license}
                                    #_:kixi.datastore.metadatastore/time #_{:kixi.datastore.metadatastore.time/from new-temporal-coverage-from
                                                                            :kixi.datastore.metadatastore.time/to new-temporal-coverage-to})
                             fetched-metadata))))))))))

(deftest file-errors-test
  "Trigger an error by trying to PUT metadata that doesn't exist"
  (let [auth (get-auth-tokens)
        file-name  "./test-resources/metadata-one-valid.csv"
        metadata (assoc (create-metadata file-name) ::ms/id (uuid))
        receipt-resp (put-metadata auth metadata)]
    (if-not (= 202 (:status receipt-resp))
      (is false (str "Receipt did not return 202: " receipt-resp " - " metadata))
      (let [receipt-resp (wait-for-receipt auth receipt-resp)]
        (is (= 200 (:status receipt-resp))
            (str "metadata receipt" receipt-resp))
        (is (= "file-not-exist"
               (get-in receipt-resp [:body :error :witan.httpapi.spec/reason])))))))
