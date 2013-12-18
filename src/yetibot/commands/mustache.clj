(ns yetibot.commands.mustache
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]))

(def facts
  ["Women are attracted to men with Moustaches"
   "In 1967, The Beatles gave away cardboard mustaches with their album Sgt. Pepper’s Lonely Hearts Club Band."
   "Mo’s make you look stronger and will scare off an opponent. Beards are for the weak and lame."
   "Gentleman have always worn Mo’s. The term barbarian was applied to men who wore beards, because they were a lesser type of man."
   "A Mo will make you richer, beards are for beggers."
   "In a deck of cards the king of hearts is the only king without a moustache."
   "A survey of 100,000 women in 1988 found that 68% confirmed that a man with a moustache was a superior lover than his clean lipped neighbor"
   "Mo’s will make you smart (think Einstein)"
   "Scientific research, commissioned by the Guinness Brewing Company, found that the average mustachioed Guinness drinker traps a pint and a half of the creamy nectar every year. Now that Guinness is UK 2.10 a pint, this is the equivalent of an annual moustache tax of UK 4.58."
   "There are between 10,000 and 20,000 hairs on a man’s face."
   "The only 4 U.S. Presidents known for baring mustaches are: Chester A. Arthur, Grover Cleveland, Theodore Roosevelt, and William H. Taft. Since Taft in 1909, all U.S. Presidents have been clean shaven."
   "The mustache has a wonderfully powerful effect upon a man’s whole expression. The idea of virility, spirit, and manliness that it conveys is so great that it was a long time the special privilege of officers of the army to wear it. -Mrs. C. E. Humphry (Etiquette for Every Day, 1904)"
   "Groucho Marx for many years wore a fake moustache of greasepaint on stage and film, then grew a real one later in life."
   "On average a man with a moustache touches it 760 time in a 24 hr day."
   "By 1914 and the advent of WWI, the military mustache was well-established. Generally, the shape of the stache suggested rank: As a man advanced in rank, so did his mustache become thicker and bushier, until he ultimately was permitted to wear an ever fuller beard."
   "In the U.S. today, there are some ten million millennial men thought to be wearing mustaches."
   "The owner of the Oakland A’s baseball team paid each of his players $300 to grow a stache in 1971. Not surprisingly, when the A’s met the clean cut Reds in the 1972 World Series, it was dubbed the “Hair versus Square” Series by the media."
   "According to the Guinness Book of World Records, in July 1993, Kalyan Ramji Sain of Sundargarth, India, had a mustache that measured 133.4 inches long."
   "A U.S. Marine’s mustache cannot be longer than half an inch."
   "Fireman are not allowed to have facial hair because it prevents breathing equipment from fitting properly."
   "It perplexes. It fascinates. It amuses and it repulses. Glorius is the mustache!"
   "The typical mustached man touches his upper lip an average of 519 times in a 24 hour period."
   "The word “mustache” comes from the Latin root mustacium which means “conveniently stored supply of extra food.” "
   "The mustaches of Scottish Highlanders are so lush and thick that they can be clipped and used as toupees by balding gingers worldwide."
   "In Victorian England practitioners of frottage were punished by being hanged by their mustache. Then, someone figured out it was easier to do it with a rope."
   "One of the original Baskin Robbins flavors was mustachio. Which was the flavor of pistachio after it had been aged in a mustache for an entire day. It was dropped because children were disturbed by the tiny hairs in it "
   "The first sentence Carol Lombard uttered to Clark Gable was, “Can I smell your mustache?” Which caused him to instantly fall in love. "
   "Martha Stewart has a treasured pot holder woven entirely from the mustache hair of her beloved maternal grandfather. She has absolutely no burn scars on her hands, which she attributes entirely to the thickness of her “Poppy’s” facial hair."
   "It is physically impossible to force feed someone with a mustache"
   "All Harvard graduates have worn a mustache at one time or another"
   "There are no blonde mustaches, only very light shades of brown and black"
   "A mustache can qualify you for a tax abatement in New York’s East Village"
   "Mustache hair is actually fiber optic cable that can light up in different colors to celebrate any holiday"
   "That’s not food stuck in it, it’s the mustache eating"
   "Hair follicles on the upper lip are measured in calibers like firearms"
   "Regardless of who is wearing it, the mustache itself is against immigration"
   "When threatened, the mustache will raise it’s bristles like a porcupine"
   "All mustaches smell like cedar chips"
   "The world’s longest moustache is believed to be an 11ft 6in monster belonging to Ram Singh Chauhan of Rajastan, India, who regularly massages it with mustard and coconut oil to keep it healthy."
   "According to research by Guinness, the average moustached Guinness drinker traps a pint and a half of the drink in his facial hair every year."
   ])

(defn mst-fact
  "mustachefact # show a random mustache fact"
  [_] (rand-nth facts))

(cmd-hook #"mustachefact"
          _ mst-fact)
