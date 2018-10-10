# Whither Weather?
*...or "Choosing A New Weather API"*

Ref: [Issue 740](https://github.com/yetibot/yetibot/issues/740)

So far, the issues I've been exploring all relate to location
specification, and what options, if any, there are for search operations
or other strategies to disambiguate returned results.

At a quick first look, all these APIs return the basic weather data we
want.  I haven't done a careful assessment of their free plans beyond a
quick gut check of N requests/interval seeming reasonable enough for my
best guesses on typical use.


## OpenWeatherMap

[OpenWeatherMap](https://openweathermap.org/api) was the first API I
looked at, as it was suggested by @devth in #740.

The most basic set of endpoints provided are those for [Current Weather
Data](https://openweathermap.org/current).  The problem I see here is
that the API doesn't surfaces any notion of states, provinces, counties,
prefectures, etc., returning only the city name, in `name`, and the
country code in `sys.country`.

For the below examples I'm using "York", a city in Pennsylvania, US, and
England, UK (country code: GB).  "York" is also token in many city
names, like "New York", a city in NY, US; relevant later when we look at
the API's search functionality.

### Full /data/2.5/weather Response, For Reference

```
 ❯ curl -s "https://api.openweathermap.org/data/2.5/weather?APPID=$owm_key&q=york" | jq .
 {
   "coord": {
     "lon": -1.08,
     "lat": 53.96
   },
   "weather": [
     {
       "id": 800,
       "main": "Clear",
       "description": "clear sky",
       "icon": "01d"
     }
   ],
   "base": "stations",
   "main": {
     "temp": 290.66,
     "pressure": 1013,
     "humidity": 51,
     "temp_min": 289.15,
     "temp_max": 292.15
   },
   "visibility": 10000,
   "wind": {
     "speed": 5.1,
     "deg": 220
   },
   "clouds": {
     "all": 0
   },
   "dt": 1539102000,
   "sys": {
     "type": 1,
     "id": 5101,
     "message": 0.0282,
     "country": "GB",
     "sunrise": 1539066114,
     "sunset": 1539105595
   },
   "id": 2633352,
   "name": "York",
   "cod": 200
 }
```

### Complications When Trying To Qualify Locations

*We'll use a trimmed down version for API responses, for discussion
going forward.*

```
❯ curl -s "https://api.openweathermap.org/data/2.5/weather?APPID=$owm_key&q=york" | jq -c '[.id,.name,.sys.country]'
[2633352,"York","GB"]
```

An unqualified "york" returns the city in England, UK.  The problems
arises when one tries to specify the city, by state (or similar), which
is a common enough habit in the US.

Let's say you're trying to get York, PA's weather.  One is likely to try
"york, pa", or *maybe* "york, pa, us".  Unfortunately, as OWM doesn't
consider delineations other than countries, you're out of luck here.

"york, pa" will fail:
```
❯ curl -s "https://api.openweathermap.org/data/2.5/weather?APPID=$owm_key&q=york,pa" | jq .
{
  "cod": "404",
  "message": "city not found"
}
```

...and "york, pa, us" will succeed incorrectly:
```
❯ curl -s "https://api.openweathermap.org/data/2.5/weather?APPID=$owm_key&q=york,pa,us" | jq -c '[.id,.name,.sys.country]'
[2633352,"York","GB"]
```

Attempting to work around this, via the limited search, is challenging.
Note the other two "York"s in the US, and one in Australia.
```
 ❯ curl -s  "https://api.openweathermap.org/data/2.5/find?APPID=$owm_key&q=york&type=like" | jq -c '.list[] | [.id,.name,.sys.country]' | sort
 [2057277,"York","AU"]
 [2633352,"York","GB"]
 [4098776,"York","US"]
 [4562407,"York","US"]
 [4601703,"York","US"]
```

OWM's [Search Accuracy](https://openweathermap.org/current#accuracy)
says they accept partial names, but I wasn't able to get this working in
my tests.

> You can use our geocoding system to find cities by name, country,
> zip-code or geographic coordinates. You can call also by part of the
> city name. To make the result more accurate just put the city name and
> country divided by comma.

I was expecting the above query (*"find?q=york&type=like"*) to return
"New York" but it doesn't.  I also tried "New", "Yor", and "ork",
without success.


### Final Thoughts on OpenWeatherMap

There is a master file of locations that OWM makes available, but it
doesn't provide any further resolution of locations, by name.

Without further location detail of states, provinces, counties,
prefectures, etc., it didn't make sense to explore options for
disambiguation of multiple returns, as you would do when trying to find
your location for the first time.

I think it would be hard to support the existing Yetibot weather query
interface, which feels very intuitive now, with OWM's API.  My sense is
that they have designed the API to work in concert with their mapping
tools, which makes sense given the name. :)


## Weatherbit.io

[Weatherbit.io](https://www.weatherbit.io/api) was the second API I
looked at; I found it through some basic googling.

I wasn't initial that keen on Weatherbit.io as their free tier seems
intended to push you into a paid subscription, but that's solely because
of the wording on their plan pages.

Still sticking with the "york" examples.


### Full /v2.0/current Response, For Reference
```
❯ curl -s "https://api.weatherbit.io/v2.0/current?key=$wb_key&city=york" | jq .
{
  "data": [
    {
      "wind_cdir": "NNW",
      "rh": 83,
      "pod": "d",
      "lon": -88.29642,
      "pres": 991.7,
      "timezone": "America/Chicago",
      "ob_time": "2018-10-10 13:00",
      "country_code": "US",
      "clouds": 25,
      "vis": 10,
      "state_code": "AL",
      "wind_spd": 2.57,
      "lat": 32.48625,
      "wind_cdir_full": "north-northwest",
      "slp": 1007.7,
      "datetime": "2018-10-10:13",
      "ts": 1539176400,
      "station": "E7440",
      "h_angle": -60,
      "dewpt": 19.2,
      "uv": 2.60677,
      "dni": 424.237,
      "wind_dir": 341,
      "elev_angle": 12.5555,
      "ghi": 164.134,
      "dhi": 71.3908,
      "precip": null,
      "city_name": "York",
      "weather": {
        "icon": "c02d",
        "code": "801",
        "description": "Few clouds"
      },
      "sunset": "23:28",
      "temp": 22.2,
      "sunrise": "11:55",
      "app_temp": 22.7
    }
  ],
  "count": 1
}
```

### Qualify Locations

```
❯ curl -s "https://api.weatherbit.io/v2.0/current?key=$wb_key&city=york" | jq -c '.data[] | [.city_name,.state_code,.country_code]'
["York","AL","US"]
```

Again, an unqualified "york" will return something, in this case, a city
in Alabama.

(York, AL has a population of ~2400, as of the 2016 US Census.  I'm not
sure why this was selected as the default "york"; I'm testing from an IP
in New York City, NY, US.  Just an interesting observation).

Adding a state, or state and country code, works as expected.

```
❯ curl -s "https://api.weatherbit.io/v2.0/current?key=$wb_key&city=york,pa" | jq -c '.data[] | [.city_name,.state_code,.country_code]'
["York","PA","US"]

❯ curl -s "https://api.weatherbit.io/v2.0/current?key=$wb_key&city=york,pa,us" | jq -c '.data[] | [.city_name,.state_code,.country_code]'
["York","PA","US"]
```

Note: proper URL encoding of the query, including spaces and commas,
works nicely so there isn't too much preparation needed for the request,
eg: "york, pa" as "york%2C%20pa" works fine.


### Complications In Disambiguation Of Multiple Location Matches

Unfortunately, Weatherbit.io's doesn't seem to provide any sort of
search functionality, so users would have to specify enough detail, to
disambiguate, without guidance from YB.

Currently, YB will offer suggestions on which "york" you might want.

```
jereme 9:52 AM
!weather york

yetibot APP 9:52 AM
Found multiple locations: York, 10Jamaica; York, 13Jamaica; York, ABCanada; York, AL; York, ERYUnited Kingdom; York, FSSouth Africa; York, GA; York, IZGuatemala; York, ME; York, NBCanada; York, ND; York, NE; York, NLSouth Africa; York, NY; York, NYKUnited Kingdom; York, ONCanada; York, PA; York, PECanada; York, SSierra Leone; York, SC; York, WSierra Leone; York, WASAustralia; York, WV; York, YORUnited Kingdom; York, NLSouth Africa; York, WSierra Leone; York, SSierra Leone; York, YORUnited Kingdom; York, WV; York, GA; York, YORUnited Kingdom; York, ERYUnited Kingdom; York, NYKUnited Kingdom; York, PA
```

If we want to provide something similar, as a possible work around, WB
publishes [lists of locations](https://www.weatherbit.io/api/meta), in
varying sizes, by populations, which would be used to offer a local,
simple search (just a string match, really).

Here are the lists, and counts provided by WB:
- Cities >15,000 population - ~20,000 Cities
- Cities >1,000 population - ~160,000 Cities
- All Cities ~376,000

I pulled *"All Cities"*, which is currently actually 378,815 locations,
at ~18 MiB of uncompressed text, without much white space.

I'm not sure how often this data set changes (it has skewed little since
*/api/meta* was published).  My guess is this is sufficiently rare that
shipping the current version with each YB release wouldn't be untenable.

With the data set stored locally, we could add our own simple search and
disambiguation feature... but that also means shipping a big blob of EDN
or similar, which is a bit gnarly.  We *could* retrieve on demand but
that seems inefficient to me, and overly complicated.


## Final Thoughts On Weatherbit.io

I wound up liking this API for its qualified locations and simple
request formatting requirements.  Unfortunately, unless we want to build
our own search, users will not be offered suggestions to help further
qualify locations.  That might not be a problems... it's just notably
different than the current interface.


# US National Weather Service API

[NWS](https://www.weather.gov/documentation/services-web-api), part of
NOAA, offers a rich API.  I didn't dig into this as it's a *beast* that
tries to support many different use cases via decomposed responses you
stitch together by traversing [JSON-LD](https://json-ld.org/) links.


> Why does the API require multiple requests for all the information?
> 
> There are many uses for the weather information provided by the API,
> and, historically, the service responded with everything but the
> kitchen sink. This design bloated bandwidth and make caching efforts
> difficult. One goal of the new API was a design that allowed repeat
> users of specific data the ability to access only the information
> needed. Another goal was to expire content based upon the information
> life cycle. The new approach using JSON-LD achieves both of these
> goals. While this requires additional requests, future enhancements,
> especially HTTP2, will make this design more efficient than a
> catch-all approach.


# Next Steps

There are surely more APIs I could research, or things I'm missing about
the two I looked at.  I could devote more time there, or we could make a
call based on the above.

The actual integration work is pretty trivial, regardless of which API
we go with; I have a working WB impl now.
