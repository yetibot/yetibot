# Choosing A New Weather API 

Ref: [Issue 740](https://github.com/yetibot/yetibot/issues/740)

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

There is a master file of locations that OWM makes available, but it
doesn't provide any further resolution of locations, by name.


### Final Thoughts on OpenWeatherMap

I think it would be hard to support the existing Yetibot weather query
interface, which feels very intuitive now, with OWM's API.  My sense is
that they have designed the API to work in concert with their mapping
tools, which makes sense given the name. :)


## Weatherbit.io

TODO: have to help with the baby and then get to work :)

Rough notes so far (please pardon the org-mode formatting)

```
** Weatherbit.io

*** Basic response reference
#+BEGIN_SRC

❯ curl -s "https://api.weatherbit.io/v2.0/current?key=$wb_key&city=york%2C%20pa" | jq 
{
  "data": [
    {
      "wind_cdir": "S",
      "rh": 82,
      "pod": "d",
      "lon": -76.72774,
      "pres": 1000,
      "timezone": "America/New_York",
      "ob_time": "2018-10-09 16:48",
      "country_code": "US",
      "clouds": 75,
      "vis": 10,
      "state_code": "PA",
      "wind_spd": 0.89,
      "lat": 39.9626,
      "wind_cdir_full": "south",
      "slp": 1025.6,
      "datetime": "2018-10-09:16",
      "ts": 1539103680,
      "station": "E7738",
      "h_angle": -15,
      "dewpt": 20.7,
      "uv": 3.38177,
      "dni": 874.929,
      "wind_dir": 169,
      "elev_angle": 41.8113,
      "ghi": 682.091,
      "dhi": 98.5849,
      "precip": null,
      "city_name": "York",
      "weather": {
        "icon": "c03d",
        "code": "803",
        "description": "Broken clouds"
      },
      "sunset": "22:36",
      "temp": 23.9,
      "sunrise": "11:11",
      "app_temp": 24.5
    }
  ],
  "count": 1
}

#+END_SRC

*** Short version for analysis
#+BEGIN_SRC
❯ curl -s "https://api.weatherbit.io/v2.0/current?key=$wb_key&city=york%2C%20pa" | jq -c '.data[] | [.city_name,.state_code,.country_code]'
["York","PA","US"]

#+END_SRC

*** Qualified guesses
#+BEGIN_SRC
❯ curl -s "https://api.weatherbit.io/v2.0/current?key=$wb_key&city=york" | jq -c '.data[] | [.city_name,.state_code,.country_code]'
["York","AL","US"]

❯ curl -s "https://api.weatherbit.io/v2.0/current?key=$wb_key&city=york,gb" | jq -c '.data[] | [.city_name,.state_code,.country_code]'
["York","ENG","GB"]

❯ curl -s "https://api.weatherbit.io/v2.0/current?key=$wb_key&city=york,eng,gb" | jq -c '.data[] | [.city_name,.state_code,.country_code]'
["York","ENG","GB"]

#+END_SRC

```

