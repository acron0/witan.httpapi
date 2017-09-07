(ns witan.httpapi.api
  (:require [compojure.api.sweet :refer [context GET POST resource api]]
            [ring.util.http-response :refer [ok unauthorized]]
            [clj-time.core              :as t]
            [taoensso.timbre :as log]
            ;;
            [witan.httpapi.spec :as s]
            [witan.httpapi.queries :as query]
            ;;
            [witan.httpapi.components.auth :as auth]
            [witan.httpapi.components.activities :as activities]))

(defn auth
  [req]
  (get-in req [:components :auth]))

(defn requester
  [req]
  (get-in req [:components :requester]))

(defn activities
  [req]
  (get-in req [:components :activities]))

(defn user
  [req]
  (get-in req [:user]))

(defn fail
  ([status]
   (fail status nil))
  ([status body]
   (log/debug "Returning failure response" status)
   {:status status
    :body body}))

(defn success
  ([body]
   (success 200 body))
  ([status body]
   (success status body nil))
  ([status body headers]
   (log/debug "Returning successful response" status)
   {:status status
    :body body
    :headers headers}))

(defn success?
  [status]
  (and (>= status 200)
       (< status 400)))

(def healthcheck-routes
  (context "/" []
    (GET "/healthcheck" []
      (str "hello"))))

(def auth-routes
  (context "/api" []
    :tags ["api"]
    :coercion :spec

    (POST "/login" req
      :summary "Retrieve an authorisation token for further API calls"
      :body-params [username :- ::s/username
                    password :- ::s/password]
      :return ::s/token-pair-container
      (let [[status body] (auth/login (auth req) username password)]
        (if (= status 201)
          (success status body)
          (fail status body))))

    (POST "/refresh" req
      :summary "Refreshes an authorisation token"
      :body-params [token-pair :- ::s/token-pair]
      :return ::s/token-pair-container
      (let [[status body] (auth/refresh (auth req) token-pair)]
        (if (= status 201)
          (success status body)
          (fail status body))))))

(defn authentication-middleware
  [handler]
  (fn [req]
    (let [auth-header (get-in req [:headers "authorization"])]
      (if-let [user (auth/authenticate (auth req) (t/now) auth-header)]
        (handler (assoc req :user user))
        (unauthorized)))))

(def api-routes
  (context "/api" []
    :tags ["api"]
    :coercion :spec
    :header-params [authorization :- ::s/auth-token]
    :middleware [authentication-middleware]

    (GET "/receipts/:receipt" req
      :summary "Returns any results associated with the specified receipt"
      :path-params [receipt :- ::s/receipt]
      :return ::s/result
      (let [[s _ headers] (activities/check-receipt (activities req) (user req) receipt)]
        (if (success? s)
          (success s nil headers)
          (fail s))))

    (context "/files" []

      (GET "/" req
        :summary "Return a list of files the user has access to"
        :return ::s/paged-items
        (let [[s r] (query/get-files-by-user (requester req) (user req))]
          (if (success? s)
            (success s r)
            (fail s))))

      (POST "/upload" req
        :summary "Creates an upload address for a new file"
        :return ::s/receipt
        (let [[s r headers] (activities/create-file-upload!
                             (activities req)
                             (user req))]
          (if (success? s)
            (success s r headers)
            (fail s))))

      (GET "/:id" req
        :summary "Return details of a specific file"
        :path-params [id :- ::s/id]
        :return ::s/file-info
        (let [[s r] (query/get-file-info (requester req) (:user req) id)]
          (if (success? s)
            (success s r)
            (fail s))))

      (GET "/:id/metadata" req
        :summary "Return metadata for a specific file"
        :path-params [id :- ::s/id]
        :return ::s/file-metadata
        (let [[s r] (query/get-file-metadata (requester req) (:user req) id)]
          (if (success? s)
            (success s r)
            (fail s))))

      (POST "/:id/metadata" req
        :summary "Update metadata for a specific file"
        :path-params [id :- ::s/id]
        :return ::s/result
        (ok "hello"))

      (GET "/:id/error" req
        :summary "Return errors for a specific file"
        :path-params [id :- ::s/id]
        :return ::s/result
        (ok "hello"))

      (GET "/:id/sharing" req
        :summary "Return sharing data for a specific file"
        :path-params [id :- ::s/id]
        :return ::s/file-sharing
        (let [[s r] (query/get-file-sharing-info (requester req) (:user req) id)]
          (if (success? s)
            (success s r)
            (fail s))))

      (POST "/:id/sharing" req
        :summary "Update sharing data for a specific file"
        :path-params [id :- ::s/id]
        :return ::s/result
        (ok "hello"))

      (POST "/:id/link" req
        :summary "Creates a download token for a specific file"
        :path-params [id :- ::s/id]
        :return ::s/result
        (ok "hello"))

      (GET "/:id/link/:link-id" req
        :summary "Return a download address for a specific file and token"
        :path-params [id :- ::s/id
                      link-id :- ::s/id]
        :return ::s/result
        (ok "hello")))))

(def handler
  (api
   {:swagger
    {:ui "/"
     :spec "/swagger.json"
     :options {:ui {:jsonEditor true}}
     :data {:info {:title "Witan API (Datastore) "}
            :tags [{:name "api", :description "API routes for Witan"}]}}}
   healthcheck-routes
   auth-routes
   api-routes))
