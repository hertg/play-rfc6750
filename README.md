# play-rfc6750

An example project, showcasing how to extract an OAuth2 Bearer 
Token ([RFC 6750](https://datatracker.ietf.org/doc/html/rfc6750))
from an incoming request inside a Play Framework [Filter](https://www.playframework.com/documentation/3.0.x/Filters).
This includes reading it from an `application/x-www-form-urlencoded` encoded
request body, which is not easily accessible from within a Play Filter.

The implementation uses an Essential Action and ~~Akka~~ Pekko Streams
to prevent duplicate body parsing and achieve a highly performant solution.
