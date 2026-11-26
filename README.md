# Dual VPN Project

The Android app bundles the official arm64-v8a sing-box libbox gomobile binding
at `app/libs/libbox.aar`. It was built by the upstream SagerNet/sing-box Android
library workflow from commit
[`3e6a6578702ad02d6911ac49abf3ee70fa774e66`](https://github.com/SagerNet/sing-box/commit/3e6a6578702ad02d6911ac49abf3ee70fa774e66).
The artifact's SHA-256 is
`e37e0cdf93e9653b73d42fedf746987922a3367f77d78ea6e457478edbda8a15`.

The bundled sing-box library is licensed under GPL-3.0-or-later; see
[`app/libs/LICENSE-sing-box`](app/libs/LICENSE-sing-box).

For manually entered proxy links, DNS uses the underlying network rather than
the configured proxy. This lets DNS resolve independently when a manual proxy
is unavailable; the app reports that proxy reachability is not verified when
the tunnel starts.
