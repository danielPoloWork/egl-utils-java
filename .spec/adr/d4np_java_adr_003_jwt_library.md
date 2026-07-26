# ADR-003: JWT — Nimbus JOSE+JWT selected for `JwtTokenProvider`

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-07-14 |
| **Related spec** | [d4np-java.md](../d4np-java.md) (§2 item 11, §4) |

## Context
v1 specified HS256/RS256 JWT support without choosing an implementation — but the implementation *is* the security posture: JWT libraries differ exactly in the failure modes that matter (algorithm confusion, `alg=none`, key-type validation). Candidates: Nimbus JOSE+JWT, JJWT, Auth0 java-jwt. Constraints: `jakarta` era compatibility (spec §1.1), minimal transitive surface (ADR-001), RS256 with JWK-based key resolution for realistic enterprise SSO scenarios.

## Options considered

**A. Nimbus JOSE+JWT** *(chosen)*
- ✅ Most complete JOSE coverage in the ecosystem (JWS/JWE/JWK, key resolution via `JWKSource` — remote JWKS with caching is first-class), which is precisely what RS256 against an IdP requires.
- ✅ Minimal transitive footprint (no Jackson requirement — ships its own minimal JSON handling), aligning with the ADR-001 dependency budget for `d4np-security`.
- ✅ De facto standard underneath Spring Security's own JWT support — behavior stays consistent with what host applications already run.
- ❌ Lower-level API than JJWT's fluent builder; the provider wraps it, which is the component's job anyway.

**B. JJWT**
- ✅ Excellent fluent API and documentation; strict-by-default parsing.
- ❌ JWK/JWKS remote key resolution is weaker than Nimbus for the RS256-against-IdP case; pluggable JSON backend adds a packaging decision Nimbus avoids.

**C. Auth0 java-jwt**
- ✅ Simple, popular.
- ❌ JWS-only focus, historically slower JOSE coverage; JWKS handling lives in a second library (`jwks-rsa`) — two dependencies for one feature.

## Decision
**Option A.** `JwtTokenProvider` wraps Nimbus with a hardened profile: explicit **algorithm allowlist** per key (HS256 *or* RS256, never both on one verifier — the algorithm-confusion defense), `alg=none` structurally impossible, mandatory `exp` verification with configurable clock skew (default 60 s), `typ`/`aud`/`iss` checks on by default, JWKS caching with rate-limited refresh for RS256.

## Consequences
- The hardened profile is the public contract; raw Nimbus types do not leak from the API, so a future library swap is an implementation change plus this ADR's supersession.
- RFC 7515/7519 test vectors plus negative tests (alg-confusion, `none`, expired, wrong-audience) live in the `d4np-security` suite (spec §8).
- HS256 secrets shorter than 256 bits are rejected at provider construction — misconfiguration fails fast rather than weakening tokens silently.
