# Vehicle Finance API

Spring Boot 4.1.1 API implementing the core Vehicle Finance System case-study HTTP contract and React UI adapters. Business state is persisted in a SQL database. The current schema stores one JSON snapshot row; this is durable for a single API instance but is not a normalized, horizontally scalable production data model.

## Run and verify

Requires Java 17 and Maven 3.9.15. The build enforces the Maven version.

- `mvn test`
- `mvn spring-boot:run`

The local API listens on port 8080 and now uses MySQL 8+ by default. Create the `vehicle_finance` database using `database/mysql-setup.sql`, then provision a dedicated application account with SELECT, INSERT, UPDATE and CREATE privileges on that database. Set `VFS_DATABASE_USERNAME` and `VFS_DATABASE_PASSWORD` in the launching process environment. The default JDBC URL is `jdbc:mysql://localhost:3306/vehicle_finance?connectionTimeZone=UTC`; set `VFS_DATABASE_URL` to override it. No password is stored in source control. Spring Boot does not automatically load `.env` files.

H2 is retained only as a test-scoped dependency for isolated tests. Existing `./data/vehicle-finance.mv.db` data is not deleted or automatically copied into MySQL. A fresh MySQL database starts with the synthetic seed dataset; export and import the existing snapshot separately if historical data must be retained. Do not copy live database files or delete the original H2 file.

The React app defaults to `http://localhost:8080` during development; set `VITE_API_BASE_URL` to override it. Use `VITE_DATA_MODE=mock` only to use the frontend's local synthetic dataset.

Secure file upload is enabled only with the explicit local profile (`SPRING_PROFILES_ACTIVE=local`) for development tests. Production/default profiles reject upload requests until authenticated ownership and scanner integrations are configured. The local adapter stores files below the private `./data/quarantine` directory and never serves raw bytes. Production storage is configured for private S3 objects with SSE-KMS; provide `AWS_REGION`, `VFS_DOCUMENTS_BUCKET`, and `VFS_DOCUMENTS_KMS_KEY_ID` through the deployment identity/secret manager. Configure S3 Block Public Access, restrictive bucket/KMS policies, TLS-only access, lifecycle/retention, versioning, and backup controls separately.

For the local registration demo with OTP code reveal, start with the explicit `local` profile: `mvn spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=local`. The default and `prod` profiles have OTP delivery disabled and fail closed; this repository does not yet include an Azure Communication Services sender. Never enable the `local` profile in shared, staging, or production environments.

## MySQL production profile

The `prod` profile also uses MySQL. Provide `VFS_DATABASE_URL`, `VFS_DATABASE_USERNAME`, and `VFS_DATABASE_PASSWORD` through your deployment secret manager/environment, then run with `SPRING_PROFILES_ACTIVE=prod`. Use `sslMode=VERIFY_IDENTITY` and a trusted server certificate for remote production connections. `.env.production.example` lists the variable names only. Do not commit credentials. `schema-mysql.sql` creates the snapshot table with LONGTEXT for large JSON payloads; use a reviewed migration strategy before production upgrades. Production document-storage configuration is still required separately.

The app loads the saved snapshot at startup and saves state-changing API commands in a JDBC transaction. This implementation is currently **single-instance**: multiple API replicas can overwrite each other's whole snapshots. Before multi-instance/production use, normalize domain records into separate tables/schemas, add optimistic locking/transactions, migrations and backups, and test restore procedures.

## API surface

