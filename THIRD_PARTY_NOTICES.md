# Third-Party Notices

PurpleBear includes third-party open source components. Each component remains
under its own license.

## mihomo

- Project: MetaCubeX/mihomo
- Version: v1.19.34
- License: GNU General Public License v3.0 only
- Source: https://github.com/MetaCubeX/mihomo/tree/v1.19.34
- Release asset: `mihomo-android-arm64-v8-v1.19.34.gz`
- Release asset SHA-256: `48a71fc6a68e2909587176e0ab7185d8f2fac298fa96ccdacf30ea86ab2359b5`
- Packaged binary path: `app/src/main/jniLibs/arm64-v8a/libmihomo.so`
- Packaged binary SHA-256: `1369bebba2b7a6fe971fe6ece0cb5aa18c81e49c89dd8340f43e5e7583d3da36`

PurpleBear uses mihomo as an independent sidecar process for SSR /
ShadowsocksR compatibility. PurpleBear communicates with it through a local
SOCKS/Mixed proxy port.

The GPL-3.0 license text is included in:

- `LICENSES/GPL-3.0.txt`
- `app/src/main/assets/licenses/GPL-3.0.txt`

No local modifications were made to the mihomo source code for this packaged
binary. The binary was downloaded from the upstream GitHub release listed
above.

## Xray

- Project: XTLS/Xray-core
- Binding project: XTLS/libXray
- libXray version: v26.11.9 (`5a0f7d8d1c28b34afb5727b04b26a10f6dbac7e3`)
- Xray-core version: v26.11.9 (`676eaf66f8f0`)
- libXray license: MIT
- Xray-core license: Mozilla Public License 2.0
- Binding source: https://github.com/XTLS/libXray/tree/v26.11.9
- Source: https://github.com/XTLS/Xray-core/tree/v26.11.9
- Packaged AAR path: `app/libs/libXray.aar`
- Packaged AAR SHA-256: `be43fe74dadcde0a1e85df00f15a4e3490cc1bc8786e57e79583a4f3e7f2ecc6`
- Local patches: `xray-patches/` (applied to Xray-core, see below)

PurpleBear uses Xray as its main proxy core. See the upstream project for
source code and license details. The packaged AAR is built from the upstream
tags above plus two small Xray-core patches; together they are the complete
source change:

- `xray-patches/0001-process-lookup-original-target.patch`
  (`app/router/condition.go`): when sniffing has replaced a connection's
  destination with a domain, the `process` routing rule falls back to the
  original destination IP so Android can look up which app owns the
  connection (used for per-app nodes).
- `xray-patches/0002-direct-keeps-app-address.patch`
  (`proxy/freedom/freedom.go`): a sniffed connection that goes direct dials
  the IP address the app chose instead of looking the domain up again, so
  apps that pick a specific server (for example WeChat international's
  overseas image servers) reach it.

To reproduce the AAR, run `xray-patches/build-libxray.sh` (arm64-v8a only,
Android API 26, NDK 29.0.14206865, Go 1.27.4 and `golang.org/x/mobile` at
`197770444867` via `gomobile bind -trimpath`, with 16 KiB ELF load-segment
alignment).

## Tailscale

- Project: Tailscale
- Version: v1.104.7
- License: BSD 3-Clause
- Source: https://github.com/tailscale/tailscale/tree/v1.104.7
- Packaged bridge paths:
  - `app/src/main/jniLibs/arm64-v8a/libtailscalebridge.so`
  - `app/src/main/jniLibs/arm64-v8a/libtailscalebridgejni.so`
- Local bridge source: `tailscale-bridge/`

PurpleBear embeds a Tailscale userspace node and exposes its authenticated
loopback SOCKS5 listener to Xray. It does not embed Tailscale's Android VPN
service, so Xray remains the app's single Android `VpnService` and can coexist
with Tailnet routing. The BSD 3-Clause license text is included in:

- `LICENSES/Tailscale-BSD-3-Clause.txt`
- `app/src/main/assets/licenses/Tailscale-BSD-3-Clause.txt`

## Backdrop (Liquid Glass)

- Project: Kyant0/AndroidLiquidGlass, artifact `io.github.kyant0:backdrop`
- Version: 2.0.1
- License: Apache License 2.0
- Source: https://github.com/Kyant0/AndroidLiquidGlass

The bottom tab bar uses this library for backdrop blur and lens refraction.
`app/src/main/java/com/mallocgfw/app/ui/LiquidGlassTabBar.kt` is adapted from
the catalog sample (`LiquidBottomTabs`, `DampedDragAnimation`,
`InteractiveHighlight`, `inspectDragGestures`), Copyright Kyant0, used under
the Apache License 2.0.

## Geo data

- geoip.dat: v2fly/geoip `202611080027`
  (SHA-256 `eedb24b3b9fcba27e06b92c0428dab90d9496083091ae4cf39e11387fd99d0e2`)
- geosite.dat: v2fly/domain-list-community `20261118141523` (`dlc.dat`)
  (SHA-256 `a033da583af17f15c9743f0b29aa5c073e9c1b8018a99ab4e69bc9ed5248a086`)
- Packaged path: `app/src/main/assets/xray/arm64-v8a/`
