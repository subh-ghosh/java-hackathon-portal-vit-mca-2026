import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const API = import.meta.env.VITE_API_URL || 'https://vit-hackathon-api.onrender.com/api';
const SESSION_TTL = 7 * 24 * 60 * 60 * 1000;
const ADMIN_SESSION_KEY = 'hackathon-admin-session';
const ATTENDANCE_SYNC_INTERVAL_MS = 3000;

function toLocalDateTimeInput(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

function toLocalLoginSettings(settings) {
  return {
    ...settings,
    startTime: toLocalDateTimeInput(settings.startTime),
    endTime: toLocalDateTimeInput(settings.endTime)
  };
}

function toLocalRoundTwoSettings(settings) {
  return {
    ...settings,
    activeRound: settings.activeRound || (settings.enabled ? '2' : '1'),
    roundTwoStartTime: toLocalDateTimeInput(settings.roundTwoStartTime || ''),
    roundTwoEndTime: toLocalDateTimeInput(settings.roundTwoEndTime || settings.deadline || '')
  };
}

function toServerRoundTwoSettings(settings) {
  const toIsoString = value => value ? new Date(value).toISOString() : '';
  return {
    roundTwoStartTime: toIsoString(settings.roundTwoStartTime),
    roundTwoEndTime: toIsoString(settings.roundTwoEndTime)
  };
}

function toServerLoginSettings(settings, attendancePassword) {
  const toIsoString = value => value ? new Date(value).toISOString() : '';
  return {
    loginEnabled: settings.loginEnabled,
    startTime: toIsoString(settings.startTime),
    endTime: toIsoString(settings.endTime),
    attendancePassword
  };
}

function hasLoginSchedule(settings) {
  return Boolean(settings.startTime || settings.endTime);
}

function isLoginScheduleActive(settings, now) {
  const start = settings.startTime ? new Date(settings.startTime).getTime() : null;
  const end = settings.endTime ? new Date(settings.endTime).getTime() : null;
  if ((start !== null && !Number.isFinite(start)) || (end !== null && !Number.isFinite(end))) return false;
  return (start === null || now >= start) && (end === null || now <= end);
}

function RoundTwoManagement({
  settings,
  onSettingsChange,
  loginSettings,
  onLoginSettingsChange,
  onSave,
  onTogglePause,
  pauseUpdating,
  attendancePassword,
  onAttendancePasswordChange,
  submissions,
  search,
  onSearchChange,
  onDownload,
  onClear,
  downloading
}) {
  const [now, setNow] = useState(Date.now());
  const roundTwoSchedule = {
    startTime: settings.roundTwoStartTime,
    endTime: settings.roundTwoEndTime
  };
  const scheduled = hasLoginSchedule(loginSettings) || hasLoginSchedule(roundTwoSchedule);
  const normalizedSearch = search.trim().toLowerCase();
  const filteredSubmissions = submissions.filter(submission => (
    `${submission.team?.name || ''} ${submission.team?.teamNumber || ''} ${submission.googleDriveLink || ''} ${submission.githubLink || ''}`
      .toLowerCase().includes(normalizedSearch)
  ));

  useEffect(() => {
    if (!scheduled) return undefined;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [scheduled, loginSettings.startTime, loginSettings.endTime, settings.roundTwoStartTime, settings.roundTwoEndTime]);

  const roundOneScheduleActive = !scheduled || isLoginScheduleActive(loginSettings, now);
  const roundTwoActive = settings.activeRound === '2' && settings.open
    && isLoginScheduleActive(roundTwoSchedule, now);
  const accessStatus = loginSettings.accessPaused
    ? 'Participant access paused'
    : settings.activeRound === '2'
      ? roundTwoActive ? 'Round 2 active · advanced teams only' : 'Round 2 closed'
      : roundOneScheduleActive ? 'Round 1 active · all teams' : 'Round 1 scheduled';

  return (
    <div className="round-two-management">
      <form className="card settings-form round-two-settings-form" onSubmit={onSave}>
        <div className="card-top">
          <div><span className="eyebrow">PARTICIPANT ACCESS</span><h2>Active round</h2></div>
          <div className="login-window-actions">
            <button type="button" className="pause-access-button" onClick={onTogglePause} disabled={pauseUpdating}>
              {pauseUpdating ? 'Updating access…' : loginSettings.accessPaused ? 'Resume access now' : 'Pause access now'}
            </button>
            <button type="submit">Save round settings</button>
          </div>
        </div>
        <span className={`pill ${settings.activeRound === '2' ? roundTwoActive ? 'round-two-open' : 'round-two-closed' : roundOneScheduleActive && !loginSettings.accessPaused ? 'round-two-open' : 'round-two-closed'}`}>
            {accessStatus}
          </span>
        <p className="muted">Only one round is active at a time. Round 1 allows all teams to log in; Round 2 allows only advanced teams.</p>
        <label className="round-active-field">Active round
          <select value={settings.activeRound} onChange={event => onSettingsChange(current => ({ ...current, activeRound: event.target.value, enabled: event.target.value === '2' }))}>
            <option value="1">Round 1 · all teams</option>
            <option value="2">Round 2 · advanced teams only</option>
          </select>
        </label>
        <div className="settings-fields">
          <label>Round 1 start time (optional)<input type="datetime-local" value={toLocalDateTimeInput(loginSettings.startTime)} onChange={event => onLoginSettingsChange(current => ({ ...current, startTime: event.target.value }))} /></label>
          <label>Round 1 end time (optional)<input type="datetime-local" value={toLocalDateTimeInput(loginSettings.endTime)} onChange={event => onLoginSettingsChange(current => ({ ...current, endTime: event.target.value }))} /></label>
        </div>
        <div className="settings-fields">
          <label>Round 2 start time (optional)
            <input type="datetime-local" value={settings.roundTwoStartTime} onChange={event => onSettingsChange(current => ({ ...current, roundTwoStartTime: event.target.value }))} />
          </label>
          <label>Round 2 end time (optional)
            <input type="datetime-local" value={settings.roundTwoEndTime} onChange={event => onSettingsChange(current => ({ ...current, roundTwoEndTime: event.target.value }))} />
          </label>
        </div>
        <p className="login-window-note">Times are entered in your browser’s local timezone. Leave a round’s schedule blank for immediate access.</p>
        <label className="attendance-password-field">Attendance coordinator password
          <input type="password" minLength="8" maxLength="72" autoComplete="new-password" value={attendancePassword} onChange={event => onAttendancePasswordChange(event.target.value)} placeholder={loginSettings.attendancePasswordConfigured ? 'Leave blank to keep the current password' : 'Set a separate password (8-72 characters)'} />
        </label>
        <p className="login-window-note">{loginSettings.attendancePasswordConfigured
          ? 'Attendance coordinators use this shared password for check-in only. Enter a new password above to change it.'
          : 'Set a separate password to enable attendance coordinator sign-in.'}</p>
        <div className="round-two-settings-actions">
          <small>{settings.activeRound === '2'
            ? settings.roundTwoStartTime || settings.roundTwoEndTime
              ? `Round 2 access ${settings.roundTwoStartTime ? `starts at ${new Date(settings.roundTwoStartTime).toLocaleString()}` : 'has no start restriction'}${settings.roundTwoEndTime ? ` and closes at ${new Date(settings.roundTwoEndTime).toLocaleString()}` : ' and has no end restriction'}.`
              : 'Round 2 has no schedule and is available immediately until switched to Round 1.'
            : 'Saving activates Round 1 and deactivates Round 2.'}</small>
        </div>
      </form>
      <section className="card table-card round-two-submissions">
        <div className="card-top">
          <div><span className="eyebrow">ROUND 2 SUBMISSIONS</span><h2>Round 2 submissions</h2></div>
          <div className="problem-library-actions">
            <span className="pill">{submissions.length} submitted</span>
            <button type="button" className="small-button" onClick={onDownload} disabled={downloading}>
              {downloading ? 'Downloading...' : 'Download Excel'}
            </button>
            <button type="button" className="small-button danger-button" onClick={onClear}>Clear all</button>
          </div>
        </div>
        <label className="admin-search-field">Search Round 2 submissions
          <input type="search" value={search} onChange={event => onSearchChange(event.target.value)}
            placeholder="Team name, number, Drive or GitHub link" />
        </label>
        {submissions.length === 0
          ? <div className="empty round-two-empty">No Round 2 submissions yet.</div>
          : filteredSubmissions.length === 0
            ? <div className="empty round-two-empty">No Round 2 submissions match your search.</div>
          : <div className="table-wrap"><table><thead><tr><th>Team</th><th>Google Drive</th><th>GitHub</th><th>Updated</th></tr></thead>
            <tbody>{filteredSubmissions.map(submission => <tr key={submission.id}>
              <td><b>#{submission.team?.teamNumber} · {submission.team?.name}</b></td>
              <td><a href={submission.googleDriveLink} target="_blank" rel="noreferrer">Open document</a></td>
              <td><a href={submission.githubLink} target="_blank" rel="noreferrer">Open repository</a></td>
              <td>{submission.updatedAt ? new Date(submission.updatedAt).toLocaleString() : '-'}</td>
            </tr>)}</tbody></table></div>}
      </section>
    </div>
  );
}

function reportRequestError(message) {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('portal-request-error', { detail: message }));
  }
}

function readSession(key) {
  try {
    const value = JSON.parse(sessionStorage.getItem(key) || 'null');
    if (!value?.expiresAt) return null;
    if (value.expiresAt <= Date.now() || !value.credentials?.email || !value.credentials?.contactNumber) {
      sessionStorage.removeItem(key);
      return { expired: true };
    }
    return value;
  } catch {
    return { expired: true };
  }
}

function saveSession(key, value) {
  sessionStorage.setItem(key, JSON.stringify({ ...value, expiresAt: Date.now() + SESSION_TTL }));
}

function clearSession(key) {
  sessionStorage.removeItem(key);
}

function readAdminSession() {
  try {
    const value = JSON.parse(sessionStorage.getItem(ADMIN_SESSION_KEY) || 'null');
    if (!value?.password || !value?.expiresAt || value.expiresAt <= Date.now()) {
      sessionStorage.removeItem(ADMIN_SESSION_KEY);
      return null;
    }
    return value;
  } catch {
    sessionStorage.removeItem(ADMIN_SESSION_KEY);
    return null;
  }
}

function saveAdminSession(password) {
  sessionStorage.setItem(ADMIN_SESSION_KEY, JSON.stringify({
    password,
    expiresAt: Date.now() + SESSION_TTL
  }));
}

