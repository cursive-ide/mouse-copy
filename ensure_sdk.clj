(ns ensure-sdk
  (:refer-clojure :exclude [ensure])
  (:require [babashka.curl :as curl]
            [babashka.fs :as fs]
            [borkdude.rewrite-edn :as rewrite]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn zipfile [version]
  (str "sdks/ideaIC-" version ".zip"))

(defn sources-file [version]
  (io/file (str "sdks/ideaIC-" version "-sources.jar")))

(defn sdk-url [repo version]
  (str "https://www.jetbrains.com/intellij-repository/"
       repo
       "/com/jetbrains/intellij/idea/ideaIC/"
       version
       "/ideaIC-"
       version
       ".zip"))

(defn download-sdk [version]
  (let [url (sdk-url "releases" version)]
    (when-not (fs/exists? "sdks")
      (fs/create-dir "sdks"))
    (when-not (fs/exists? (zipfile version))
      (println "Downloading" url)
      (let [[resp repo]
            (let [resp (curl/get url {:as :stream :throw false})]
              (if (= 200 (:status resp))
                [resp "releases"]
                [(let [url (sdk-url "snapshots" version)]
                   (println "Not found (response" (:status resp) "), downloading" url)
                   (curl/get url {:as :stream :throw false}))
                 "snapshots"]))]
        (if (not= 200 (:status resp))
          (throw (ex-info "Problem downloading SDK" resp)))
        (io/copy (:body resp) (io/file (zipfile version)))
        @(:exit resp)

        (println "Unzipping SDK")
        (let [sdk (str "sdks/" version)
              ret (sh "/usr/bin/unzip" (zipfile version) "-d" sdk)]
          (if (not= 0 (:exit ret))
            (throw (ex-info "Problem unzipping" ret))))

        (when-not (fs/exists? (sources-file version))
          (let [url (str "https://www.jetbrains.com/intellij-repository/"
                         repo
                         "/com/jetbrains/intellij/idea/ideaIC/"
                         version
                         "/ideaIC-"
                         version
                         "-sources.jar")
                _ (println "Downloading" url)
                resp (curl/get url {:as :stream :throw false})]
            (if (not= 200 (:status resp))
              (throw (ex-info "Problem downloading sources" resp)))
            (io/copy (:body resp) (sources-file version))
            @(:exit resp)))))))

(defn update-deps-edn [file-name version prefix]
  (let [cursive-deps-string (slurp file-name)
        nodes (rewrite/parse-string cursive-deps-string)
        edn (edn/read-string cursive-deps-string)
        nodes (reduce (fn [nodes alias]
                        (let [keys (filter #(#{"intellij" "plugin"} (namespace %))
                                           (keys (get-in edn [:aliases alias :extra-deps])))]
                          (reduce (fn [nodes key]
                                    (let [target [:aliases alias :extra-deps key :local/root]]
                                      (cond
                                        (.endsWith (name key) "$sources")
                                        (rewrite/assoc-in nodes target
                                                          (str prefix "sdks/ideaIC-" version "-sources.jar"))
                                        (= "intellij" (namespace key))
                                        (rewrite/assoc-in nodes target
                                                          (str prefix "sdks/" version))
                                        (= "plugin" (namespace key))
                                        (let [previous (get-in edn target)
                                              file (io/file previous)
                                              name (.getName file)]
                                          (rewrite/assoc-in nodes target
                                                            (str prefix "sdks/" version "/plugins/" name)))
                                        :else nodes)))
                                  nodes
                                  keys)))
                      nodes
                      [:sdk :ide])]
    (spit file-name (str nodes))))

(defn ensure [args]
  (try
    (let [config (edn/read-string (slurp "deps.edn"))
          version (:idea-version config)
          sdk (str "sdks/" version)]

      ; Download SDK if not present
      (when-not (fs/exists? sdk)
        (download-sdk version)

        ; Generate deps.edn files for the SDK itself and for each plugin
        (let [jars (->> (fs/glob sdk "lib/**.jar")
                        ; Remove annotations jar due to weird version conflict
                        (remove #(= (fs/file-name %) "annotations.jar"))
                        (map #(fs/relativize sdk %))
                        (mapv str))]
          (spit (str sdk "/deps.edn") (pr-str {:paths jars}))
          (let [plugins (fs/glob sdk "plugins/*")]
            (doseq [plugin plugins]
              (let [jars (->> (fs/glob plugin "lib/**.jar")
                              ; Remove JPS plugins due to another weird version conflict in Kotlin
                              (remove #(str/includes? (fs/file-name %) "jps-plugin"))
                              (map #(fs/relativize plugin %))
                              (mapv str))]
                (spit (str plugin "/deps.edn") (pr-str {:paths jars})))))))

      ; Now update deps.edn file
      (update-deps-edn "deps.edn" version ""))
    (catch Exception e
      (println (str "Error: "
                    (.getMessage e)
                    (when-let [data (ex-data e)]
                      (str ", data: " (pr-str data))))))))
