(defproject jepsen.pegasus "0.1.0-SNAPSHOT"
  :description "Jepsen testing for Pegasus"
  :url "https://github.com/XiaoMi/jepsen"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [jepsen "0.1.12"]
                 [com.xiaomi.infra/pegasus-client "1.11.4-thrift-0.11.0-inlined" :exclusions [org.slf4j/slf4j-api]]]
  :repositories {"local" "/home/wutao1/.m2/repository/"}
  :plugins [[nightlight/lein-nightlight "1.6.5" :exclusions [org.clojure/clojure]]
            [lein-cljfmt "0.6.4"]]
  :main pegasus.core
  :profiles {:uberjar {:aot :all}})