async function request(path, options = {}) {
  const isFormData = options.body instanceof FormData;
  let response;
  try {
    response = await fetch(`${API}${path}`, {
      ...options,
      headers: { ...(isFormData ? {} : { 'Content-Type': 'application/json' }), ...(options.headers || {}) }
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'The server could not be reached.';
    reportRequestError(message);
    throw error;
  }
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = body.message || body.detail || body.title || 'Something went wrong';
    reportRequestError(message);
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return body;
}

function Login({ onLogin, onAdmin }) {
  const [form, setForm] = useState({ email: '', contactNumber: '' });
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  async function submit(event) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError('');
    try {
      onLogin(await request('/student/login', { method: 'POST', body: JSON.stringify(form) }), form);
    } catch (e) { setError(e.message); }
    finally { setSubmitting(false); }
  }
  return (
    <main className="login-shell">
      <button type="button" className="mode-switch" onClick={onAdmin}>Coordinator Login</button>
      <section className="hero event-hero">
        <div className="event-host">
          <img className="login-logo" src="/vit-logo-transparent.png" alt="Vellore Institute of Technology" />
          <div>
            <span className="eyebrow">School of Computer Science Engineering and Information Systems</span>
            <span className="event-host-subtitle">VIT Vellore</span>
          </div>
        </div>
        <div className="event-title-lockup">
          <span className="event-name">GreenOps :</span>
        </div>
        <span className="eyebrow event-track">HACKATHON · VIT VELLORE</span>
        <h1>Enterprise AI using<br /><em>Spring Boot &amp; Microservices</em></h1>
        <p className="event-description">Develop a Spring Boot-based enterprise web application illustrating microservices architecture with AI features and the Model-View-Controller (MVC) design pattern for your assigned problem statement.</p>
        <div className="event-facts">
          <div><span className="event-fact-icon">⌁</span><span><b>October 10 &amp; 11, 2026</b><small>Hackathon dates</small></span></div>
          <div><span className="event-fact-icon">⌖</span><span><b>VIT, Vellore</b><small>Silver Jubilee Tower · Sarojini Naidu Gallery</small></span></div>
        </div>
        <div className="event-timeline" aria-label="Hackathon schedule">
          <div><b>08:00 AM</b><span>Registration begins</span><small>10 Oct</small></div>
          <div><b>09:30 AM</b><span>Problem disclosure</span><small>10 Oct</small></div>
          <div><b>09:30 AM</b><span>Development &amp; submissions due</span><small>11 Oct</small></div>
          <div><b>05:00 PM</b><span>Results &amp; valedictory</span><small>11 Oct</small></div>
        </div>
        <p className="event-stack-note"><b>Mandatory:</b> Java + Spring Boot · Thymeleaf · Spring Data JDBC/JPA · Relational database</p>
      </section>
      <form className="card login-card event-login-card" onSubmit={submit}>
        <span className="eyebrow">GREENOPS · PARTICIPANT PORTAL</span>
        <h2>Your team’s<br />challenge space.</h2>
        <p className="muted">Sign in to view your assigned problem statement and submit your team’s project links.</p>
        <label>Team’s primary email<input required type="email" value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} placeholder="Email from your team registration" autoComplete="email" /></label>
        <label>Team’s primary contact number<input required type="tel" value={form.contactNumber} onChange={e => setForm({ ...form, contactNumber: e.target.value })} placeholder="Contact number from your team registration" autoComplete="tel" /></label>
        {error && <div className="error" role="alert">{error}</div>}
        <button disabled={submitting}>{submitting ? 'Signing in…' : 'Enter team workspace'} <span>→</span></button>
        <div className="login-footnote"><b>Read the event guidelines before participating</b><span>Schedule · technology policy · conduct · awards · travel and accommodation</span></div>
      </form>
      <section className="event-details">
        <div className="event-details-heading">
          <span className="eyebrow">OFFICIAL EVENT GUIDELINES</span>
          <h2>GreenOps : Enterprise AI using Spring Boot &amp; Microservices</h2>
          <p>School of Computer Science Engineering and Information Systems · October 10 &amp; 11, 2026</p>
        </div>
        <div className="event-detail-grid">
          <article className="event-detail-card event-registration-card">
            <span className="eyebrow">ELIGIBILITY &amp; REGISTRATION</span>
            <h3>Who can participate</h3>
            <p>Open to undergraduate and postgraduate students, Ph.D. students, research scholars and faculty from recognized Indian institutions pursuing Engineering, Science, Technology, MCA, M.Tech or equivalent programmes.</p>
            <p><b>Team size:</b> Participate individually or in a team of up to four members. Teams of four are strongly recommended.</p>
            <h4>Registration steps and fee</h4>
            <p>Register using the Google Form, then sign in to the VIT events portal to make the registration payment.</p>
            <div className="event-registration-actions">
              <a href="https://forms.gle/Sgyfwqm69c8naWzt7" target="_blank" rel="noreferrer">Open registration form <span aria-hidden="true">↗</span></a>
              <a href="https://events.vit.ac.in/" target="_blank" rel="noreferrer">VIT events &amp; payment portal <span aria-hidden="true">↗</span></a>
            </div>
            <ul>
              <li>VIT Vellore students: ₹300 (GST included).</li>
              <li>Students from other institutions: ₹500 (GST included).</li>
            </ul>
            <p className="event-registration-note">The registration form currently indicates that it is no longer accepting responses. Contact the organizers if you need assistance.</p>
          </article>
          <article className="event-detail-card event-schedule-card">
            <span className="eyebrow">SCHEDULE AT A GLANCE</span>
            <h3>Key event times</h3>
            <div className="event-schedule-list">
              <div><time>10 Oct · 08:00 AM</time><span>Hackathon registration begins</span></div>
              <div><time>10 Oct · 09:00 AM</time><span>Registration ends. No entry after this time.</span></div>
              <div><time>10 Oct · 09:30 AM</time><span>Problem statement disclosure</span></div>
              <div><time>10 Oct · 12:00 PM</time><span>Round 1 evaluation</span></div>
              <div><time>10 Oct · 02:00 PM</time><span>Round 1 results announcement</span></div>
              <div><time>11 Oct · 09:30 AM</time><span>Development completion, document upload (format shared during the event) and GitHub repository submission</span></div>
              <div><time>11 Oct · 10:00 AM</time><span>Evaluation begins</span></div>
              <div><time>11 Oct · 05:00 PM</time><span>Result announcement and valedictory</span></div>
            </div>
          </article>
          <article className="event-detail-card">
            <span className="eyebrow">CHALLENGE OVERVIEW</span>
            <h3>What your team will build</h3>
            <p>Develop a Spring Boot-based enterprise web application illustrating microservices architecture with AI features and the Model-View-Controller (MVC) design pattern for the problem statement given.</p>
            <p>Participants may use appropriate AI/ML models, APIs or services based on the application and problem domain. The solution should demonstrate effective integration of:</p>
            <ul>
              <li>Spring Boot for enterprise application development</li>
              <li>MVC architecture for presentation and application flow</li>
              <li>Microservices for modular and scalable services</li>
              <li>AI/ML capabilities for intelligent functionality</li>
              <li>REST APIs for communication between services</li>
              <li>Database integration for persistent data management</li>
            </ul>
          </article>
          <details className="event-detail-card event-policy-card">
            <summary><span><span className="eyebrow">TECHNOLOGY POLICY</span><b>Mandatory stack, AI use and evaluation</b></span></summary>
            <div className="event-policy-content">
              <p>Participants are encouraged to use the following tools and technologies:</p>
              <ul>
                <li>Java and Spring Boot are the primary and mandatory technologies for the enterprise application and all microservices.</li>
                <li>Microservices must use Java with Spring Boot. Other programming languages or frameworks must not be used to develop the application’s microservices.</li>
                <li>RESTful APIs must be developed using Spring Boot to expose business functionality and enable communication between microservices.</li>
                <li>Thymeleaf must be used for frontend development and server-side view rendering.</li>
                <li>Use Spring Data JDBC/JPA for persistence with a relational database of choice: PostgreSQL, MySQL or Oracle.</li>
                <li>AI models may be integrated where appropriate to the problem statement and must be connected appropriately to the Java/Spring Boot application.</li>
                <li>Any IDE may be used.</li>
                <li>AI-assisted code development is not encouraged. If AI-generated or AI-assisted code is detected during evaluation, the group will be disqualified from evaluation. Participants must demonstrate their understanding, coding skills and ability to explain their implementation.</li>
              </ul>
            </div>
          </details>
          <details className="event-detail-card event-policy-card">
            <summary><span><span className="eyebrow">MODE OF OPERATION</span><b>Day 1 and Day 2 instructions</b></span></summary>
            <div className="event-policy-content">
              <h4>Day 1 · Saturday, October 10</h4>
              <ul>
                <li>Assemble at Silver Jubilee Tower, Sarojini Naidu Gallery, VIT, Vellore by 08:00 AM. All group members listed in the Google Form must be present and available throughout the event.</li>
                <li>Complete registration by 09:00 AM. Registration closes strictly at 09:00 AM; no entry is permitted afterward.</li>
                <li>The problem statement will be disclosed at 09:30 AM. Round 1 evaluation is at 12:00 noon, with results announced at 02:00 PM.</li>
                <li>Teams clearing Round 1 may submit a hostel accommodation request before 02:00 PM. Online accommodation payment is from 03:00 PM to 05:00 PM.</li>
                <li>Development at the designated venue may continue until 06:00 PM. After 06:00 PM, teams may continue at their accommodation premises.</li>
              </ul>
              <h4>Day 2 · Sunday, October 11</h4>
              <ul>
                <li>Assemble by 08:00 AM at Silver Jubilee Tower, Sarojini Naidu Gallery, VIT, Vellore.</li>
                <li>Each team has 15–20 minutes to present its application, followed by technical evaluation by the expert panel.</li>
                <li>Evaluation begins at 10:00 AM. Results and valedictory are by 05:00 PM.</li>
                <li>Evaluation parameters and criteria will be announced during the event.</li>
              </ul>
            </div>
          </details>
          <details className="event-detail-card event-policy-card">
            <summary><span><span className="eyebrow">HACKATHON CODE OF CONDUCT</span><b>Development, submission and campus rules</b></span></summary>
            <div className="event-policy-content">
              <ul>
                <li>Round 1 is a 2-hour-30-minute proof-of-concept/draft-prototype phase from the start of the hackathon.</li>
                <li>Teams clearing Round 1 may continue development and must complete the application within 24 hours from the start of the hackathon.</li>
                <li>Create a GitHub repository and commit all source code, configuration files and other required project files. No files or code may be committed after the specified deadline; late commits will not be considered.</li>
                <li>The application submitted for evaluation will be downloaded from the GitHub repository.</li>
                <li>Campus entry requires identity verification. Carry both a college ID card and a valid Government-issued ID card; both are mandatory.</li>
                <li>Each participant must bring their own laptop.</li>
                <li>Misconduct, unethical practices, malpractice or violations of the event code may result in action, including disqualification.</li>
              </ul>
              <h4>Conduct throughout the event</h4>
              <p>Maintain proper and respectful conduct throughout campus and for the entire event. Misconduct, inappropriate behaviour, indiscipline, unethical practice, violation of institutional or hostel rules, or other unacceptable conduct anywhere on campus results in immediate disqualification at the Organizing Committee’s discretion. Cooperate with the Organizing Committee, coordinators, faculty, expert panel, security personnel and hostel authorities. Follow the event guidelines strictly.</p>
            </div>
          </details>
          <details className="event-detail-card event-policy-card">
            <summary><span><span className="eyebrow">EXPECTATIONS &amp; OUTCOMES</span><b>What the complete solution should demonstrate</b></span></summary>
            <div className="event-policy-content">
              <p>A complete and fully integrated application implementing the required features is expected. AI models, services, APIs and other intelligent technologies should support and enhance application functionality for the problem statement; they must not replace participants’ understanding or coding skills.</p>
              <ul>
                <li>Develop and deploy enterprise-grade intelligent web applications using Spring Boot, AI and microservices-oriented architecture.</li>
                <li>Design RESTful APIs for business functionality, service communication and integration with relational databases.</li>
                <li>Apply AI meaningfully through prediction, recommendation, classification, anomaly detection, intelligent analysis or decision support.</li>
                <li>Apply MVC and industry practices to build scalable, maintainable and loosely coupled enterprise applications.</li>
                <li>Gain practical microservices experience with modular services, REST communication, service integration and loose coupling.</li>
                <li>Develop responsive interfaces using Thymeleaf, integrated with Spring Boot services and AI-powered functionality.</li>
                <li>Show how AI can enhance business processes, automate analysis and support intelligent decisions.</li>
                <li>Strengthen problem-solving, teamwork and software engineering through a real-world collaborative hackathon.</li>
              </ul>
            </div>
          </details>
          <details className="event-detail-card event-policy-card">
            <summary><span><span className="eyebrow">HOSTEL OPERATIONS</span><b>Accommodation, check-in and meals</b></span></summary>
            <div className="event-policy-content">
              <ul>
                <li>Accommodation payment is online from 03:00 PM to 05:00 PM on October 10, for participants seeking accommodation.</li>
                <li>Participants who have paid may approach the respective Ladies’ or Men’s Hostel only after 06:30 PM on October 10.</li>
                <li>Check-in: 07:00 PM on October 10. Check-out: 06:00 PM on October 11.</li>
                <li>The mess package includes dinner on October 10 and breakfast and lunch on October 11.</li>
                <li>Accommodation and mess facilities are not provided outside these times. Follow all hostel rules and regulations.</li>
              </ul>
            </div>
          </details>
          <details className="event-detail-card event-policy-card">
            <summary><span><span className="eyebrow">AWARDS, TRAVEL &amp; ACCOMMODATION</span><b>Prizes, certificates and participant expenses</b></span></summary>
            <div className="event-policy-content">
              <h4>Awards and certificates</h4>
              <ul>
                <li>Cash prizes will be awarded to the first- and second-place teams based on the Organizing Committee’s evaluation criteria.</li>
                <li>Participation certificates will be awarded to registered participants who successfully complete the hackathon.</li>
              </ul>
              <h4>Travel and accommodation policy</h4>
              <ul>
                <li>No reimbursement is provided for travel, food, accommodation or other incidental expenses related to the hackathon.</li>
                <li>Accommodation and food facilities on and around campus are on a payment basis, subject to prior request and availability.</li>
                <li>On October 10, outstation participants must make their own arrangements for refreshments, breakfast and lunch.</li>
                <li>Participants clearing Round 1 may use hostel and dining facilities on a payment basis, subject to availability and prior registration.</li>
              </ul>
            </div>
          </details>
        </div>
        <p className="event-guidelines-footer">Please read the instructions carefully and comply with the event guidelines. We look forward to your active participation in the GreenOps hackathon.</p>
        <section className="event-support" aria-labelledby="event-support-title">
          <div>
            <span className="eyebrow">NEED HELP?</span>
            <h3 id="event-support-title">Event support</h3>
            <p>For questions about the hackathon, contact the coordinators:</p>
          </div>
          <div className="event-support-contacts">
            <article className="event-support-contact">
              <h4>Dr. B. Senthil Murugan</h4>
              <a href="tel:+919047151090">+91 90471 51090</a>
              <a href="mailto:senthilmurugan.b@vit.ac.in">senthilmurugan.b@vit.ac.in</a>
            </article>
            <article className="event-support-contact">
              <h4>Dr. A. Vijayarani</h4>
              <a href="tel:+917904440327">+91 79044 40327</a>
              <a href="mailto:vijayarani.a@vit.ac.in">vijayarani.a@vit.ac.in</a>
            </article>
          </div>
        </section>
        <section className="event-support" aria-labelledby="technical-support-title">
          <div>
            <span className="eyebrow">WEBSITE HELP?</span>
            <h3 id="technical-support-title">Technical and website support</h3>
            <p>For help with the portal or website, contact:</p>
          </div>
          <div className="event-support-contacts">
            <article className="event-support-contact">
              <h4>Subarta Ghosh</h4>
              <a href="tel:+917319591361">+91 73195 91361</a>
              <a href="mailto:subarta.ghosh2025@vitstudent.ac.in">subarta.ghosh2025@vitstudent.ac.in</a>
            </article>
          </div>
        </section>
      </section>
    </main>
  );
}

