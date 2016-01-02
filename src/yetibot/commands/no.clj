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
   "http://www.reactiongifs.com/wp-content/gallery/no/987.gif"
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
   ])

(defn no-cmd
  "no # show your dissaproval"
  {:yb/cat #{:fun :img}}
  [_] (rand-nth no-gifs))

(cmd-hook #"no"
          _ no-cmd)
