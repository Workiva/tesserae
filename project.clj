(defproject com.workiva/tesserae "1.0.0"
  :description "An abstraction over promises, futures, delays, etc."
  :url "https://github.com/Workiva/tesserae"

  :license {:name "Eclipse Public License 1.0"}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.logging "0.4.1"]]

  :plugins [[lein-cljfmt "0.6.4"]
            [lein-codox "0.10.4"]
            [lein-shell "0.5.0"]]

  :deploy-repositories {"clojars"
                        {:url "https://repo.clojars.org"
                         :username :env/clojars_username
                         :password :env/clojars_password
                         :sign-releases false}}

  :source-paths ["src"]
  :test-paths ["test"]
  :java-source-paths ["java-src"]

  :aliases {"docs" ["do" "clean-docs," "with-profile" "docs" "codox," "java-docs"]
            "clean-docs" ["shell" "rm" "-rf" "./documentation"]
            "java-docs" ["shell" "javadoc" "-d" "./documentation/java" "-notimestamp"
                         "./java-src/tesserae/CancellationExceptionInfo.java"]}

  :codox {:namespaces [tesserae.core]
          :themes [:rdash]
          :html {:transforms [[:title]
                              [:substitute [:title "Tesserae API Docs"]]
                              [:span.project-version]
                              [:substitute nil]
                              [:pre.deps]
                              [:substitute [:a {:href "https://clojars.org/com.workiva/tesserae"}
                                            [:img {:src "https://img.shields.io/clojars/v/com.workiva/tesserae.svg"}]]]]}
          :output-path "documentation/clojure"}

  :profiles {:dev [{:dependencies [[criterium "0.4.3"]]}]
             :docs {:dependencies [[codox-theme-rdash "0.1.2"]]}})
