# VIT Java Hackathon Portal

Full-stack hackathon management portal built with Spring Boot, React, and PostgreSQL (Neon-compatible).

## Features

- Admin dashboard for team management and problem statements
- Assign one problem statement to multiple teams
- Shared team login with the team's primary email and primary contact number
- Shared view of every non-empty team registration field for all authenticated team members
- Shared problem statement for every member of a team
- Read-only student view of team members and assigned problem
- Admin-only team, student, problem, and assignment management
- Admin submission review and Excel export, with double confirmation for bulk clears

## Team import format

Team CSV and Excel imports map recognized registration headers by name, regardless of their order. Extra unrecognized columns are ignored, and omitted optional columns are treated as blank. Only the primary contact number and primary email are required; every other registration field, including leader and member details, is optional. The admin import dialog displays the supported fields and provides a CSV template download. The registration fields are stored as typed columns on `teams`; participant records remain in `students` for roster management. In the database, the primary contact number and primary email columns are non-null, and the other registration columns are nullable.

All team members use the same participant login: the team's primary email and primary contact number. Email matching is case-insensitive; phone-number matching ignores spaces and punctuation. Register numbers and `Username` are not accepted as login credentials. Each team must have a unique primary email and contact number. Repeated emails or contact numbers within an import, or credentials already assigned to another team, reject the entire upload; no teams or students from that upload are saved. Question imports are also validated as a whole before rows are saved.

Editing imported registration details updates the linked student roster. Participants sign in with the team's primary email and primary contact number, and any member with those shared credentials can submit or update the team's project links. Username and register number are not used for login. A team leader must be reassigned before that participant can be deleted. Admin passwords are held in browser memory only and are not persisted in web storage. The primary contact number functions as the shared login secret; there is no separate participant access password.

Every signed-in member can view the team's shared registration details. The portal displays populated values from the 19 registration fields and omits blank or missing values.

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