All response bodies use JSON. Create commands accept `Idempotency-Key` where supported. Requests may include `X-Correlation-ID`; validation and workflow errors return a problem object with `title`, `detail`, `status`, `code`, and `correlationId`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/health` | Liveness check |
| GET | `/api/bootstrap` | Synthetic dataset consumed by React |
| POST / GET | `/api/customers`, `/api/customers/{customerId}` | Register/update the demo customer and read a profile |
| POST | `/api/otp/challenges` | Issue an EMAIL or SMS challenge (local profile only until delivery provider is configured) |
| POST | `/api/otp/challenges/verify` | Verify a short-lived, attempt-limited one-time code |
| GET / POST | `/api/dealers`, `/api/dealers/{dealerId}` | List/filter, submit a dealer, read, and update dealer status |
| POST | `/api/kyc/cases` | Start a KYC case (202 Accepted) |
| GET | `/api/kyc/cases/{caseId}` | Read case and evidence metadata |
| POST | `/api/kyc/cases/{caseId}/evidence` | Register a protected-object reference (202 Accepted; no file upload) |
| POST multipart | `/api/documents/upload` | Validate PDF/PNG/JPEG signatures and size, store privately under quarantine, and link metadata to a CUSTOMER, KYC_CASE, or LOAN_APPLICATION owner |
| POST | `/api/kyc/cases/{caseId}/decision` | Record KYC verified/rework/rejected outcome |
| GET / PATCH | `/api/vehicles/{vehicleId}` | Read/update vehicle availability, eligibility, stock, and versioned price |
| POST | `/api/vehicles/search` | Search/filter catalogue, returns `items`, `total`, `page`, and `size` |
| GET | `/api/loans` | Filter and paginate applications |
| POST / PUT | `/api/loans`, `/api/loans/{loanId}` | Create and update a draft |
| POST | `/api/loans/{loanId}/submit` | Submit a draft (202 Accepted) |
| GET | `/api/loans/{loanId}` | Read an application |
| GET | `/api/loans/{loanId}/timeline` | Read customer-safe timeline events |
| POST | `/api/loans/{loanId}/documents` | Register loan evidence metadata/reference |
| POST | `/api/loans/{loanId}/kyc-decision` | Record loan KYC workflow decision |
| POST | `/api/loans/{loanId}/recommendation` | Record analyst recommendation |
| POST | `/api/loans/{loanId}/decision` | Record final decision and create an offer on approval |
| GET | `/api/offers/{offerId}` | Read offer snapshot |
| POST | `/api/offers/{offerId}/accept` | Accept a specific offer version |
| POST | `/api/offers/{offerId}/esign-sessions` | Start an offer/customer-scoped signing session (sandbox profile only) |
| GET | `/api/esign-sessions/{sessionId}` | Read signing session status and expiry |
| POST | `/api/esign-sessions/{sessionId}/complete` | Complete the explicit-consent local sandbox signing step |
| POST | `/api/loans/{loanId}/agreement-signed` | Record a signed session against the matching offer and loan |
| POST | `/api/webhooks/esign` | Verify webhook HMAC only; provider event processing is intentionally disabled |
| PATCH | `/api/loans/{loanId}/conditions/{conditionId}` | Update an offer condition |
| POST | `/api/loans/{loanId}/funding` | Submit a ready offer through the configured funding adapter; returns an idempotent ACKNOWLEDGED receipt (202), never a settlement confirmation |
| GET | `/api/audit` | Read synthetic audit events |

Search and list filters are query parameters or JSON fields as appropriate. For example, `/api/dealers?status=ACTIVE&region=South` and `/api/loans?status=CREDIT_REVIEW&page=0&size=25`.

## Business checks included

- Customer consent, email, and mobile validation.
- OTP proof is required for both normalized email and mobile before profile creation; codes expire, are attempt-limited, throttled per destination, stored as hashes, and consumed once. Development code exposure is local-profile-only; the default and production profiles disable OTP until a real delivery provider is configured.
- Active dealer and active/eligible vehicle checks; vehicle price version is revalidated at origination and final decision.
- Down-payment, income, obligations, and tenure bounds.
- Explicit loan and KYC states, KYC gate before final credit decision, structured decision rationale, offer expiry/version checks, and accepted-offer/conditions checks before funding hand-off.
- In-process idempotency for loan creation and duplicate-safe submit, offer-acceptance, funding, and evidence-reference operations.
- Multipart upload uses opaque object keys, a 5 MB cap, file-signature checks, private local/S3 quarantine, and SSE-KMS for configured S3 mode. There is intentionally no raw-file download endpoint.
- The local e-sign sandbox displays the versioned offer snapshot, requires explicit consent, and updates the agreement condition after the session is marked signed. It is not a legal signature; sessions are in-memory and production/provider event processing is not configured.
- Funding uses a no-network local simulator only when `vfs.funding.mode=sandbox`; it returns and persists an `ACKNOWLEDGED` provider reference while keeping `fundsDisbursed=false`. Requests require `Idempotency-Key`; retries with the same key return the original receipt. Default and production configurations are disabled. This is not a real external lender connection.
- Audit/timeline records for the implemented state changes.

## Important production gaps

This is durable SQL snapshot persistence, not a fully modeled relational microservices platform. OIDC/JWT authentication and server-side role/ownership/dealer authorization are not configured; production uploads are therefore disabled. The frontend role selector is not a security control. OTP challenges work in the isolated local simulator; actual ACS email/SMS delivery is not configured, so default and production profiles reject issuance until a provider is integrated. Malware scanning is not configured: uploads remain PENDING in quarantine and KYC verification remains blocked until a trusted scanner reports CLEAN. There is no download endpoint. A real funding provider, trusted operations authorization, durable distributed idempotency, timeout reconciliation, and provider callbacks remain unimplemented; do not enable external funding against the single-instance snapshot store. Outbox/event broker, external KYC/credit bureau, e-signature and notification providers, observability, backup/restore and multi-instance safety also remain to be integrated and tested. Do not use real customer data or financial transactions with this educational deployment.
