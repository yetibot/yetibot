(ns yetibot.commands.no
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]))

(def no-gifs
  ["http://www.reactiongifs.com/wp-content/gallery/no/snobby-no.gif"
   "http://i.imgur.com/GF2CoJw.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/jonah-hill-no.gif"
   "http://1.media.todaysbigthing.cvcdn.com/97/82/204166a71f807df627c8e337674c58a6.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/John-Krasinski-no.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/aint-nobody.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/754.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/no-bird.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/Julie-White.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/Gosling-No.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/fast-no.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/um-no.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/tyra_unsure.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/the_dude_nope.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/no.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/hell-no.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/Dumbledore.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/cowboy-shaking-head-no.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/no_way.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/Gladiator_Thumb_Down_01.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/AQV9L.gif"
   "http://media.giphy.com/media/UlSz37vyqzTvq/giphy.gif"
   "http://www.reactiongifs.com/wp-content/gallery/no/obama_not_amused.gif"
   "http://media3.giphy.com/media/108QDeRLo4w5uE/200.gif"
   "http://media.giphy.com/media/aSFcYSXw7p0DC/giphy.gif"
   "http://media1.giphy.com/media/OJ86LQa3vaUyA/200.gif"
   "http://media.giphy.com/media/Ahs5u58Qud2PC/giphy.gif"
   "http://media.giphy.com/media/ry6BrbbLysbC/giphy.gif"
   "http://media.giphy.com/media/OJcRLchr3bT5S/giphy.gif"
   "https://media1.giphy.com/media/15aGGXfSlat2dP6ohs/giphy.gif"
   "https://media1.giphy.com/media/6Q2KA5ly49368/giphy.gif"
   "https://media3.giphy.com/media/gnE4FFhtFoLKM/giphy.gif"
   "https://media2.giphy.com/media/AmDzMmCJZABsk/giphy.gif"
   "https://media1.giphy.com/media/EVbEdEW3kuu0o/giphy.gif"
   "https://media3.giphy.com/media/PRH7kZqQsHTSE/giphy.gif"
   "https://media0.giphy.com/media/T5QOxf0IRjzYQ/giphy.gif"
   "https://media3.giphy.com/media/TdmTcdoN3egaQ/giphy.gif"
   "https://media3.giphy.com/media/77xrxjer0slgc/giphy.gif"
   "https://media0.giphy.com/media/yBXuEb8lC1mqA/giphy.gif"
   "https://media2.giphy.com/media/lJ6O1tEcJUfYI/giphy.gif"
   "https://media3.giphy.com/media/l0MYKMSOdFabmEDQc/giphy.gif"
   "https://media0.giphy.com/media/11tVh1XRNAunYI/giphy.gif"
   "https://media0.giphy.com/media/LZQsVAzgB6sE0/giphy.gif"
   "https://media3.giphy.com/media/l1BgRBd4cVC8OaZKo/giphy.gif"
   "https://media0.giphy.com/media/xT0xezwKyz4VXZlBGU/giphy.gif"
   "https://media0.giphy.com/media/MTGCHXzeiMcMg/giphy.gif"
   "https://media1.giphy.com/media/l41Ym7ql1UKk58KC4/giphy.gif"])

(defn no-cmd
  "no # show your dissaproval"
  {:yb/cat #{:fun :img}}
  [_] (rand-nth no-gifs))

(cmd-hook #"no"
          _ no-cmd)
