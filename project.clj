(defproject tesserae "1.0.0"
  :description "Tesserae"
  :url "https://github.com/Workiva/tesserae"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.logging "0.4.1"]]
  :plugins [[lein-codox "0.10.4"]]
  
  :source-paths ["src"]
  :test-paths ["test"]
  :java-source-paths ["java-src"]

  :aliases {"docs" ["do" "clean-docs," "codox"]
            "clean-docs" ["shell" "rm" "-rf" "./documentation"]}
  
  :codox {:output-path "docs"
          :namespaces [tesserae.core]}
  :profiles {:dev [{:dependencies [[criterium "0.4.3"]]}]})
