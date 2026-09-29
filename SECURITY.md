# Security Policy

## Supported Versions

Only the latest release and recent release candidates receive security patches:

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |
| < 0.1.0 | :x:                |

---

## Reporting a Vulnerability

The Ariadne team takes security vulnerabilities seriously. We appreciate your efforts to responsibly disclose findings.

### Private Reporting Process
If you discover a security vulnerability or sensitive flaw within Ariadne:
1. **Do not disclose the issue publicly** in GitHub Issues, Discussions, or social media.
2. Send an encrypted or direct email detailing the vulnerability to:
   - **Email:** `afonsecapaiva@gmail.com` (or through GitHub Private Vulnerability Reporting on this repository).
3. Include the following details to assist our team in reproducing and validating the issue:
   - Component affected (`ariadne-core`, `ariadne-agent`, `ariadne-adapter-*`)
   - Complete description of the potential vulnerability
   - Proof of concept (PoC) code or reproduction instructions
   - Impact assessment (e.g., sensitive context leakage, heap exhaustion, unhandled exception in advice)

### Response Timeline
- **Initial Acknowledgement:** Within 48 hours of receipt.
- **Triage & Assessment:** Within 5 business days.
- **Fix & Patch Release:** Coordinated security patch released prior to public disclosure.

---

## Built-In Security Safeguards

Ariadne is designed specifically for mission-critical enterprise production environments:

1. **Total Fail-Safe Architecture:**
   - Bytecode advice and reconstructor operations are strictly wrapped in fail-safe try-catch handlers.
   - Any runtime failure inside Ariadne is safely swallowed, ensuring user business logic is never broken.

2. **Data Leak Prevention (MDC Allowlist):**
   - By default, raw MDC payloads are not embedded into exception messages unless explicitly allowed via `-Dariadne.mdc.allowlist` or when `MDC_PROPAGATION` is explicitly enabled.
   - Prevents sensitive tokens, session IDs, or PII from leaking into logs or exception traces.

3. **Memory Exhaustion Safeguards:**
   - Call site registrations are bounded (`-Dariadne.registry.max_sites=65536`) to protect JVM heaps against malicious or runaway dynamic class generation loops.
   - `CallSiteMetadata` stores only primitive values and String descriptors, holding zero Class or ClassLoader references to avoid ClassLoader leaks upon container redeployment.
