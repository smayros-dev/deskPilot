# End-to-end tests

Playwright tests of the whole stack through the browser: Keycloak sign-in, the Angular app, the backend and the
database. No model runs: the backend's `e2e` profile points Spring AI at [`fake-ollama.mjs`](fake-ollama.mjs), a
scripted stand-in for Ollama (search the knowledge base, then answer with a citation; for a ticket request, call
`create_ticket`, which pauses for a reviewer). CI runs them in the `e2e` job of `.github/workflows/ci.yml`.

| Test | What it checks |
|---|---|
| `login-and-roles` | alice, bob and admin sign in through Keycloak; each sees the pages of their role; alice is sent back from Approvals/Evaluation and sees the settings read-only; admin can edit them |
| `chat-and-approval` | a VPN question is answered with a cited knowledge-base article; a ticket request waits for approval, bob approves it on the Approvals page, alice's chat shows the confirmation and her Tickets page lists the ticket |
| `specialists` | admin adds a keyword to a specialist on the Specialists page and the route preview picks it at once; restoring the built-in specialists undoes it; alice cannot open the page |
| `languages` | French and Arabic translate the UI, Arabic switches to right-to-left, the choice survives a reload |

## Run locally

Next to the development stack (Postgres and Keycloak from `backend/compose.yaml`; the normal backend may keep running
on 8080), on other ports:

```bash
# 1. a separate database in the development Postgres (once); find its host port with `docker ps`
docker exec deskpilot-postgres-1 psql -U deskpilot -d deskpilot -c "create database deskpilot_e2e"

# 2. the scripted model (port 11500)
node e2e/fake-ollama.mjs

# 3. the backend with the e2e profile (port 8090)
cd backend && SPRING_PROFILES_ACTIVE=e2e E2E_DB_URL=jdbc:postgresql://localhost:<port>/deskpilot_e2e ./mvnw spring-boot:run

# 4. the frontend (port 4300), proxied to that backend
cd frontend && npx ng serve --port 4300 --proxy-config ../e2e/proxy.e2e.json

# 5. the tests (PW_CHANNEL=chrome uses the installed Chrome; otherwise run `npx playwright install chromium` once)
cd e2e && npm ci && PW_CHANNEL=chrome npx playwright test
```

Keycloak's demo realm accepts `http://localhost:4300` as a redirect URI. A Keycloak that imported the realm before
this was added needs it added to the `deskpilot-spa` client (Clients > deskpilot-spa > Valid redirect URIs and Web
origins), or a fresh container.

The tests share the database and leave their conversations and tickets behind; they do not depend on a clean state.