function ProblemStatement({ statement }) {
  const normalized = String(statement || '')
    .replace(/\uFFFD/g, ':')
    .replace(/\?(?=\s*\d)/g, '₹')
    .trim();
  const lines = normalized.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  const blocks = [];
  let listItems = [];
  let listStart = 1;

  function flushList() {
    if (listItems.length) {
      blocks.push({ type: 'list', start: listStart, items: listItems });
      listItems = [];
    }
  }

  for (const line of lines) {
    const segments = line.split(/(?=\b\d{1,2}\.\s+)/);
    for (const segment of segments) {
      const text = segment.trim();
      if (!text) continue;
      const numberedItem = text.match(/^(\d{1,2})\.\s+(.+)$/);
      if (!numberedItem) {
        flushList();
        blocks.push({ type: text === 'Illustrative Example' ? 'heading' : 'paragraph', text });
        continue;
      }

      const number = Number(numberedItem[1]);
      if (!listItems.length) listStart = number;
      else if (number !== listStart + listItems.length) flushList();
      if (!listItems.length) listStart = number;

      const [heading, ...description] = numberedItem[2].split(/:\s*/, 2);
      listItems.push({ heading, description: description.join(': ') });
    }
  }
  flushList();

  return (
    <div className="problem-statement">
      {blocks.map((block, index) => block.type === 'list'
        ? <ol key={index} start={block.start}>{block.items.map((item, itemIndex) => <li key={itemIndex}>
          <strong>{item.heading}</strong>{item.description && <>: {item.description}</>}
        </li>)}</ol>
        : block.type === 'heading'
          ? <h3 key={index}>{block.text}</h3>
          : <p key={index}>{block.text}</p>)}
    </div>
  );
}

function Student({ team, credentials, logout, refreshSession, sessionSyncError }) {
  const [links, setLinks] = useState({ googleDriveLink: team.submission?.googleDriveLink || '', githubLink: team.submission?.githubLink || '' });
  const [roundTwoLinks, setRoundTwoLinks] = useState({
    googleDriveLink: team.roundTwoSubmission?.googleDriveLink || '',
    githubLink: team.roundTwoSubmission?.githubLink || ''
  });
  const [roundTwoSubmission, setRoundTwoSubmission] = useState(team.roundTwoSubmission || null);
  const [roundTwoSubmitting, setRoundTwoSubmitting] = useState(false);
  const [roundTwoNotice, setRoundTwoNotice] = useState('');
  const [roundTwoNoticeType, setRoundTwoNoticeType] = useState('success');
  const [notice, setNoticeState] = useState('');
  const [noticeType, setNoticeType] = useState('success');
  const [challengeNotice, setChallengeNotice] = useState('');
  const [challengeNoticeType, setChallengeNoticeType] = useState('success');
  function setNotice(message) {
    setNoticeState(message);
    setNoticeType('success');
  }
  function setErrorNotice(message) {
    setNoticeState(message);
    setNoticeType('error');
  }
  const [submitting, setSubmitting] = useState(false);
  const [refreshingChallenge, setRefreshingChallenge] = useState(false);
  useEffect(() => {
    setRoundTwoLinks({
      googleDriveLink: team.roundTwoSubmission?.googleDriveLink || '',
      githubLink: team.roundTwoSubmission?.githubLink || ''
    });
    setRoundTwoSubmission(team.roundTwoSubmission || null);
  }, [team.roundTwoSubmission?.updatedAt]);
  async function refreshChallenge() {
    if (refreshingChallenge) return;
    setRefreshingChallenge(true);
    try {
      const refreshedTeam = await refreshSession();
      if (refreshedTeam.problem) {
        setChallengeNotice('Your present-member roster and assigned challenge have been refreshed.');
        setChallengeNoticeType('success');
      } else {
        setChallengeNotice('Your present-member roster has been refreshed. No published challenge is available yet.');
        setChallengeNoticeType('error');
      }
    } catch (error) {
      setChallengeNotice(error.message);
      setChallengeNoticeType('error');
    } finally {
      setRefreshingChallenge(false);
    }
  }
  async function submit(event) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setNotice('');
    setNoticeType('');
    try {
      const submission = await request('/student/submission', { method: 'PUT', body: JSON.stringify({ ...credentials, ...links }) });
      team.submission = submission;
      setNotice('Submission links saved.');
      setNoticeType('success');
    } catch (error) { setNotice(error.message); setNoticeType('error'); }
    finally { setSubmitting(false); }
  }
  async function submitRoundTwo(event) {
    event.preventDefault();
    if (roundTwoSubmitting) return;
    setRoundTwoSubmitting(true);
    setRoundTwoNotice('');
    try {
      const saved = await request('/student/round-two/submission', {
        method: 'PUT',
        body: JSON.stringify({ ...credentials, ...roundTwoLinks })
      });
      setRoundTwoSubmission(saved);
      setRoundTwoNotice('Round 2 submission links saved.');
      setRoundTwoNoticeType('success');
    } catch (error) {
      setRoundTwoNotice(error.message);
      setRoundTwoNoticeType('error');
    } finally {
      setRoundTwoSubmitting(false);
    }
  }
  const registrationDetails = (team.importedFields || [])
    .filter(field => ![2, 3, 4, 5, 6, 7, 8, 9].includes(field.columnIndex))
    .filter(field => String(field.fieldValue ?? '').trim());
  return (
    <main className="app-shell">
      <header>
        <div className="brand event-app-brand"><img src="/vit-logo-transparent.png" alt="Vellore Institute of Technology" /><span>GreenOps<small>VIT HACKATHON</small></span></div>
        <div className="header-actions"><span className="session-label">Participant session active</span><button className="ghost" onClick={logout}>Sign out</button></div>
      </header>
      <div className="content">
        <div className="welcome">
          <span className="eyebrow">GREENOPS · TEAM SPACE</span>
          <h1>{team.name}</h1>
          <p className="muted">Your assigned problem, checked-in team members and final project submission.</p>
        </div>
        {sessionSyncError && <div className="error" role="alert">{sessionSyncError}</div>}
        <div className="student-event-banner">
          <span><b>OCTOBER 10 &amp; 11, 2026</b><small>Hackathon dates</small></span>
          <span><b>VIT, VELLORE</b><small>Silver Jubilee Tower · Sarojini Naidu Gallery</small></span>
          <span><b>SUBMISSION DEADLINE · 11 OCT, 09:30 AM</b><small>Complete development, upload the document and submit the GitHub repository</small></span>
        </div>
        <section className="card registration-details-card">
          <div className="card-top">
            <div><span className="eyebrow">TEAM REGISTRATION</span><h2>Shared team details</h2></div>
            <span className="pill">Visible to all team members</span>
          </div>
          {registrationDetails.length
            ? <dl className="registration-details-grid">{registrationDetails.map(field => <div key={field.columnIndex}>
              <dt>{field.fieldName}</dt><dd>{field.fieldValue}</dd>
            </div>)}</dl>
            : <div className="empty">No registration details are available for this team.</div>}
          <h3 className="team-members-heading">Present team members</h3>
          <p className="muted attendance-roster-note">The same attendance check-in applies to Round 1 and Round 2. Only members marked present by an Attendance Coordinator appear here; the roster syncs automatically.</p>
          {team.students.length
            ? <div className="members">{team.students.map(s => <div className="member" key={s.registerNumber}><span>{(s.name || s.registerNumber).charAt(0)}</span><div><b>{s.name || 'Name not provided'}</b><small>{s.registerNumber}{s.leader ? ' - Group leader' : ''}</small></div></div>)}</div>
            : <div className="empty">No team members have been marked present yet.</div>}
        </section>
        <div className="student-grid">
          <section className="card problem-card">
            <div className="card-top"><span className="eyebrow">YOUR GREENOPS CHALLENGE {team.problem ? `· #${team.problem.id}` : ''}</span><div className="problem-card-actions"><button type="button" className="small-button" onClick={refreshChallenge} disabled={refreshingChallenge}>{refreshingChallenge ? 'Refreshing…' : 'Refresh team view'}</button><span className="pill">{team.problem ? 'Ready' : 'Not published'}</span></div></div>
            {team.problem ? <><h2>{team.problem.title}</h2><ProblemStatement statement={team.problem.statement} /></> : <div className="empty">No published challenge is available. If the coordinator just assigned one, refresh here; the problem must also be enabled before participants can view it.</div>}
            {challengeNotice && <div className={challengeNoticeType} role={challengeNoticeType === 'error' ? 'alert' : 'status'}>{challengeNotice}</div>}
          </section>
          <form className="card submission-card" onSubmit={team.roundTwoOpen ? submitRoundTwo : submit}>
            <span className="eyebrow">GREENOPS · ROUND {team.roundTwoOpen ? '2' : '1'} ENTRY</span>
            <h2>{team.roundTwoOpen ? 'Submit your Round 2 project' : 'Submit your Round 1 project'}</h2>
            <p className="muted">Any team member using the shared team login can submit or update these links. Both links should be ready for the jury to review.</p>
            <label>Project document (Google Drive)<input required type="url" value={team.roundTwoOpen ? roundTwoLinks.googleDriveLink : links.googleDriveLink} onChange={event => team.roundTwoOpen
              ? setRoundTwoLinks(current => ({ ...current, googleDriveLink: event.target.value }))
              : setLinks(current => ({ ...current, googleDriveLink: event.target.value }))} /></label>
            <label>Source code (GitHub repository)<input required type="url" value={team.roundTwoOpen ? roundTwoLinks.githubLink : links.githubLink} onChange={event => team.roundTwoOpen
              ? setRoundTwoLinks(current => ({ ...current, githubLink: event.target.value }))
              : setLinks(current => ({ ...current, githubLink: event.target.value }))} /></label>
            {team.roundTwoOpen
              ? roundTwoNotice && <div className={roundTwoNoticeType} role={roundTwoNoticeType === 'error' ? 'alert' : 'status'}>{roundTwoNotice}</div>
              : notice && <div className={noticeType} role={noticeType === 'error' ? 'alert' : 'status'}>{notice}</div>}
            <button disabled={team.roundTwoOpen ? roundTwoSubmitting : submitting}>
              {team.roundTwoOpen
                ? roundTwoSubmitting ? 'Saving Round 2 links…' : roundTwoSubmission ? 'Update Round 2 submission' : 'Submit Round 2 links'
                : submitting ? 'Saving project links…' : team.submission ? 'Update Round 1 submission' : 'Submit Round 1 links'}
            </button>
          </form>
        </div>
        {team.roundTwoOpen && <section className="card round-two-student">
          <div className="card-top round-two-heading">
            <div>
              <span className="eyebrow">ROUND 2</span>
              <h2>{team.advancedToRoundTwo ? 'Your next-round workspace' : 'Advancement status'}</h2>
            </div>
            <span className={`pill ${team.advancedToRoundTwo && team.roundTwoOpen ? 'round-two-open' : 'round-two-closed'}`}>
              {team.advancedToRoundTwo ? 'Round 2 open' : 'Not advanced'}
            </span>
          </div>
          {!team.advancedToRoundTwo
            ? <p className="muted round-two-closed-note">
              You haven’t advanced to Round 2. Your Round 1 challenge and submission remain available in this workspace.
            </p>
            : team.problem
            ? <div className="round-two-problem">
              <h3>{team.problem.title}</h3>
              <ProblemStatement statement={team.problem.statement} />
            </div>
            : <div className="empty">Your team does not have a Round 1 problem statement assigned yet.</div>}
          {team.advancedToRoundTwo && team.roundTwoDeadline && <p className="muted round-two-deadline">
            Submission deadline: {new Date(team.roundTwoDeadline).toLocaleString()}
          </p>}
        </section>}
      </div>
    </main>
  );
}

