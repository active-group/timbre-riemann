(defproject timbre-riemann "0.1.0-SNAPSHOT"
  :description "A timbre appender to send events to a Riemann server."
  :url "http://github.com/active-group/timbre-riemann"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.taoensso/timbre "4.3.1"]
                 [riemann-clojure-client "0.4.1"]])
