(ns yetibot.api.image-charts
  (:require
    [cemerick.url :as url]
    [clj-http.client :as client]
    ))

;; (def endpoint "https://image-charts.com/chart")
(def endpoint "https://chart.googleapis.com/chart")

(defn chart
  "Generates a URL that contains a chart"
  ([required-args] (chart required-args {}))
  ([{:keys [chart-type chart-data chart-size] :as required-args}
    {:keys [chart-scaling chart-animation chart-series-colors
            chart-fill chart-line-styles chart-title chart-color-font] :as optional-args}]
   (let [query (into {} (remove (fn [[k v]] (nil? v))
                                {:cht chart-type
                                 :chd chart-data
                                 :chs chart-size
                                 :chds chart-scaling
                                 :chan chart-animation
                                 :chco chart-series-colors
                                 :chls chart-line-styles
                                 :chf chart-fill
                                 :chtt chart-title
                                 :chts chart-color-font
                                 }))
         ic-url (assoc (url/url endpoint)
                       :query query)]
     (str ic-url))))


(comment

  (chart {:chart-type "lc"
          :chart-data "t:1,2,3"
          :chart-size "800x800"}
         {:chart-scaling "a"})

  (use '[clojure.java.shell :only [sh]])

  (sh "open"
      (chart {:chart-type "lc"
              :chart-data "t:2,5,3,9,11,2,5,8"
              :chart-size "800x300"}
             {:chart-scaling "a"
              :chart-fill "bg,lg,90,FFFFFF,0,FDFDFD,1"
              :chart-series-colors "B02B35,0F1E66,55C4D4,FDDE68"
              :chart-line-styles "2"
              :chart-title "Amazing Chart"
              :chart-color-font "444444,20"
              :chart-animation "2000,easeOutQuad"}))

  )

;; curl -X GET --header 'Accept: image/gif' 'https://image-charts.com/chart?

;; cht=lc
;; chd=t%3A60%2C40%2C80%2C20%2C100%2C300%2C45%2C76%2C234%2C578%2C562%2C234%2C57%2C768%2C78%2C34%2C243
;; chds=a
;; chof=.png
;; chs=900x700
;; chdls=000000
;; chg=10%2C10
;; chco=F56991%2CFF9F80%2CFFC48C%2CD1F2A5%2CEFFAB4
;; chdlp=b
;; chf=bg%2Cs%2CFFFFFF
;; chbh=10
;; chan=2000%2CeaseInSine
;; icwt=false

;; https://image-charts.com/chart?chs=700x190&chd=t:60,40&cht=p3&chl=Hello%7CWorld&chan&chf=…
