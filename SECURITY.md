# Security Policy

## Supported Versions

Security fixes are handled for the latest public release of PurpleBear.

| Version | Supported |
| --- | --- |
| Latest release | Yes |
| Older releases | Best effort |

## Reporting a Vulnerability

Please do not open a public issue for security vulnerabilities.

Use GitHub private vulnerability reporting if it is available for this repository. If it is not available, contact the maintainer through the GitHub profile and include only the minimum information needed to start coordination.

Please include:

- Affected version or commit.
- Android version and device model, if relevant.
- Steps to reproduce.
- Expected impact.
- Whether the issue exposes traffic, credentials, subscription URLs, local files, logs, or other private data.

## Sensitive Data

Do not post real subscription URLs, node credentials, private keys, account tokens, or full logs containing personal data in public issues, pull requests, or discussions.

## Scope

Reports in scope include:

- Leaks of subscription URLs, node credentials, tokens, or private configuration.
- VPN routing behavior that unintentionally bypasses expected protection.
- Issues that expose private logs, DNS queries, or traffic metadata.
- Unsafe update, import, QR scanning, or file handling behavior.

Reports generally out of scope:

- General support requests.
- Issues caused by third-party nodes, subscriptions, or server configuration.
- Requests to bypass third-party service rules or restrictions.
