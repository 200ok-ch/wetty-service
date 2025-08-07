#!/usr/bin/env bb

(require '[org.httpkit.server :as server]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def active-sessions (atom {}))
(def config-dir "/etc/traefik/dynamic")
(def base-port 9000)

(defn generate-port []
  (+ base-port (rand-int 1000)))

(defn write-traefik-config [session-id port]
  (let [config {:http
                {:routers
                 {(keyword (str "wetty-" session-id))
                  {:rule (str "PathPrefix(`/session/" session-id "`)")
                   :service (str "wetty-" session-id)
                   ;; No need to strip prefix - wetty handles it
                   }}
                 :services
                 {(keyword (str "wetty-" session-id))
                  {:loadBalancer
                   {:servers [{:url (str "http://localhost:" port)}]}}}}}
        config-file (str config-dir "/" session-id ".yml")]
    (io/make-parents config-file)
    (spit config-file (json/generate-string config {:pretty true}))))

(defn remove-traefik-config [session-id]
  (let [config-file (str config-dir "/" session-id ".yml")]
    (when (.exists (io/file config-file))
      (io/delete-file config-file))))

(defn cleanup-session [session-id]
  (when-let [session (get @active-sessions session-id)]
    (try
      (p/destroy (:process session))
      (catch Exception e
        (println "Error destroying process:" (.getMessage e))))
    (remove-traefik-config session-id)
    (swap! active-sessions dissoc session-id)
    (println "Cleaned up session:" session-id)))

(defn start-wetty [command session-id timeout]
  (let [port (generate-port)
        base-path (str "/session/" session-id)
        ;; Wetty command with base path support
        process (p/process ["wetty"
                            "--port" (str port)
                            "--base" base-path
                            "--command" "bash"
                            "--login-command" (str "timeout " timeout " bash -c '" command "'")]
                           {:out :inherit :err :inherit})]
    (swap! active-sessions assoc session-id
           {:process process
            :port port
            :command command
            :created-at (System/currentTimeMillis)
            :timeout (* timeout 1000)})
    (write-traefik-config session-id port)

    ;; Auto-cleanup after timeout + buffer
    (future
      (Thread/sleep (* (+ timeout 10) 1000))
      (cleanup-session session-id))

    port))

(defn execute-handler [req]
  (try
    (let [body (json/parse-string (slurp (:body req)) true)
          command (:command body)
          timeout (or (:timeout body) 300)
          session-id (str (random-uuid))]

      (println "Starting wetty session:" session-id "command:" command)
      (start-wetty command session-id timeout)

      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:url (str "/session/" session-id)
               :session-id session-id
               :timeout timeout
               :expires-at (+ (System/currentTimeMillis) (* timeout 1000))})})
    (catch Exception e
      (println "Error in execute-handler:" (.getMessage e))
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Internal server error"})})))

(defn status-handler [req]
  (let [sessions (map (fn [[id session]]
                        {:session-id id
                         :command (:command session)
                         :port (:port session)
                         :uptime (- (System/currentTimeMillis)
                                    (:created-at session))})
                      @active-sessions)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string
            {:active-sessions (count sessions)
             :sessions sessions})}))

(defn cleanup-handler [req]
  (let [session-id (get-in req [:params :session-id])]
    (if (get @active-sessions session-id)
      (do
        (cleanup-session session-id)
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:message "Session cleaned up"})})
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Session not found"})})))

(defn home-handler [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (str "<h1>Wetty Service \ud83d\ude80</h1>"
              "<h2>API Endpoints:</h2>"
              "<ul>"
              "<li><code>POST /api/execute</code> - Start new session</li>"
              "<li><code>GET /api/status</code> - List active sessions</li>"
              "<li><code>DELETE /api/cleanup/:session-id</code> - Cleanup session</li>"
              "</ul>"
              "<h2>Example:</h2>"
              "<pre>curl -X POST http://localhost/api/execute \\\n"
              "  -H \"Content-Type: application/json\" \\\n"
              "  -d '{\"command\": \"htop\", \"timeout\": 300}'</pre>"
              "<p>Then open the returned URL in your browser for the terminal.</p>")})

(defn router [req]
  (let [uri (:uri req)
        method (:request-method req)]
    (cond
      (and (= method :get) (= uri "/"))
      (home-handler req)

      (and (= method :post) (= uri "/api/execute"))
      (execute-handler req)

      (and (= method :get) (= uri "/api/status"))
      (status-handler req)

      (and (= method :delete) (str/starts-with? uri "/api/cleanup/"))
      (let [session-id (last (str/split uri #"/"))]
        (cleanup-handler (assoc-in req [:params :session-id] session-id)))

      :else
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Not Found"})))

;; Create initial traefik config for the API
(defn create-api-config []
  (let [config {:http
                {:routers
                 {:api
                  {:rule "PathPrefix(`/api`) || Path(`/`)"
                   :service "babashka-api"}}
                 :services
                 {:babashka-api
                  {:loadBalancer
                   {:servers [{:url "http://localhost:3000"}]}}}}}
        config-file (str config-dir "/api.yml")]
    (io/make-parents config-file)
    (spit config-file (json/generate-string config {:pretty true}))))

(defn -main []
  (println "Creating API configuration...")
  (create-api-config)

  (let [port 3000]
    (println (str "Starting Babashka server on port " port "..."))
    (server/run-server router {:port port})
    (println "Server started!")
    (println "Access via: http://localhost/")
    (println "Traefik dashboard: http://localhost/dashboard/")
    @(promise)))

(-main)
