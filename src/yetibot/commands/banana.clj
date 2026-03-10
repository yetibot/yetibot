(ns yetibot.commands.banana
  (:require [taoensso.timbre :refer [info error]]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.util.gemini :as gemini]
            [yetibot.webapp.routes.images :refer [store-image!]]))

(defn banana-cmd
  "banana <prompt> # generate an image using Gemini nano banana image generation"
  {:yb/cat #{:img}}
  [{:keys [match]}]
  (if (gemini/configured?)
    (try
      (info "banana: generating image for prompt:" match)
      (let [image (gemini/generate-image
                   (str "Generate an image: " match))
            id (store-image! image)
            base-url (gemini/yetibot-base-url)
            image-url (format "%s/generated-images/%s.png" base-url id)]
        (info "banana: image generated successfully, serving at" image-url)
        {:result/value image-url
         :result/data {:id id :prompt match :url image-url}})
      (catch Exception e
        (error "banana: Gemini image generation error:" (.getMessage e))
        {:result/error (str "Image generation failed: " (.getMessage e))}))
    {:result/error
     "Gemini API is not configured. Set `gemini.api.key` in config."}))

(cmd-hook #"banana"
  #".+" banana-cmd)
