# HMS Changelog: Corrections Applied and Open Items

Files edited in place: `hms-architecture.md` (Part 1), `hms-services-part2.md`, `hms-terraform-edge.md` (Part 3), `hms-eks-helm.md` (Part 4), `hms-terraform-data.md` (Part 5), `hms-tests.md` (Part 6). None of this has been compiled or applied against a real environment.

## Applied

| # | File | Change |
|---|------|--------|
| 1 | Part 1, `HmsPermissionEvaluator.decide` | Admins may now read `NORMAL` records only; all other admin access is denied. Previously every admin action was denied. |
| 2 | Part 1, `AppointmentSagaOrchestrator.onBilling` | A late `InvoiceCreated` for a saga already in `FAILED` now emits `VoidInvoice`, so no orphaned invoice remains. |
| 3 | Part 1, CQRS section | Note added: OpenSearch needs the `opensearch-java` client, not the Elasticsearch 8 client. |
| 4 | Part 2, `AllocationService` | Check-all-then-reserve. `AllocationFailed` is published in the normal transaction (no self-invoked `REQUIRES_NEW`, no rollback-only). Added `available()` query, a `release()` compensation method, and a retry note for `ConcurrentStockChangeException`. |
| 5 | Part 3, `data` security group | Catch-all ingress removed; port-specific rules live in Part 5. |
| 6 | Part 3, ACM certificate | Removed the `internal.hms.local` SAN (public ACM can't issue for private TLDs). Now `internal.hms.example.com`, pointed at the internal ALB through a private hosted zone. |
| 7 | Part 4, Helm values and Strimzi | Kafka bootstrap port `9094` -> `9098` (MSK IAM). Added SASL/IAM settings for the Connect worker, producer, consumer and admin clients, the `aws-msk-iam-auth` plugin, and an IRSA annotation for the Connect service account. |
| 8 | Part 5, Redis note | Removed an inaccurate property name; now states the actual fail-open behavior. |
| 9 | Parts 5 and 6 | Status notes updated to say the fixes are applied. |

## Open items (not fixed, in priority order)

1. **Break-glass can be spoofed (high, security).** The evaluator reads an `X-Break-Glass` request header. Any doctor client can set it, and nothing ties it to a justification or a time window. Recommended design: `iam-auth-service` issues a short-lived signed claim (for example `break_glass: {patientId, until}`) after the doctor submits a justification at `POST /api/iam/break-glass`. The evaluator checks that claim against the requested patient and expiry, and the header is dropped entirely. Also strip any client-supplied `X-Forwarded-For` at the gateway, because the "trusted network" check reads it.
2. **Route 53 health checks vs. the origin-verify header (medium).** The ALB returns 403 to health checkers because they can't send `X-Origin-Verify`. Part 3 describes two fixes; I recommend the CloudWatch-alarm-based check. Not implemented.
3. **Helper types are referenced but not defined (medium).** `AuditPublisher`, `Deduper`, `ProcessedMessage`, `PrescriptionClient`, `Slot` / `SlotRepository`, `RecipientDirectory`, `TemplateRenderer`, `EncryptedStringConverter`, the event records, and several Spring Data finder methods are used without bodies. They are straightforward but need writing before anything compiles.
4. **Saga timeout sweeper (medium).** Described in prose and referenced by the late-invoice fix, but no code or test exists.
5. **NetworkPolicy ingress is VPC-wide (low).** Part 4 allows the whole VPC CIDR to reach pods that sit behind a target group. Narrow it to the ALB subnet CIDRs.
6. **No DR region (low until required).** MSK Replicator, RDS cross-region replicas, and a second regional stack for the Route 53 latency/failover records are not covered.
7. **Remaining tests (see Part 6, section 5).** Debezium and saga end-to-end, resilience with WireMock, gateway header and rate-limit tests, ArchUnit, and event contract tests.
