<!--
Thank you for contributing to stock_broker!
Please fill out the checklist below. For more details, see docs/13-DEVELOPMENT-GUIDE.md.
-->

## Description
<!-- Brief description of what this PR does and why -->

## Type of Change
<!-- Check all that apply -->
- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update
- [ ] Refactoring / Code quality
- [ ] Performance improvement
- [ ] CI / Build / Infrastructure
- [ ] Security fix

## Related Issues
<!-- Link any related issues, e.g. "Fixes #123", "Related to #456" -->

---

## Definition of Done (docs/13-DEVELOPMENT-GUIDE.md section 5)
<!-- All must be checked for PR to be merged -->

- [ ] Builds with zero warnings (`clippy -D warnings`, `tsc --strict`, `ruff`, `mypy`, Java compiler lint)
- [ ] Tests added at the right level (see docs/10-TESTING-STRATEGY.md) and passing
- [ ] Metrics/logging added where the feature is operationally relevant
- [ ] Security considerations checked (authz scoping, input validation, secrets)
- [ ] Docs/ADR updated if behavior, contract, or decision changed
- [ ] Changelog entry if user-visible

## Protobuf Compatibility
<!-- Required if proto/ files are modified -->
- [ ] `buf lint` passes
- [ ] `buf format -d --exit-code` passes
- [ ] `buf build` passes
- [ ] `buf breaking` against main passes (or no breaking changes introduced)
- [ ] Proto semantics unchanged (field numbers, types, units, scales preserved)

## Performance Changes
<!-- Required for perf-sensitive changes -->
- [ ] Benchmark before/after included in PR description
- [ ] Methodology documented (hardware, OS, CPU governor, build profile, message rate, loopback vs network)
- [ ] p50, p90, p99, p99.9, max and sample counts reported
- [ ] Clock domain of each number stated; indicative numbers labeled

## Security Considerations
<!-- Required for all changes -->
- [ ] Authz scoping: user_id filtering applied where user-owned data accessed
- [ ] Input validation: types, sizes, enums, ranges validated at edge
- [ ] No secrets in code, logs, URLs, or client bundles
- [ ] No default credentials in compose files or images
- [ ] Parameterized queries only; no string-built SQL
- [ ] Rate limits considered for new endpoints

## Documentation
<!-- Required if user-facing behavior changes -->
- [ ] Relevant docs updated (docs/04 to docs/08, docs/12, docs/13, etc.)
- [ ] ADR created/updated if a decision changed
- [ ] API spec updated if gRPC/REST/WebSocket contracts changed

## Testing
<!-- Required for all changes -->
- [ ] Unit tests added for new logic
- [ ] Integration tests added for new endpoints/flows
- [ ] Regression test added for bug fixes
- [ ] Property-based tests for hot-path logic (Rust)
- [ ] Isolation tests for user-owned resources (Java)

## Screenshots / Recordings
<!-- If UI changes, include before/after screenshots or a short screen recording -->

---

## Additional Notes
<!-- Any other information for reviewers -->