const emptyStudent = { name: '', registerNumber: '', email: '' };
const importedFieldLabels = [
  'Timestamp',
  'Username',
  'Name of the Group Leader (As per SSLC Record- USE UPPERCASE FORMAT only)',
  'Register Number/Roll Number of the Group Leader',
  'Team Member 2 Name (As per SSLC Record- USE UPPERCASE FORMAT only)',
  'Team Member 2 Registration Number/Roll Number',
  'Team Member 3 Name (As per SSLC Record- USE UPPERCASE FORMAT only)',
  'Team Member 3 Registration Number/Roll Number',
  'Team Member 4 Name (As per SSLC Record- USE UPPERCASE FORMAT only)',
  'Team Member 4 Registration Number/Roll Number',
  'Primary Contact Number (preferably Whatsapp Number)',
  'Primary Email Id',
  'Programme',
  'Specialization (say for example CSE/ECE/EEE/ CSE SPEC. IN AI ML)',
  'Institution',
  'Payment Reference Number (Check your Payment Receipt- Refer Reference No column)',
  'Name of the Institute',
  'City',
  'State'
];

function importedFieldCategory(fieldName) {
  const normalized = fieldName.normalize('NFKD').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  if (normalized === 'timestamp') return 'Timestamp';
  if (normalized === 'username') return 'Username';
  if (normalized.includes('group leader') && normalized.includes('name')) return importedFieldLabels[2];
  if (normalized.includes('group leader') && (normalized.includes('regist') || normalized.includes('roll number'))) return importedFieldLabels[3];
  const memberNumber = normalized.match(/team member\s*(2|3|4)/);
  if (memberNumber) {
    const isName = normalized.includes('name');
    const index = 4 + (Number(memberNumber[1]) - 2) * 2 + (isName ? 0 : 1);
    return importedFieldLabels[index];
  }
  if (normalized.includes('primary contact number')) return importedFieldLabels[10];
  if (normalized.includes('primary email')) return importedFieldLabels[11];
  if (normalized === 'programme' || normalized === 'program') return importedFieldLabels[12];
  if (normalized.startsWith('specialization') || normalized.startsWith('specialisation')) return importedFieldLabels[13];
  if (normalized === 'institution') return importedFieldLabels[14];
  if (normalized.includes('payment reference')) return importedFieldLabels[15];
  if (normalized.includes('name of the institute') || normalized === 'name institute') return importedFieldLabels[16];
  if (normalized === 'city') return importedFieldLabels[17];
  if (normalized === 'state') return importedFieldLabels[18];
  return null;
}

function orderImportedFields(fields = []) {
  const matched = new Map();
  const usedIndexes = new Set();
  fields.forEach(field => {
    const category = importedFieldCategory(field.fieldName || '');
    if (category && !matched.has(category)) {
      matched.set(category, field);
      usedIndexes.add(field.columnIndex);
    }
  });
  let nextColumnIndex = fields.reduce((max, field) => Math.max(max, field.columnIndex), -1) + 1;
  const ordered = importedFieldLabels.map(fieldName => {
    const field = matched.get(fieldName);
    return field
      ? { ...field, fieldName }
      : { columnIndex: nextColumnIndex++, fieldName, fieldValue: '' };
  });
  return [...ordered, ...fields.filter(field => !usedIndexes.has(field.columnIndex))];
}

function LoadingState({ label }) {
  return <div className="loading-state" role="status"><span className="loading-spinner" aria-hidden="true" /><span>{label}</span></div>;
}

function LoadErrorState({ onRetry }) {
  return <div className="load-error-state" role="alert"><span>Couldn’t load this data.</span><button type="button" className="small-button" onClick={onRetry}>Try again</button></div>;
}

