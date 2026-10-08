# VIT Java Hackathon Portal

Full-stack hackathon management portal built with Spring Boot, React, and PostgreSQL (Neon-compatible).

## Features

- Admin dashboard for team management and problem statements
- Assign one problem statement to multiple teams
- Student login with the team registration username and participant register number
- Shared problem statement for every member of a team
- Read-only student view of team members and assigned problem
- Admin-only team, student, problem, and assignment management
- Admin submission review and Excel export, with double confirmation for bulk clears

## Team import format

Team CSV and Excel imports must use exactly the 19 team registration headers in the required order. The admin import dialog displays the complete ordered list and provides a CSV template download. Imports with missing, additional, renamed, or reordered headers—or rows containing extra cells—are rejected. The registration fields are stored as typed columns on `teams`; participant login records remain in `students`.

Team registration usernames and group leader names may repeat. Participant login uses the combination of the team registration `Username` and the participant register number; both values are trimmed and normalized case-insensitively. A register number may therefore appear for participants using different team usernames, but a duplicate username/register-number combination is rejected. Repeated combinations within an import reject the entire upload; no teams or students from that upload are saved. Question imports are also validated as a whole before rows are saved.

Editing imported registration details updates the linked student roster and login username. Participants sign in with the team registration `Username` and their register number; they do not enter an institution name. A team leader must be reassigned before that participant can be deleted. Admin passwords are held in browser memory only and are not persisted in web storage. Participant login does not yet use a separate access password.

## Local setup

### Backend

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` for Neon or a local PostgreSQL database.
Set `ADMIN_PASSWORD` to a strong, private coordinator password. These values are required environment variables and are intentionally not stored in Git.
`ADMIN_PASSWORD` must be a BCrypt hash in production.
Configure `CORS_ALLOWED_ORIGIN_PATTERNS` as a comma-separated list of trusted frontend origins in production. The default permits localhost and the portal's Cloudflare Pages domains. Authentication throttling is held in the backend process's memory, so it resets on restart and is not shared across multiple instances. Admin throttling uses the socket peer address and deliberately does not trust client-supplied forwarding headers; if the hosting proxy masks client addresses, add a trusted edge/API rate limit before public launch or scaling out.

### Frontend

```powershell
cd frontend
npm install
npm run dev
```

Set `VITE_API_URL` when the backend is not running at `http://localhost:8080`.

## Deployment

- Deploy `backend/` as the Render web service using the included `Dockerfile` and `render.yaml`.
- In Render, set the Neon JDBC URL as `jdbc:postgresql://<host>/<database>?sslmode=require`.
- Deploy `frontend/` to Cloudflare Pages with build command `npm run build`, output directory `dist`, and `VITE_API_URL` pointing to the Render API URL.
- After the Cloudflare Pages domain is ready, configure a custom VIT domain/subdomain through the college DNS administrator.
- Set `CORS_ALLOWED_ORIGIN_PATTERNS` to the exact production frontend origin(s) when deploying; use the Cloudflare Pages defaults only when those are the actual origins.
