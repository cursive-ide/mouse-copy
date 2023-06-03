(ns build
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as api]
            [clojure.tools.build.util.file :as file]
            [clojure.tools.build.util.zip :as zip])
  (:import (java.io File FileOutputStream)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)
           (java.util.zip ZipOutputStream)
           (org.jetbrains.kotlin.cli.jvm K2JVMCompiler)))


(def kotlinc-opts ["-jvm-target" "17" "-no-stdlib" "-Xjvm-default=all"])


(defn clean [args]
  (api/delete {:path "out/production"}))


(defn kotlinc
  [& {:keys [basis kotlinc-opts class-dir src-dirs]}]
  (let [{:keys [libs]} basis]
    (when (seq src-dirs)
      (let [class-dir (file/ensure-dir (api/resolve-path class-dir))
            class-dir-path (.getPath class-dir)
            classpath (str/join File/pathSeparator (conj (mapcat :paths (vals libs)) class-dir-path))
            options (concat
                      src-dirs
                      ["-classpath" classpath "-d" class-dir-path]
                      kotlinc-opts)]
        (K2JVMCompiler/main (into-array String options))))))


(defn git-revision []
  (-> (api/process {:command-args ["git" "describe" "--tags" "--always" "HEAD"]
                    :dir          "."
                    :out          :capture})
      (:out)
      (str/trim)))


(defn find-plugin-version []
  (let [config (edn/read-string (slurp "deps.edn"))
        {:keys [plugin-version platform-version]} config]
    (str plugin-version \- platform-version)))

(defn update-plugin-xml [target plugin-version]
  (let [rev (git-revision)
        description (slurp "description.html")
        now (-> (LocalDateTime/now)
                (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")))
        plugin-xml (str target "/META-INF/plugin.xml")
        xml (-> plugin-xml
                (slurp)
                (str/replace #"(<version>).*(</version>)"
                             (str "$1" plugin-version "$2"))
                (str/replace #"(?s)(<description>[\r\n\s]*).*([\r\n\s]*</description>)"
                             (str "$1<![CDATA[\n"
                                  description
                                  "<p>Built on: " now "</p>\n"
                                  "<p>Built from: " rev "</p>\n"
                                  "]]>$2")))]
    (spit plugin-xml xml)
    (println "Building" plugin-version "from" rev)))


(defn build-mouse-copy [plugin-version]
  (println "Compiling mouse-copy")
  (let [target "out/production/mouse-copy"
        basis (api/create-basis {:aliases [:sdk :no-clojure]})]
    (println "Compiling Kotlin")
    (kotlinc {:src-dirs ["src/kotlin"]
              :class-dir target
              :basis basis
              :kotlinc-opts kotlinc-opts})
    (println "Building Plugin")
    (api/copy-dir {:src-dirs ["resources"]
                   :target-dir target})
    (update-plugin-xml target plugin-version)
    (api/jar {:class-dir target
              :jar-file "build/distributions/mouse-copy.jar"})))


(defn prepare-sandbox []
  (println "Preparing sandbox")
  (api/delete {:path "sandbox/plugins"})
  (let [sandbox "sandbox/plugins/mouse-copy/lib"
        basis (api/create-basis {:aliases [:no-clojure]})]
    (doseq [root (filter #(not (.isDirectory (io/file %)))
                         (:classpath-roots basis))]
      (api/copy-file {:src root
                      :target (str sandbox \/ (.getName (io/file root)))}))
    (api/copy-file {:src    "build/distributions/mouse-copy.jar"
                    :target (str sandbox \/ (.getName (io/file "mouse-copy.jar")))})))


(defn package-mouse-copy [plugin-version]
  (let [zip-file (api/resolve-path (str "build/distributions/mouse-copy-" plugin-version ".zip"))
        class-dir-file (file/ensure-dir (api/resolve-path "sandbox/plugins"))]
    (file/ensure-dir (.getParent zip-file))
    (with-open [zos (ZipOutputStream. (FileOutputStream. zip-file))]
      (zip/copy-to-zip zos class-dir-file))))


(defn package [args]
  (clean args)
  (let [plugin-version (find-plugin-version)]
    (build-mouse-copy plugin-version)
    (prepare-sandbox)
    (package-mouse-copy plugin-version)))