function Admin() {
  const [password, setPassword] = useState(() => readAdminSession()?.password || '');
  const [authed, setAuthed] = useState(() => Boolean(readAdminSession()));
  const [teams, setTeams] = useState([]);
  const [problems, setProblems] = useState([]);
  const [updatingProblemVisibility, setUpdatingProblemVisibility] = useState(false);
  const [newTeamFields, setNewTeamFields] = useState(() =>
    importedFieldLabels.map((fieldName, columnIndex) => ({ columnIndex, fieldName, fieldValue: '' }))
  );
  const [csvFile, setCsvFile] = useState(null);
  const [questionCsvFile, setQuestionCsvFile] = useState(null);
  const [importDialog, setImportDialog] = useState(null);
  const [actionDialog, setActionDialog] = useState(null);
  const [teamCreateError, setTeamCreateError] = useState('');
  const [student, setStudent] = useState(emptyStudent);
  const [newProblem, setNewProblem] = useState({ title: '', statement: '' });
  const [editingProblem, setEditingProblem] = useState(null);
  const [editingTeam, setEditingTeam] = useState(null);
  const [assigningLeaderId, setAssigningLeaderId] = useState(null);
  const [leaderActionError, setLeaderActionError] = useState('');
  const [savingImportedFields, setSavingImportedFields] = useState(false);
  const [importedFieldsError, setImportedFieldsError] = useState('');
  const [importedFieldsNotice, setImportedFieldsNotice] = useState('');
  const [dialog, setDialog] = useState(null);
  const [notice, setNoticeState] = useState('');
  const [noticeType, setNoticeType] = useState('success');
  const [loginSettings, setLoginSettings] = useState({ loginEnabled: true, startTime: '', endTime: '', attendancePasswordConfigured: false });
  const [attendancePassword, setAttendancePassword] = useState('');
  const [pauseUpdating, setPauseUpdating] = useState(false);
  const [submissions, setSubmissions] = useState([]);
  const [roundTwoSubmissions, setRoundTwoSubmissions] = useState([]);
  const [roundTwoSettings, setRoundTwoSettings] = useState({
    activeRound: '1', enabled: false, published: false, open: false,
    roundTwoStartTime: '', roundTwoEndTime: ''
  });
  const [downloadingSubmissions, setDownloadingSubmissions] = useState(false);
  const [downloadingRoundTwoSubmissions, setDownloadingRoundTwoSubmissions] = useState(false);
  const [downloadingTeams, setDownloadingTeams] = useState(false);
  const [submissionSearch, setSubmissionSearch] = useState('');
  const [roundTwoSubmissionSearch, setRoundTwoSubmissionSearch] = useState('');
  const [problemSearch, setProblemSearch] = useState('');
  const [teamSearch, setTeamSearch] = useState('');
  const [loadingData, setLoadingData] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const headers = () => ({ 'X-Admin-Password': password });
  const adminAttendanceStudents = teams.flatMap(team => team.students || []);
  const adminPresentCount = adminAttendanceStudents.filter(member => member.present).length;
  const adminAbsentCount = adminAttendanceStudents.length - adminPresentCount;
  const normalizedSubmissionSearch = submissionSearch.trim().toLowerCase();
  const submissionsByTeamId = new Map(submissions.map(item => [item.team?.id, item]));
  const filteredSubmissionTeams = teams.filter(team => {
    const submission = submissionsByTeamId.get(team.id);
    return `${team.name || ''} ${team.teamNumber || ''} ${submission?.googleDriveLink || ''} ${submission?.githubLink || ''}`
      .toLowerCase().includes(normalizedSubmissionSearch);
  });
  const normalizedProblemSearch = problemSearch.trim().toLowerCase();
  const filteredProblems = problems.filter(problem => (
    `${problem.id} ${problem.title || ''} ${problem.statement || ''}`
      .toLowerCase().includes(normalizedProblemSearch)
  ));
  const normalizedTeamSearch = teamSearch.trim().toLowerCase();
  const filteredTeams = teams.filter(team => (
    `${team.teamNumber || ''} ${team.name || ''} ${team.primaryEmail || ''} ${team.primaryContactNumber || ''} `
    + `${(team.importedFields || []).map(field => field.fieldValue || '').join(' ')} `
    + `${(team.students || []).map(member => `${member.name || ''} ${member.registerNumber || ''} ${member.email || ''}`).join(' ')}`
  ).toLowerCase().includes(normalizedTeamSearch));
  function setNotice(message) {
    setNoticeType('success');
    setNoticeState(message);
  }
  function setErrorNotice(message) {
    setNoticeType('error');
    setNoticeState(message);
  }
  useEffect(() => {
    if (!notice || noticeType === 'error') return undefined;
    const timeout = window.setTimeout(() => setNotice(''), 3500);
    return () => window.clearTimeout(timeout);
  }, [notice, noticeType]);
  useEffect(() => {
    if (!importDialog && !actionDialog) return undefined;
    const closeOnEscape = event => {
      if (event.key === 'Escape') {
        if (importDialog) closeImportDialog();
        else closeActionDialog();
      }
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [importDialog, actionDialog]);
  useEffect(() => {
    const savedSession = readAdminSession();
    if (!savedSession) return;
    load(savedSession.password).catch(error => {
      if (error.status === 401 || error.status === 403) {
        sessionStorage.removeItem(ADMIN_SESSION_KEY);
        setPassword('');
        setAuthed(false);
      }
      setErrorNotice(error.message);
    });
  }, []);
  useEffect(() => {
    if (!authed) sessionStorage.removeItem(ADMIN_SESSION_KEY);
  }, [authed]);
  useEffect(() => {
    const showRequestError = event => setErrorNotice(event.detail || 'The request failed.');
    const showUnhandledError = event => {
      const reason = event.reason;
      setErrorNotice(reason instanceof Error ? reason.message : 'The admin action failed.');
      event.preventDefault();
    };
    window.addEventListener('portal-request-error', showRequestError);
    window.addEventListener('unhandledrejection', showUnhandledError);
    return () => {
      window.removeEventListener('portal-request-error', showRequestError);
      window.removeEventListener('unhandledrejection', showUnhandledError);
    };
  }, []);
  useEffect(() => {
    if (!authed) setPassword('');
  }, [authed]);
  useEffect(() => {
    if (!authed || !password) return undefined;
    const timer = window.setInterval(() => {
      request('/admin/teams', { headers: headers() })
        .then(setTeams)
        .catch(error => setErrorNotice(error.message));
    }, 15000);
    return () => window.clearInterval(timer);
  }, [authed, password]);
  useEffect(() => {
    if (!authed || !password) return undefined;
    const timer = window.setInterval(() => {
      request('/admin/submissions', { headers: headers() })
        .then(setSubmissions)
        .catch(error => setErrorNotice(error.message));
      request('/admin/round-two/submissions', { headers: headers() })
        .then(setRoundTwoSubmissions)
        .catch(error => setErrorNotice(error.message));
    }, 30000);
    return () => window.clearInterval(timer);
  }, [authed, password]);
  async function load(adminPassword = password) {
    setLoadingData(true);
    setLoadError(false);
    const authHeaders = { 'X-Admin-Password': adminPassword };
    try {
      const [loadedTeams, loadedProblems, loadedRoundAccess, loadedSubmissions, loadedRoundTwoSubmissions] = await Promise.all([
        request('/admin/teams', { headers: authHeaders }),
        request('/admin/problems', { headers: authHeaders }),
        request('/admin/round-access/settings', { headers: authHeaders }),
        request('/admin/submissions', { headers: authHeaders }),
        request('/admin/round-two/submissions', { headers: authHeaders })
      ]);
      setTeams(loadedTeams); setProblems(loadedProblems); setLoginSettings(toLocalLoginSettings(loadedRoundAccess));
      setSubmissions(loadedSubmissions); setRoundTwoSettings(toLocalRoundTwoSettings(loadedRoundAccess));
      setRoundTwoSubmissions(loadedRoundTwoSubmissions);
      saveAdminSession(adminPassword);
      setAuthed(true);
    } catch (error) {
      setLoadError(true);
      throw error;
    } finally {
      setLoadingData(false);
    }
  }
  async function saveRoundTwoSettings(event) {
    event.preventDefault();
    const saved = await request('/admin/round-access/settings', {
      method: 'PUT',
      headers: headers(),
      body: JSON.stringify({
        ...toServerLoginSettings({
          ...loginSettings,
          loginEnabled: roundTwoSettings.activeRound === '1'
        }, attendancePassword),
        ...toServerRoundTwoSettings({
          ...roundTwoSettings,
          enabled: roundTwoSettings.activeRound === '2'
        }),
        activeRound: roundTwoSettings.activeRound
      })
    });
    setLoginSettings(toLocalLoginSettings(saved));
    setRoundTwoSettings(toLocalRoundTwoSettings(saved));
    setAttendancePassword('');
    setNotice(saved.open
      ? 'Round 2 is active for advanced teams. Round 1 access is closed.'
      : saved.activeRound === '1'
        ? 'Round 1 is active for all teams. Round 2 access is closed.'
        : 'Round settings saved.');
  }
  async function toggleParticipantAccessPause() {
    setPauseUpdating(true);
    try {
      const saved = await request('/admin/settings/pause', {
        method: 'PUT',
        headers: headers(),
        body: JSON.stringify({ paused: !loginSettings.accessPaused })
      });
      setLoginSettings(current => ({ ...current, accessPaused: saved.accessPaused }));
      setNotice(saved.accessPaused
        ? 'Participant access is paused immediately.'
        : 'Participant access resumed; the saved schedule or manual setting applies.');
    } catch (error) {
      setErrorNotice(error.message);
    } finally {
      setPauseUpdating(false);
    }
  }
  function confirmClearAll(label, count) {
    return window.confirm(`Clear all ${count} ${label}? This cannot be undone.`)
      && window.confirm(`Please confirm again: permanently delete all ${count} ${label}.`);
  }
  async function downloadSubmissions() {
    setDownloadingSubmissions(true);
    try {
      const response = await fetch(`${API}/admin/submissions/export`, { headers: headers() });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message || 'Could not download submissions.');
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'hackathon-submissions.xlsx';
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setNotice('Submissions Excel file downloaded.');
    } catch (error) {
      setErrorNotice(error.message);
    } finally {
      setDownloadingSubmissions(false);
    }
  }
  async function clearSubmissions() {
    if (!confirmClearAll('submissions', submissions.length)) return;
    await request('/admin/submissions', { method: 'DELETE', headers: headers() });
    setSubmissions([]);
    setNotice('All submissions cleared.');
  }
  async function downloadRoundTwoSubmissions() {
    setDownloadingRoundTwoSubmissions(true);
    try {
      const response = await fetch(`${API}/admin/round-two/submissions/export`, { headers: headers() });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message || 'Could not download Round 2 submissions.');
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'hackathon-round-two-submissions.xlsx';
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setNotice('Round 2 submissions Excel file downloaded.');
    } catch (error) {
      setErrorNotice(error.message);
    } finally {
      setDownloadingRoundTwoSubmissions(false);
    }
  }
  async function downloadTeams() {
    setDownloadingTeams(true);
    try {
      const response = await fetch(`${API}/admin/teams/export`, { headers: headers() });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message || 'Could not download the team roster.');
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'hackathon-teams.xlsx';
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setNotice('Team roster Excel file downloaded.');
    } catch (error) {
      setErrorNotice(error.message);
    } finally {
      setDownloadingTeams(false);
    }
  }
  async function clearRoundTwoSubmissions() {
    if (!confirmClearAll('Round 2 submissions', roundTwoSubmissions.length)) return;
    try {
      await request('/admin/round-two/submissions', { method: 'DELETE', headers: headers() });
      setRoundTwoSubmissions([]);
      setNotice('All Round 2 submissions cleared. Round 1 submissions are unchanged.');
    } catch (error) {
      setErrorNotice(error.message);
    }
  }
  async function toggleAllProblems() {
    const enabled = !problems.every(problem => problem.enabled);
    setUpdatingProblemVisibility(true);
    try {
      const updatedProblems = await request('/admin/problems/enabled', {
        method: 'PUT',
        headers: headers(),
        body: JSON.stringify({ enabled })
      });
      setProblems(updatedProblems);
      setNotice(enabled ? 'All problem statements are visible to participants.' : 'All problem statements are hidden from participants.');
    } catch (error) {
      setErrorNotice(error.message);
    } finally {
      setUpdatingProblemVisibility(false);
    }
  }
  async function createTeam(e) {
    e.preventDefault();
    setTeamCreateError('');
    const primaryContact = newTeamFields[10].fieldValue.trim();
    const primaryEmail = newTeamFields[11].fieldValue.trim();
    if (!primaryContact || !primaryEmail) {
      setTeamCreateError('Primary contact number and primary email are required.');
      return;
    }
    const normalizedContact = primaryContact.replace(/\D/g, '');
    const duplicateContact = teams.some(team => (team.primaryContactNumber || '').replace(/\D/g, '') === normalizedContact);
    if (duplicateContact) {
      setTeamCreateError('This primary contact number is already assigned to another team.');
      return;
    }
    const duplicateEmail = teams.some(team => (team.primaryEmail || '').trim().toLowerCase() === primaryEmail.toLowerCase());
    if (duplicateEmail) {
      setTeamCreateError('This primary email is already assigned to another team.');
      return;
    }
    if (Boolean(newTeamFields[2].fieldValue.trim()) !== Boolean(newTeamFields[3].fieldValue.trim())) {
      setTeamCreateError('Enter both the group leader’s name and register number, or leave both blank.');
      return;
    }
    const participantRegisters = [3, 5, 7, 9]
      .map(index => newTeamFields[index].fieldValue.trim().toUpperCase())
      .filter(Boolean);
    if (new Set(participantRegisters).size !== participantRegisters.length) {
      setTeamCreateError('Each participant must have a unique register number. Check the leader and member register numbers.');
      return;
    }
    for (let memberIndex = 4; memberIndex <= 8; memberIndex += 2) {
      const name = newTeamFields[memberIndex].fieldValue.trim();
      const registerNumber = newTeamFields[memberIndex + 1].fieldValue.trim();
      if (Boolean(name) !== Boolean(registerNumber)) {
        setTeamCreateError(`Enter both the name and register number, or leave both blank, for team member ${memberIndex / 2}.`);
        return;
      }
    }
    let created;
    try {
      created = await request('/admin/teams', {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify({ importedFields: newTeamFields })
      });
    } catch (error) {
      const message = /already assigned/i.test(error.message)
        ? `${error.message} Edit the existing team instead of registering this participant again.`
        : error.message;
      setTeamCreateError(message);
      setErrorNotice(message);
      return;
    }
    setTeams([...teams, created]);
    setNewTeamFields(importedFieldLabels.map((fieldName, columnIndex) => ({ columnIndex, fieldName, fieldValue: '' })));
    setActionDialog(null);
    setNotice('Team created with participant details.');
  }
  async function importTeams(e) {
    e.preventDefault();
    setNotice('');
    if (!csvFile) return setNotice('Choose a CSV or Excel workbook first.');
    const form = e.currentTarget;
    const formData = new FormData();
    formData.append('file', csvFile);
    const controls = [...form.querySelectorAll('button, input')];
    const disabledStates = controls.map(control => control.disabled);
    controls.forEach(control => { control.disabled = true; });
    const progress = document.createElement('div');
    progress.className = 'loading-state import-progress';
    progress.setAttribute('role', 'status');
    progress.setAttribute('aria-live', 'polite');
    const spinner = document.createElement('span');
    spinner.className = 'loading-spinner';
    spinner.setAttribute('aria-hidden', 'true');
    const progressText = document.createElement('span');
    progressText.textContent = 'Importing teams. Please wait…';
    progress.append(spinner, progressText);
    form.after(progress);
    try {
      const imported = await request('/admin/teams/import', { method: 'POST', headers: headers(), body: formData });
      setTeams([...teams, ...imported]);
      setCsvFile(null);
      setImportDialog(null);
      form.reset();
      setNotice(`${imported.length} teams imported.`);
    } catch (error) {
      const message = /already assigned|appears more than once/i.test(error.message)
        ? `Team import failed: ${error.message} No teams were imported. Remove the repeated participant or edit their existing team.`
        : `Team import failed: ${error.message} No teams were imported.`;
      setErrorNotice(message);
    } finally {
      progress.remove();
      controls.forEach((control, index) => { control.disabled = disabledStates[index]; });
    }
  }
  function downloadTeamImportTemplate() {
    const csv = importedFieldLabels
      .map(label => `"${label.replaceAll('"', '""')}"`)
      .join(',');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'team-import-template.csv';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }
  async function importQuestions(e) {
    e.preventDefault();
    if (!questionCsvFile) return setNotice('Choose a question CSV file first.');
    const form = e.currentTarget;
    const formData = new FormData();
    formData.append('file', questionCsvFile);
    try {
      const imported = await request('/admin/problems/import', { method: 'POST', headers: headers(), body: formData });
      setProblems([...problems, ...imported]);
      setQuestionCsvFile(null);
      setImportDialog(null);
      form.reset();
      setNotice(`${imported.length} questions imported.`);
    } catch (error) {
      setErrorNotice(`Question import failed: ${error.message} No questions were imported.`);
    }
  }
  function closeImportDialog() {
    if (document.querySelector('.import-progress')) return;
    setImportDialog(null);
    setCsvFile(null);
    setQuestionCsvFile(null);
  }
  function closeActionDialog() {
    setActionDialog(null);
    setEditingProblem(null);
    setTeamCreateError('');
  }
  async function randomlyAssignQuestions() {
    const unassignedCount = teams.filter(team => !team.problem).length;
    if (!unassignedCount) {
      setNotice('All teams already have questions assigned.');
      return;
    }
    if (!confirm(`Assign questions to ${unassignedCount} unassigned team${unassignedCount === 1 ? '' : 's'}? Existing assignments will be kept.`)) return;
    const assigned = await request('/admin/teams/random-assignment', { method: 'PUT', headers: headers() });
    setTeams(assigned);
    setNotice('Questions assigned evenly to the teams that were still unassigned.');
  }
  async function deleteTeam(id) {
    if (!confirm('Delete this team and all its participants?')) return;
    await request(`/admin/teams/${id}`, { method: 'DELETE', headers: headers() });
    setTeams(teams.filter(team => team.id !== id)); setNotice('Team deleted.');
  }
  async function clearTeams() {
    if (!confirmClearAll('teams and all their participants', teams.length)) return;
    await request('/admin/teams', { method: 'DELETE', headers: headers() });
    setTeams([]);
    setNotice('All teams cleared.');
  }
  async function renameTeam(team) {
    setDialog({ type: 'renameTeam', title: 'Rename team', team, fields: [{ name: 'name', label: 'Team name', value: team.name }] });
  }
  async function saveDialog(e) {
    e.preventDefault();
    const values = Object.fromEntries(new FormData(e.currentTarget));
    if (dialog.type === 'renameTeam') {
      if (!values.name || values.name === dialog.team.name) return setDialog(null);
      const updated = await request(`/admin/teams/${dialog.team.id}`, { method: 'PUT', headers: headers(), body: JSON.stringify({ name: values.name }) });
      setTeams(teams.map(item => item.id === updated.id ? updated : item));
      setNotice('Team renamed.');
    } else if (dialog.type === 'editStudent') {
      if (!values.name?.trim()) {
        setNotice('Participant name is required for sign-in.');
        return;
      }
      try {
        const updated = await request(`/admin/students/${dialog.student.id}`, { method: 'PUT', headers: headers(), body: JSON.stringify(values) });
        const team = teams.find(item => item.students.some(student => student.id === updated.id));
        if (team) {
          const refreshedTeam = await request(`/admin/teams/${team.id}`, { headers: headers() });
          setTeams(current => current.map(item => item.id === refreshedTeam.id ? refreshedTeam : item));
          setEditingTeam(current => current?.id === refreshedTeam.id ? refreshedTeam : current);
        }
        setNotice('Student updated.');
      } catch (error) {
        setErrorNotice(error.message);
      }
    }
    setDialog(null);
  }
  async function addStudent(e, teamId) {
    e.preventDefault();
    if (!student.name.trim()) {
      setNotice('Participant name is required for sign-in.');
      return;
    }
    const updated = await request(`/admin/teams/${teamId}/students`, { method: 'POST', headers: headers(), body: JSON.stringify(student) });
    setTeams(teams.map(team => team.id === teamId ? updated : team)); setEditingTeam(updated); setStudent(emptyStudent); setNotice('Participant added.');
  }
  async function deleteStudent(id) {
    if (!confirm('Delete this student?')) return;
    try {
      await request(`/admin/students/${id}`, { method: 'DELETE', headers: headers() });
      const team = teams.find(item => item.students.some(student => student.id === id));
      if (team) {
        const refreshedTeam = await request(`/admin/teams/${team.id}`, { headers: headers() });
        setTeams(current => current.map(item => item.id === refreshedTeam.id ? refreshedTeam : item));
        setEditingTeam(current => current?.id === refreshedTeam.id ? refreshedTeam : current);
      }
      setNotice('Participant deleted.');
    } catch (error) {
      setErrorNotice(error.message);
    }
  }
  async function saveImportedFields(event) {
    event.preventDefault();
    if (!editingTeam) return;
    setSavingImportedFields(true);
    setImportedFieldsError('');
    setImportedFieldsNotice('');
    try {
      const updated = await request(`/admin/teams/${editingTeam.id}/imported-fields`, {
        method: 'PUT',
        headers: headers(),
        body: JSON.stringify({ fields: orderImportedFields(editingTeam.importedFields || []) })
      });
      setTeams(current => current.map(team => team.id === updated.id ? updated : team));
      setEditingTeam(updated);
      setImportedFieldsNotice('Team details saved.');
      setNotice('Team spreadsheet details saved.');
    } catch (error) {
      setImportedFieldsError(error.message);
      setErrorNotice(error.message);
    } finally {
      setSavingImportedFields(false);
    }
  }
  async function assignLeader(studentId) {
    setAssigningLeaderId(studentId);
    setLeaderActionError('');
    try {
      const updated = await request(`/admin/students/${studentId}/leader`, { method: 'PUT', headers: headers() });
      if (!updated?.id || !Array.isArray(updated.students)) {
        throw new Error('The server returned an invalid team after assigning the leader. Please reload and try again.');
      }
      setTeams(current => current.map(team => team.id === updated.id ? updated : team));
      setEditingTeam(current => current?.id === updated.id ? updated : current);
      setNotice('Team leader assigned.');
    } catch (error) {
      setLeaderActionError(error.message);
      setErrorNotice(error.message);
    } finally {
      setAssigningLeaderId(null);
    }
  }
  async function editStudent(item) {
    setDialog({
      type: 'editStudent',
      title: 'Edit member',
      student: item,
      fields: [
        { name: 'name', label: 'Participant name', value: item.name },
        { name: 'registerNumber', label: 'Register number', value: item.registerNumber },
        { name: 'email', label: 'Email', value: item.email || '' }
      ]
    });
  }
  async function saveProblem(e) {
    e.preventDefault();
    const method = editingProblem ? 'PUT' : 'POST';
    const path = editingProblem ? `/admin/problems/${editingProblem.id}` : '/admin/problems';
    const saved = await request(path, { method, headers: headers(), body: JSON.stringify(newProblem) });
    setProblems(editingProblem ? problems.map(item => item.id === saved.id ? saved : item) : [...problems, saved]);
    setNewProblem({ title: '', statement: '' }); setEditingProblem(null); setActionDialog(null); setNotice('Problem saved.');
  }
  async function deleteProblem(id) {
    if (!confirm('Delete this problem statement?')) return;
    await request(`/admin/problems/${id}`, { method: 'DELETE', headers: headers() });
    setProblems(problems.filter(problem => problem.id !== id));
    setTeams(teams.map(team => team.problem?.id === id ? { ...team, problem: null } : team));
  }
  async function clearProblems() {
    if (!confirmClearAll('problem statements and remove all team assignments', problems.length)) return;
    await request('/admin/problems', { method: 'DELETE', headers: headers() });
    setProblems([]);
    setTeams(teams.map(team => ({ ...team, problem: null })));
    setNotice('All problem statements cleared.');
  }
  async function assign(teamId, problemId) {
    const updated = await request(problemId ? `/admin/teams/${teamId}/problem/${problemId}` : `/admin/teams/${teamId}/problem`, { method: problemId ? 'PUT' : 'DELETE', headers: headers() });
    setTeams(teams.map(team => team.id === teamId ? updated : team));
    const selectedProblem = problems.find(problem => String(problem.id) === String(problemId));
    setNotice(problemId
      ? selectedProblem?.enabled
        ? 'Problem assigned. Participants already signed in can refresh their challenge.'
        : 'Problem assigned but hidden. Enable it in the Problem Library when it is ready for participants to view.'
      : 'Problem assignment removed.');
    setNoticeType('success');
  }
  async function setRoundTwoStatus(teamId, status) {
    const updated = await request(`/admin/teams/${teamId}/round-two/qualification`, {
      method: 'PUT',
      headers: headers(),
      body: JSON.stringify({ status })
    });
    setTeams(current => current.map(team => team.id === updated.id ? updated : team));
    setNotice(status === 'advanced'
      ? `Team ${updated.teamNumber} advanced to Round 2.`
      : status === 'not_advanced'
        ? `Team ${updated.teamNumber} marked as not advancing. Existing round data is retained.`
        : `Team ${updated.teamNumber} qualification reset to pending.`);
  }
  if (!authed) return <main className="login-shell admin-login"><form className="card login-card" onSubmit={e => { e.preventDefault(); load().catch(err => setErrorNotice(err.message)); }}><span className="eyebrow">GREENOPS · COORDINATOR PORTAL</span><h2>Admin access.</h2><p className="muted">Manage GreenOps teams, participants, submissions and problem statements.</p><label>Admin password<input type="password" required value={password} onChange={e => setPassword(e.target.value)} /></label>{notice && <div className="error">{notice}</div>}<button disabled={loadingData}>{loadingData ? 'Connecting…' : 'Open dashboard'} <span>&gt;</span></button></form></main>;
  return <main className="app-shell">  <header><div className="brand"><img src="/vit-logo-transparent.png" alt="Vellore Institute of Technology" /><span>GREENOPS</span></div><div className="header-actions"><button className="ghost" onClick={() => setAuthed(false)}>Sign out</button></div></header>  <div className="content admin-content"><div className="welcome"><span className="eyebrow">GREENOPS · COORDINATOR PORTAL</span><h1>Command center</h1><p className="muted">Manage GreenOps participants, problem statements and team submissions.</p></div>{notice && <div className={noticeType} role={noticeType === 'error' ? 'alert' : 'status'}>{notice}</div>}  <RoundTwoManagement settings={roundTwoSettings} onSettingsChange={setRoundTwoSettings} loginSettings={loginSettings} onLoginSettingsChange={setLoginSettings} onSave={saveRoundTwoSettings} onTogglePause={toggleParticipantAccessPause} pauseUpdating={pauseUpdating} attendancePassword={attendancePassword} onAttendancePasswordChange={setAttendancePassword} submissions={roundTwoSubmissions} search={roundTwoSubmissionSearch} onSearchChange={setRoundTwoSubmissionSearch} onDownload={downloadRoundTwoSubmissions} onClear={clearRoundTwoSubmissions} downloading={downloadingRoundTwoSubmissions} /><section className="card attendance-toolbar admin-attendance-summary"><div><span className="eyebrow">TEAM ATTENDANCE</span><h2>Attendance overview</h2><p className="muted">Present/absent totals across all teams.</p></div><div className="attendance-counts"><span><b>{adminPresentCount}</b><small>Present</small></span><span><b>{adminAbsentCount}</b><small>Absent</small></span><span><b>{adminAttendanceStudents.length}</b><small>Total members</small></span></div></section><section className="card table-card"><div className="card-top"><div><span className="eyebrow">ROUND 1 SUBMISSIONS</span><h2>Round 1 submissions</h2></div><div className="problem-library-actions"><span className="pill">{loadingData ? 'Loading…' : submissions.length + ' submitted'}</span><button type="button" className="small-button" onClick={downloadSubmissions} disabled={downloadingSubmissions}>{downloadingSubmissions ? 'Downloading...' : 'Download Excel'}</button><button type="button" className="small-button danger-button" onClick={clearSubmissions} disabled={loadingData}>Clear all</button></div></div><label className="admin-search-field">Search submissions<input type="search" value={submissionSearch} onChange={event => setSubmissionSearch(event.target.value)} placeholder="Team name, number, Drive or GitHub link" /></label>{loadingData ? <LoadingState label="Loading team submissions…" /> : loadError ? <LoadErrorState onRetry={() => load().catch(error => setErrorNotice(error.message))} /> : teams.length === 0 ? <div className="empty">No teams available.</div> : filteredSubmissionTeams.length === 0 ? <div className="empty">No teams match your search.</div> : <div className="table-wrap"><table><thead><tr><th>Group</th><th>Round 2 status</th><th>Google Drive</th><th>GitHub</th><th>Updated</th></tr></thead><tbody>{filteredSubmissionTeams.map(team => { const submission = submissionsByTeamId.get(team.id); return <tr key={team.id}><td><b>{team.name || `#${team.teamNumber || ''}`}</b></td><td><select aria-label={`Round 2 status for team ${team.teamNumber}`} value={team.roundTwoStatus || 'pending'} onChange={event => setRoundTwoStatus(team.id, event.target.value)}><option value="pending">Pending review</option><option value="advanced">Advanced</option><option value="not_advanced">Not advanced</option></select></td><td>{submission?.googleDriveLink ? <a href={submission.googleDriveLink} target="_blank" rel="noreferrer">Open document</a> : <span className="muted">Not submitted</span>}</td><td>{submission?.githubLink ? <a href={submission.githubLink} target="_blank" rel="noreferrer">Open repository</a> : <span className="muted">Not submitted</span>}</td><td>{submission?.updatedAt ? new Date(submission.updatedAt).toLocaleString() : '-'}</td></tr>;})}</tbody></table></div>}</section><section className="card table-card"><div className="card-top library-card-top"><div><span className="eyebrow">PROBLEM LIBRARY</span><h2>Problem statements</h2></div>  <div className="problem-library-actions"><span className="pill">{loadingData ? 'Loading…' : problems.length + ' questions'}</span><button type="button" className="small-button bulk-problem-toggle" disabled={loadingData || problems.length === 0 || updatingProblemVisibility} onClick={toggleAllProblems}>{updatingProblemVisibility ? 'Updating?' : problems.length > 0 && problems.every(problem => problem.enabled) ? 'Disable all' : 'Enable all'}</button><button type="button" className="small-button" onClick={() => { setEditingProblem(null); setNewProblem({ title: '', statement: '' }); setActionDialog('problem'); }}>Add problem</button><button type="button" className="small-button" onClick={() => setImportDialog('questions')}>Import questions</button>  <button className="small-button" onClick={randomlyAssignQuestions}>Randomly assign evenly</button><button className="small-button danger-button" onClick={clearProblems}>Clear all</button></div></div><label className="admin-search-field">Search problem library<input type="search" value={problemSearch} onChange={event => setProblemSearch(event.target.value)} placeholder="Question ID, title or statement" /></label><div className="problem-list">{loadingData ? <LoadingState label="Loading problem statements…" /> : loadError ? <LoadErrorState onRetry={() => load().catch(error => setErrorNotice(error.message))} /> : problems.length === 0 ? <div className="empty">No problem statements yet.</div> : filteredProblems.length === 0 ? <div className="empty">No problems match your search.</div> : filteredProblems.map(problem => <div className="problem-row" key={problem.id}><div><b>#{problem.id}  -  {problem.title}</b><ProblemStatement statement={problem.statement} /></div><div className="row-actions">  <button className="small-button" onClick={() => { setEditingProblem(problem); setNewProblem({ title: problem.title, statement: problem.statement }); setActionDialog('problem'); }}>Edit</button><button className="small-button danger-button" onClick={() => deleteProblem(problem.id)}>Delete</button></div></div>)}</div></section>  <section className="card table-card"><div className="card-top library-card-top"><div><span className="eyebrow">TEAM ROSTER</span>  <h2>Teams and assignments</h2></div><div className="problem-library-actions"><span className="pill">{loadingData ? 'Loading…' : teams.length + ' teams'}</span><button type="button" className="small-button" onClick={() => setActionDialog('team')}>Create team</button><button type="button" className="small-button" onClick={() => setImportDialog('teams')}>Import teams</button><button type="button" className="small-button" onClick={downloadTeams} disabled={downloadingTeams}>{downloadingTeams ? 'Exporting…' : 'Export teams Excel'}</button><button className="small-button danger-button" onClick={clearTeams}>Clear all</button></div></div><label className="admin-search-field">Search team roster<input type="search" value={teamSearch} onChange={event => setTeamSearch(event.target.value)} placeholder="Team, participant, register number, email or contact" /></label><div className="table-wrap"><table><thead><tr><th>Team</th><th>Present / total</th><th>Problem</th><th>Actions</th></tr></thead><tbody>{loadingData ? <tr><td colSpan="4"><LoadingState label="Loading teams…" /></td></tr> : loadError ? <tr><td colSpan="4"><LoadErrorState onRetry={() => load().catch(error => setErrorNotice(error.message))} /></td></tr> : teams.length === 0 ? <tr><td colSpan="4"><div className="empty">No teams yet.</div></td></tr> : filteredTeams.length === 0 ? <tr><td colSpan="4"><div className="empty">No teams match your search.</div></td></tr> : filteredTeams.map(team =>   <tr key={team.id}><td><b>#{team.teamNumber}  -  {team.name}</b></td><td>{team.students.filter(member => member.present).length} / {team.students.length}</td><td><select value={team.problem?.id || ''} onChange={e => assign(team.id, e.target.value)}><option value="">Unassigned</option>{problems.map(p => <option key={p.id} value={p.id}>{p.id}  -  {p.title}</option>)}</select></td>  <td><div className="row-actions"><button className="small-button" onClick={() => setEditingTeam(team)}>Edit</button><button className="small-button" onClick={() => renameTeam(team)}>Rename</button><button className="small-button danger-button" onClick={() => deleteTeam(team.id)}>Delete</button></div></td></tr>)}</tbody></table></div></section></div>  {editingTeam && <div className="modal-backdrop" onClick={() => setEditingTeam(null)}><section className="member-modal card" onClick={e => e.stopPropagation()}><div className="card-top"><div><span className="eyebrow">EDIT MEMBERS</span><h2>{editingTeam.name}</h2></div><button type="button" className="ghost" onClick={() => setEditingTeam(null)}>Close</button></div><form className="student-form modal-student-form" onSubmit={e => addStudent(e, editingTeam.id)}><input required placeholder="Participant name" value={student.name} onChange={e => setStudent({ ...student, name: e.target.value })} /><input required placeholder="Register number" value={student.registerNumber} onChange={e => setStudent({ ...student, registerNumber: e.target.value })} /><input required={false} type="email" placeholder="Email (optional)" value={student.email} onChange={e => setStudent({ ...student, email: e.target.value })} />  <button>Add participant</button></form>{leaderActionError && <div className="error" role="alert">{leaderActionError}</div>}{editingTeam.students.length === 0 ? <div className="empty">No participants in this team yet.</div> : <div className="modal-member-list">{editingTeam.students.map(item => <div className="modal-member" key={item.id}><div><b>{item.name}</b>  <small>{item.registerNumber}  -  {item.email || 'No email'}{item.leader ? '  -  Leader' : ''}  -  {item.present ? 'Present' : 'Absent'}</small></div><div className="row-actions">{!item.leader && <button type="button" className="small-button" disabled={assigningLeaderId !== null} onClick={() => assignLeader(item.id)}>{assigningLeaderId === item.id ? 'Assigning…' : 'Assign as leader'}</button>}<button type="button" className="small-button" onClick={() => editStudent(item)}>Edit</button><button type="button" className="small-button danger-button" onClick={() => deleteStudent(item.id)}>Remove</button></div></div>)}</div>}{editingTeam.importedFields?.length > 0 && <form className="imported-fields-form" onSubmit={saveImportedFields}><details open><summary>Team details ({importedFieldLabels.length} editable fields)</summary><div className="imported-fields-grid">{orderImportedFields(editingTeam.importedFields || []).slice(0, importedFieldLabels.length).map(field => <label className="imported-field" key={field.columnIndex}><b>{field.fieldName}</b><input maxLength={5000} value={field.fieldValue || ''} onChange={event => setEditingTeam(current => current ? { ...current, importedFields: orderImportedFields(current.importedFields || []).map(item => item.columnIndex === field.columnIndex ? { ...item, fieldValue: event.target.value } : item) } : current)} /></label>)}</div>  </details>{importedFieldsError && <div className="error" role="alert">{importedFieldsError}</div>}{importedFieldsNotice && <div className="success" role="status">{importedFieldsNotice}</div>}<button type="submit" className="small-button" disabled={savingImportedFields}>{savingImportedFields ? 'Saving…' : 'Save team details'}</button></form>}</section></div>}{dialog && <div className="modal-backdrop" onClick={() => setDialog(null)}><form className="card dialog-modal" onClick={e => e.stopPropagation()} onSubmit={saveDialog}><div className="card-top"><h2>{dialog.title}</h2><button type="button" className="ghost" onClick={() => setDialog(null)}>Close</button></div>{dialog.fields.map(field => <label key={field.name}>{field.label}<input required={field.name === 'registerNumber' || field.name === 'name'} type={field.name === 'password' ? 'password' : field.name === 'email' ? 'email' : 'text'} name={field.name} defaultValue={field.value} /></label>)}<button>Save</button></form></div>}{actionDialog && <div className="modal-backdrop import-modal-backdrop" onClick={closeActionDialog}><section className="card import-modal action-modal" role="dialog" aria-modal="true" aria-labelledby="action-dialog-title" onClick={e => e.stopPropagation()}><div className="card-top import-modal-header"><div><span className="eyebrow">COORDINATOR DASHBOARD</span><h2 id="action-dialog-title">{actionDialog === 'team' ? 'Create a team' : editingProblem ? 'Edit problem statement' : 'Add a problem statement'}</h2></div><button type="button" className="ghost" onClick={closeActionDialog}>Close</button></div>  <p className="muted">{actionDialog === 'team' ? 'Enter the registration details and participant roster. Leader name, register number and primary email are required; Username is optional.' : 'Give the problem a short title and describe the challenge for participants.'}</p>{actionDialog === 'team' ? <form className="action-dialog-form team-create-form" onSubmit={createTeam}>{teamCreateError && <div className="error" role="alert">{teamCreateError}</div>}<div className="team-create-fields">{newTeamFields.map((field, index) => <label key={field.columnIndex}>{field.fieldName}<input autoFocus={index === 2} type={index === 11 ? 'email' : index === 10 ? 'tel' : 'text'} required={index === 2 || index === 3 || index === 11} placeholder={index >= 4 && index <= 9 ? 'Leave both fields blank if this member is not part of the team' : ''} value={field.fieldValue} onChange={e => setNewTeamFields(current => current.map((item, fieldIndex) => fieldIndex === index ? { ...item, fieldValue: e.target.value } : item))} /></label>)}</div><div className="import-modal-actions"><button type="button" className="small-button" onClick={closeActionDialog}>Cancel</button><button className="secondary-button">Create team</button></div></form> : <form className="action-dialog-form" onSubmit={saveProblem}><label>Problem title<input autoFocus required placeholder="e.g. Sustainable Campus" value={newProblem.title} onChange={e => setNewProblem({ ...newProblem, title: e.target.value })} /></label><label>Problem statement<textarea required placeholder="Describe the problem for participants" value={newProblem.statement} onChange={e => setNewProblem({ ...newProblem, statement: e.target.value })} /></label><div className="import-modal-actions"><button type="button" className="small-button" onClick={closeActionDialog}>Cancel</button><button className="secondary-button">{editingProblem ? 'Save changes' : 'Add problem'}</button></div></form>}</section></div>}{importDialog && <div className="modal-backdrop import-modal-backdrop" onClick={closeImportDialog}><section className="card import-modal" role="dialog" aria-modal="true" aria-labelledby="import-dialog-title" onClick={e => e.stopPropagation()}><div className="card-top import-modal-header"><div><span className="eyebrow">BULK IMPORT</span><h2 id="import-dialog-title">{importDialog === 'teams' ? 'Import teams' : 'Import questions'}</h2></div><button type="button" className="ghost" onClick={closeImportDialog}>Close</button></div><p className="muted">{importDialog === 'teams' ? 'Team uploads accept only the exact 19 team table columns shown below, in the same order. Extra, missing, or renamed columns are rejected.' : 'Choose a CSV file with a Statement, Problem Statement, Question, or Description column. Title is optional.'}</p>{importDialog === 'teams' && <div className="team-import-guidance"><button type="button" className="small-button" onClick={downloadTeamImportTemplate}>Download exact CSV template</button><ol>{importedFieldLabels.map(label => <li key={label}><code>{label}</code></li>)}</ol></div>}<form onSubmit={importDialog === 'teams' ? importTeams : importQuestions}><label className="import-file-picker"><input type="file" accept={importDialog === 'teams' ? '.csv,.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv' : '.csv,text/csv'} required onChange={e => importDialog === 'teams' ? setCsvFile(e.target.files[0] || null) : setQuestionCsvFile(e.target.files[0] || null)} /><span className="import-file-button">Choose file</span><span className="import-file-name">{(importDialog === 'teams' ? csvFile : questionCsvFile)?.name || 'No file selected'}</span></label><div className="import-modal-actions"><button type="button" className="small-button" onClick={closeImportDialog}>Cancel</button><button className="secondary-button" disabled={!(importDialog === 'teams' ? csvFile : questionCsvFile)}>Import {importDialog === 'teams' ? 'teams' : 'questions'}</button></div></form></section></div>}</main>;
}

function AttendanceCoordinator({ onExit }) {
  const [password, setPassword] = useState('');
  const [authenticated, setAuthenticated] = useState(false);
  const [teams, setTeams] = useState([]);
  const [query, setQuery] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [updatingStudentId, setUpdatingStudentId] = useState(null);
  const [lastSyncedAt, setLastSyncedAt] = useState(null);
  const attendanceRevision = useRef(0);
  const attendanceRefreshInFlight = useRef(false);

  async function loadAttendance() {
    if (attendanceRefreshInFlight.current) return;
    attendanceRefreshInFlight.current = true;
    const revision = attendanceRevision.current;
    try {
      const loadedTeams = await request('/attendance/teams', { headers: { 'X-Attendance-Password': password } });
      if (revision === attendanceRevision.current) {
        setTeams(loadedTeams);
        setLastSyncedAt(Date.now());
        setError('');
      }
    } finally {
      attendanceRefreshInFlight.current = false;
    }
  }

  async function signIn(event) {
    event.preventDefault();
    setLoading(true);
    setError('');
    try {
      const loadedTeams = await request('/attendance/login', { method: 'POST', body: JSON.stringify({ password }) });
      attendanceRevision.current += 1;
      setTeams(loadedTeams);
      setLastSyncedAt(Date.now());
      setAuthenticated(true);
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setLoading(false);
    }
  }

  async function toggleAttendance(student) {
    setUpdatingStudentId(student.id);
    setError('');
    try {
      const updated = await request(`/attendance/students/${student.id}`, {
        method: 'PUT',
        headers: { 'X-Attendance-Password': password },
        body: JSON.stringify({ present: !student.present })
      });
      attendanceRevision.current += 1;
      setTeams(current => current.map(team => ({
        ...team,
        students: team.students.map(member => member.id === updated.id ? updated : member)
      })));
      setLastSyncedAt(Date.now());
    } catch (requestError) {
      setError(requestError.message);
    } finally {
      setUpdatingStudentId(null);
    }
  }

  useEffect(() => {
    if (!authenticated) return undefined;
    const timer = window.setInterval(() => {
      loadAttendance().catch(requestError => setError(requestError.message));
    }, ATTENDANCE_SYNC_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [authenticated, password]);

  function signOut() {
    setAuthenticated(false);
    setPassword('');
    setTeams([]);
    onExit();
  }

  if (!authenticated) {
    return <main className="login-shell admin-login"><form className="card login-card" onSubmit={signIn}>
      <span className="eyebrow">GREENOPS · ATTENDANCE</span>
      <h2>Attendance check-in.</h2>
      <p className="muted">Sign in to mark each participant present as they arrive. Team members start as absent until checked in.</p>
      <label>Attendance coordinator password<input type="password" required autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} /></label>
      {error && <div className="error" role="alert">{error}</div>}
      <button disabled={loading}>{loading ? 'Signing in…' : 'Open attendance'}</button>
      <button type="button" className="attendance-entry-button" onClick={onExit}>Back to participant login</button>
    </form></main>;
  }

  const allStudents = teams.flatMap(team => team.students);
  const presentCount = allStudents.filter(student => student.present).length;
  const normalizedQuery = query.trim().toLowerCase();
  const filteredTeams = teams.map(team => ({
    ...team,
    students: team.students.filter(student => !normalizedQuery
      || `${team.name} ${team.teamNumber || ''} ${student.name} ${student.registerNumber}`.toLowerCase().includes(normalizedQuery))
  })).filter(team => team.students.length > 0);

  return <main className="app-shell">
    <header><div className="brand"><img src="/vit-logo-transparent.png" alt="Vellore Institute of Technology" /><span>GREENOPS</span></div><div className="header-actions"><span className="session-label">Attendance session active</span><button className="ghost" onClick={signOut}>Sign out</button></div></header>
    <div className="content admin-content attendance-content">
      <div className="welcome"><span className="eyebrow">GREENOPS · ATTENDANCE COORDINATOR</span><h1>Participant check-in</h1><p className="muted">Mark participants present one by one. Updates sync across coordinator devices every 3 seconds{lastSyncedAt ? ` · Last synced ${new Date(lastSyncedAt).toLocaleTimeString()}` : ''}.</p></div>
      {error && <div className="error" role="alert">{error}</div>}
      <section className="card attendance-toolbar">
        <div className="attendance-counts"><span><b>{presentCount}</b><small>Present</small></span><span><b>{allStudents.length - presentCount}</b><small>Absent</small></span><span><b>{allStudents.length}</b><small>Total members</small></span></div>
        <label>Find team or participant<input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="Team name, participant or register number" /></label>
      </section>
      {filteredTeams.length === 0
        ? <div className="card empty">{teams.length ? 'No team members match this search.' : 'No teams are available for check-in.'}</div>
        : <div className="attendance-team-list">{filteredTeams.map(team => <section className="card attendance-team" key={team.id}>
          <div className="card-top"><div><span className="eyebrow">TEAM {team.teamNumber ? `#${team.teamNumber}` : ''}</span><h2>{team.name}</h2></div><span className="pill">{team.students.filter(student => student.present).length}/{team.students.length} present</span></div>
          <div className="attendance-member-list">{team.students.map(student => <div className="attendance-member" key={student.id}>
            <div><b>{student.name}</b><small>{student.registerNumber}{student.leader ? ' · Group leader' : ''}</small></div>
            <span className={`attendance-status ${student.present ? 'is-present' : 'is-absent'}`}>{student.present ? 'Present' : 'Absent'}</span>
            <button type="button" className={`small-button ${student.present ? 'danger-button' : ''}`} disabled={updatingStudentId !== null} onClick={() => toggleAttendance(student)}>
              {updatingStudentId === student.id ? 'Saving…' : student.present ? 'Mark absent' : 'Mark present'}
            </button>
          </div>)}</div>
        </section>)}</div>}
    </div>
  </main>;
}

function App() {
  const [mode, setMode] = useState(location.hash === '#admin' ? 'admin' : 'student');
  const savedParticipantSession = readSession('hackathon-participant-session');
  const [session, setSession] = useState(savedParticipantSession?.team ? savedParticipantSession : null);
  const [sessionExpired, setSessionExpired] = useState(Boolean(savedParticipantSession?.expired));
  const [sessionExpiredMessage, setSessionExpiredMessage] = useState(
    'Your session has expired for security. Please sign in again.'
  );
  const [sessionSyncError, setSessionSyncError] = useState('');
  const logout = () => { clearSession('hackathon-participant-session'); setSession(null); setSessionExpired(false); setSessionSyncError(''); };
  const onLogin = (team, credentials) => { saveSession('hackathon-participant-session', { team, credentials }); setSession(readSession('hackathon-participant-session')); setSessionExpired(false); setSessionSyncError(''); };
  useEffect(() => {
    if (mode !== 'student' || !session?.credentials) return undefined;
    let active = true;
    const validateSession = async () => {
      try {
        const team = await request('/student/login', {
          method: 'POST',
          body: JSON.stringify(session.credentials)
        });
        if (!active) return;
        const updatedSession = { ...session, team };
        saveSession('hackathon-participant-session', updatedSession);
        setSession(updatedSession);
        setSessionSyncError('');
      } catch (error) {
        if (active && (error.status === 401 || error.status === 403)) {
          clearSession('hackathon-participant-session');
          setSession(null);
          setSessionExpiredMessage(error.message);
          setSessionExpired(true);
        } else if (active) {
          setSessionSyncError(`Could not refresh your team status: ${error.message}`);
        }
      }
    };
    validateSession();
    const timer = window.setInterval(validateSession, 15000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [mode, session?.credentials?.email, session?.credentials?.contactNumber]);
  const refreshParticipantSession = async () => {
    const team = await request('/student/login', {
      method: 'POST',
      body: JSON.stringify(session.credentials)
    });
    const updatedSession = { ...session, team };
    saveSession('hackathon-participant-session', updatedSession);
    setSession(updatedSession);
    return team;
  };
  const openAdmin = () => { logout(); location.hash = 'admin'; setMode('admin'); };
  const openAttendance = () => { logout(); setMode('attendance'); };
  const openParticipant = () => { logout(); location.hash = ''; setMode('student'); };
  return <>{mode === 'admin' ? <Admin /> : mode === 'attendance' ? <AttendanceCoordinator onExit={openParticipant} /> : session ? <Student team={session.team} credentials={session.credentials} logout={logout} refreshSession={refreshParticipantSession} sessionSyncError={sessionSyncError} /> : <Login onLogin={onLogin} onAdmin={openAdmin} />}{mode === 'student' && sessionExpired && <div className="session-expired"><div className="card"><h2>Participant access updated</h2><p>{sessionExpiredMessage}</p><button onClick={logout}>Back to login</button></div></div>}{(mode === 'admin' || mode === 'attendance' || session) && <div className="portal-mode-switches">{mode === 'admin' && <button className="mode-switch" onClick={openAttendance}>Attendance Coordinator</button>}<button className="mode-switch" onClick={openParticipant}>Participant login</button></div>}</>;
}

createRoot(document.getElementById('root')).render(<App />);
