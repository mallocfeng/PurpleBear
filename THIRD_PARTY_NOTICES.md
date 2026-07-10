# Third-Party Notices

PurpleBear includes third-party open source components. Each component remains
under its own license.

## mihomo

- Project: MetaCubeX/mihomo
- Version: v1.19.24
- License: GNU General Public License v3.0 only
- Source: https://github.com/MetaCubeX/mihomo/tree/v1.19.24
- Release asset: `mihomo-android-arm64-v8-v1.19.24.gz`
- Release asset SHA-256: `e45a0b18ea3554cf7f322b59cf2dd21f3e4879dd2db657cbba6a557235e33115`
- Packaged binary path: `app/src/main/jniLibs/arm64-v8a/libmihomo.so`
- Packaged binary SHA-256: `5915d69c8440267158d7b9fbcb4089d522a3d66e86482dd121200e614c1af68f`

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
- Source: https://github.com/XTLS/Xray-core

PurpleBear uses Xray as its main proxy core. See the upstream project for
source code and license details.
