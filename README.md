# zalobot

A Spring Boot backend for a **Zalo Official Account (OA) bot** that keeps track of
informal debts and payments between people, based on the chat messages users send
to the OA.

Users write messages like *"I owe Lan 50k for lunch"* to the OA. The bot stores
the message, analyses it for debt/payment information, works out who the other
person is (exact name, alias or fuzzy match) and records the transaction. If the
other person is unknown, the bot asks the sender whether a new person should be
created before recording anything.

> **Status: work in progress.** The full path from webhook message to stored
> debt/payment record, including the "create new person?" confirmation, is
> implemented and unit-tested, but it has not been verified against a live Zalo
> OA in its current form. See [Known limitations](#known-limitations).

## Features

- **Zalo OA webhook** – receives user messages (`POST /zalo/webhook`) and answers
  the verification challenge (`GET /zalo/webhook`).
- **Message history** – every inbound message is stored in MySQL.
- **Debt / payment records** – a debt has a debtor, a creditor, amount, reason and
  date; the direction (`USER_OWES_OTHER` / `OTHER_OWES_USER`) decides who is who.
  Payments are stored per user.
- **Entity resolution** – the counterparty name is resolved by exact name, then by
  alias, then by fuzzy matching (FuzzyWuzzy ratio > 80).
- **Gemini message analysis** – each message is sent to Gemini with a prompt
  that asks for a JSON object (transaction type, direction, counterparty, amount,
  reason, date). Markdown fences are stripped; truncated or invalid JSON is
  logged as a warning and treated as "not a transaction".
- **Confirmation flow** – if no matching person exists, the bot replies on Zalo
  asking whether to create one (*Có/Không*) and parks the analysed transaction in
  `pending_user_confirmations` (status `PENDING`) until the user answers
  (see [Confirmation flow](#confirmation-flow)).
- **Zalo OAuth v4** – exchanges the authorization code for access/refresh tokens
  (`GET /zalo/callback`), stores them, and refreshes the access token
  automatically when it expires.
- **Scheduled polling** – every 60 s the bot fetches the latest message of each
  configured user through the Zalo conversation API and processes it.
- **Gemini chat endpoint** – `POST /zalo/gemini/command` forwards free text to
  Gemini (`gemini-2.5-flash`) with per-sender in-memory conversation history.
- **Dev tools** – with the `dev` profile, `/simulate/message` injects a fake
  inbound message and `/simulate/clear-db` wipes the tables.

## Tech stack

| Area        | Technology                                                      |
|-------------|-----------------------------------------------------------------|
| Language    | Java 17                                                         |
| Framework   | Spring Boot 3.4.0 (Web, WebFlux `WebClient`, Data JPA, Validation, Actuator) |
| Build       | Gradle 8.14 (wrapper included)                                  |
| Database    | MySQL 8 (Hibernate, `ddl-auto=update`)                          |
| AI          | Google Gemini API (`generateContent`, `gemini-2.5-flash`)       |
| Messaging   | Zalo Official Account Open API v2.0, Zalo OAuth v4              |
| Libraries   | Gson, FuzzyWuzzy, Lombok                                        |
| Testing     | JUnit 5, Reactor `StepVerifier`, OkHttp `MockWebServer`          |

## Architecture

```mermaid
sequenceDiagram
    participant U as Zalo user
    participant Z as Zalo OA platform
    participant C as ZaloController
    participant S as ZaloService
    participant G as GeminiApiClient
    participant R as EntityResolutionService
    participant DB as MySQL

    U->>Z: sends message to OA
    Z->>C: POST /zalo/webhook
    C->>S: processIncomingZaloMessage(sender, text, msgId)
    S->>DB: save ZaloMessage
    alt pending confirmation for sender
        S->>S: handleConfirmationResponse ("Có" / "Không")
    else
        S->>G: analyzeMessageForDebt(text)
        G-->>S: TransactionAnalysisResult
        S->>DB: find or create AppUser for sender
        alt debt
            S->>R: resolveAppUser(name)
            alt person found
                S->>DB: save DebtRecord (debtor / creditor by direction)
            else unknown person
                S->>Z: ask "create new user?"
                S->>DB: save PendingUserConfirmation
            end
        else payment
            S->>DB: save PaymentRecord
        end
    end
```

Outbound Zalo calls (`sendMessage`, `getConversation`, `getProfile`) go through
`getValidAccessToken()`, which loads the latest token from `zalo_tokens` and
refreshes it via OAuth v4 when it has expired.

### Confirmation flow

1. The sender writes e.g. *"Tôi nợ Lan 50k tiền ăn trưa"*. Gemini returns
   `debt` / `USER_OWES_OTHER` / `Lan` / `50000`.
2. "Lan" matches no known person, so a `pending_user_confirmations` row
   (`type=CREATE_APP_USER`, `status=PENDING`, with the analysis JSON and the
   original message) is saved and the bot asks whether to create the person (Có/Không).
3. The sender's next message is treated as the answer while a `PENDING` row exists:
   - **Có / co / yes / y** – the person is created, the stored transaction is
     recorded (debtor/creditor according to the direction) and the row becomes `CONFIRMED`.
   - **Không / khong / no / n** – nothing is recorded and the row becomes `DENIED`.
   - anything else – the bot asks again and the row stays `PENDING`.

### Data model

| Table                        | Purpose                                              |
|------------------------------|------------------------------------------------------|
| `app_users`                  | People known to the bot (name, optional Zalo ID)     |
| `user_aliases`               | Alternative names for an app user                    |
| `debt_records`               | Debtor, creditor, amount, reason, date               |
| `payment_records`            | Payments per user                                    |
| `pending_user_confirmations` | Transactions waiting for a "create user?" answer     |
| `zalo_messages`              | Inbound/outbound message log                         |
| `zalo_tokens`                | Zalo OAuth access/refresh tokens                     |

## Configuration

All credentials and environment-specific values come from environment variables
(see [`.env.example`](.env.example)). Nothing secret is committed.

| Variable                 | Required | Description                                                     |
|--------------------------|----------|-----------------------------------------------------------------|
| `ZALO_APP_ID`            | yes      | Zalo app ID (developers.zalo.me)                                |
| `ZALO_APP_SECRET`        | yes      | Zalo app secret key                                             |
| `ZALO_OA_ID`             | yes      | Zalo Official Account ID                                        |
| `ZALO_REDIRECT_URI`      | no       | OAuth callback URL (default `http://localhost:8081/zalo/callback`) |
| `ZALO_SCHEDULED_USERS`   | no       | Comma-separated Zalo user IDs polled every 60 s                 |
| `GEMINI_API_KEY`         | yes      | Google Gemini API key                                           |
| `GEMINI_API_URL`         | no       | Default `https://generativelanguage.googleapis.com`            |
| `DB_URL`                 | yes      | JDBC URL, e.g. `jdbc:mysql://localhost:3306/zalobot`            |
| `DB_USERNAME`            | yes      | Database user                                                   |
| `DB_PASSWORD`            | yes      | Database password                                               |
| `SERVER_PORT`            | no       | Default `8081`                                                  |
| `SPRING_PROFILES_ACTIVE` | no       | Default `local`; add `dev` to enable `/simulate/*`             |

Alternatively, copy
[`src/main/resources/application-local.properties.example`](src/main/resources/application-local.properties.example)
to `application-local.properties` (git-ignored); it is picked up by the default
`local` profile.

## Running locally

Prerequisites: JDK 17 and a MySQL 8 database.

```bash
# 1. Create a database
mysql -u root -p -e "CREATE DATABASE zalobot; CREATE USER 'zalobot'@'%' IDENTIFIED BY 'change-me'; GRANT ALL ON zalobot.* TO 'zalobot'@'%';"

# 2. Provide configuration (env vars, or application-local.properties as above)
export ZALO_APP_ID=... ZALO_APP_SECRET=... ZALO_OA_ID=... GEMINI_API_KEY=...
export DB_URL="jdbc:mysql://localhost:3306/zalobot" DB_USERNAME=zalobot DB_PASSWORD=change-me

# 3. Start the app (Windows: gradlew.bat bootRun)
./gradlew bootRun
```

Then:

1. Authorize the OA by opening the Zalo OAuth URL for your app with the redirect
   URI pointing to `/zalo/callback`; the bot stores the returned tokens.
2. Point the OA webhook to `https://<public-host>/zalo/webhook` (e.g. via a
   tunnel such as ngrok or cloudflared).

To try the flow without Zalo, run with `SPRING_PROFILES_ACTIVE=local,dev` and:

```bash
curl -X POST "http://localhost:8081/simulate/message" \
     --data-urlencode "senderId=test-user" \
     --data-urlencode "messageText=Tôi nợ Lan 50k tiền ăn trưa"
```

## Testing

```bash
./gradlew test      # or: ./gradlew build
```

Tests run without network access or real credentials:

- `GeminiApiClientTest` uses OkHttp `MockWebServer` to stub the Gemini API:
  plain responses, errors, the API key sent as a header, and debt extraction
  (clean JSON, fenced JSON, truncated/invalid JSON, API errors).
- `ZaloServiceConfirmationFlowTest` uses Mockito for the repositories and the
  Gemini client and covers: unknown person -> confirmation question, known
  person -> direct record, and the yes / no / invalid replies.

## Project structure

```
src/main/java/io/github/baoloc1993/zalobot
├── ZalobotApplication.java
├── config/       ZaloConfig (OA settings), ZaloScheduler (60 s polling)
├── controller/   ZaloController (webhook, OAuth callback, OA API proxies),
│                 UserSimulationController (dev profile only)
├── model/        JPA entities, TransactionAnalysisResult,
│                 gemini/ (request/response DTOs), zalo/ (webhook DTO)
├── repository/   Spring Data JPA repositories
└── service/      ZaloService (message pipeline, Zalo API, tokens),
                  GeminiApiClient, EntityResolutionService,
                  ZaloTokenService, UserSimulationService
src/main/resources/application.properties
src/test/java/.../service/GeminiApiClientTest.java
```

## Known limitations

- The scheduled poller re-reads each configured user's latest message every
  60 s without remembering which message it has already processed, so one
  message can be processed repeatedly.
- Summary requests ("how much do I owe?") are recognised by the analysis but not
  answered yet.
- Zalo API calls (OAuth, sending messages, conversation) are not covered by
  tests; only Gemini calls and the confirmation flow are.
- Gemini chat history for `/zalo/gemini/command` is kept in memory only.
- The schema is managed by Hibernate `ddl-auto=update`; there are no migrations.
