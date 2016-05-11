# timbre-riemann

Send [Timbre](https://github.com/ptaoussanis/timbre) output to a
[Riemann](http://riemann.io/) server.

## Usage

The function `riemann-appender` returns a Timbre appender, which
will send each log message to a Riemann server.

```clojure
(timbre-riemann/riemann-appender opts)
```

The argument `opts` may be a map of options, or nil, in which case
events are send to `localhost:5555` via TCP. For details about the
available options see the docstring of `riemann-appender`.

The returned appender is `enabled?`, not `async?`, has no `min-level`
and no `rate-limit` set. The `output-fn` setting is not used.

## License

Copyright © 2016 Active Group GmbH

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
