# VIT Java Hackathon Portal

Full-stack hackathon management portal built with Spring Boot, React, and PostgreSQL (Neon-compatible).

## Features

- Admin dashboard for team management and problem statements
- Assign one problem statement to multiple teams
- Student login with VIT register number and access password
- Shared problem statement for every member of a team
- Read-only student view of team members and assigned problem
- Admin-only team, student, problem, and assignment management
- Admin submission review and Excel export, with double confirmation for bulk clears

## Team import format

Team CSV and Excel imports must use exactly the 19 team registration headers in the required order. The admin import dialog displays the complete ordered list and provides a CSV template download. Imports with missing, additional, renamed, or reordered headers—or rows containing extra cells—are rejected. The registration fields are stored as typed columns on `teams`; participant login records remain in `students`.

Team registration usernames and group leader names may repeat because they are not participant identity keys. Participant register numbers are trimmed and normalized to uppercase and must be unique across the upload, across teams, and within a team. If any import row is invalid, the entire upload is rolled back and no teams or students from that upload are saved.

## Local setup

### Backend

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` for Neon or a local PostgreSQL database.
Set `ADMIN_PASSWORD` to a strong, private coordinator password. These values are required environment variables and are intentionally not stored in Git.
`ADMIN_PASSWORD` must be a BCrypt hash in production. Student access keys are BCrypt-hashed when created or changed, and legacy student keys are upgraded after a successful login.

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
