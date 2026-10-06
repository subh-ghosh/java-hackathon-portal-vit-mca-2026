import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const API = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

async function request(path, options = {}) {
  const response = await fetch(`${API}${path}`, { headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }, ...options });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || 'Something went wrong');
  return body;
}

function Login({ onLogin }) {
  const [form, setForm] = useState({ registerNumber: '', password: '' });
  const [error, setError] = useState('');
  async function submit(event) {
    event.preventDefault();
    try { onLogin(await request('/student/login', { method: 'POST', body: JSON.stringify(form) }), form); }
    catch (e) { setError(e.message); }
  }
  return <main className="login-shell"><section className="hero"><span className="eyebrow">VIT · JAVA HACKATHON 2026</span><h1>Build something<br /><em>that matters.</em></h1><p>Your team space for the problem statement, resources, and final submission.</p><div className="stat-row"><strong>500+ <small>students</small></strong><strong>100 <small>teams</small></strong><strong>24h <small>to build</small></strong></div></section>
    <form className="card login-card" onSubmit={submit}><span className="eyebrow">STUDENT PORTAL</span><h2>Welcome back.</h2><p className="muted">Sign in with the details shared by your coordinator.</p><label>VIT register number<input required value={form.registerNumber} onChange={e => setForm({ ...form, registerNumber: e.target.value })} placeholder="22BCE0001" /></label><label>Access password<input required type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} placeholder="••••••••" /></label>{error && <div className="error">{error}</div>}<button>Enter team space <span>→</span></button><p className="hint">Admin? Use the dashboard switch below.</p></form></main>;
}

function Student({ team, credentials, logout }) {
  const [links, setLinks] = useState({ githubUrl: team.githubUrl || '', driveUrl: team.driveUrl || '' });
  const [message, setMessage] = useState('');
  async function submit(e) {
    e.preventDefault();
    try { await request('/student/submission', { method: 'PUT', body: JSON.stringify({ ...links, ...credentials }) }); setMessage('Submission saved successfully.'); }
    catch (error) { setMessage(error.message); }
  }
  return <main className="app-shell"><header><div className="brand">VIT <span>HACKATHON</span></div><button className="ghost" onClick={logout}>Sign out</button></header><div className="content"><div className="welcome"><span className="eyebrow">TEAM SPACE</span><h1>{team.name}</h1><p className="muted">Your entire team sees the same challenge. Make it count.</p></div><div className="student-grid"><section className="card problem-card"><div className="card-top"><span className="eyebrow">PROBLEM {team.problem ? `#${team.problem.id}` : ''}</span><span className="pill">{team.problem ? 'Assigned' : 'Pending'}</span></div>{team.problem ? <><h2>{team.problem.title}</h2><p>{team.problem.statement}</p></> : <div className="empty">Your coordinator has not assigned a problem yet.</div>}<h3>Team members</h3><div className="members">{team.students.map(s => <div className="member" key={s.registerNumber}><span>{s.name.charAt(0)}</span><div><b>{s.name}</b><small>{s.registerNumber}</small></div></div>)}</div></section><form className="card submission-card" onSubmit={submit}><span className="eyebrow">FINAL SUBMISSION</span><h2>Ship your work.</h2><p className="muted">Both links are visible to the admin after you save.</p><label>GitHub repository URL<input type="url" required value={links.githubUrl} onChange={e => setLinks({ ...links, githubUrl: e.target.value })} placeholder="https://github.com/team/project" /></label><label>Google Drive document URL<input type="url" required value={links.driveUrl} onChange={e => setLinks({ ...links, driveUrl: e.target.value })} placeholder="https://drive.google.com/..." /></label>{message && <div className={message.includes('success') ? 'success' : 'error'}>{message}</div>}<button>Save submission <span>→</span></button></form></div></div></main>;
}

function Admin() {
  const [password, setPassword] = useState('');
  const [authed, setAuthed] = useState(false);
  const [teams, setTeams] = useState([]);
  const [problems, setProblems] = useState([]);
  const [newProblem, setNewProblem] = useState({ title: '', statement: '' });
  const [notice, setNotice] = useState('');
  async function load() { const headers = { 'X-Admin-Password': password }; setTeams(await request('/admin/teams', { headers })); setProblems(await request('/admin/problems', { headers })); setAuthed(true); }
  async function addProblem(e) { e.preventDefault(); const item = await request('/admin/problems', { method: 'POST', headers: { 'X-Admin-Password': password }, body: JSON.stringify(newProblem) }); setProblems([...problems, item]); setNewProblem({ title: '', statement: '' }); }
  async function assign(teamId, problemId) { await request(`/admin/teams/${teamId}/problem/${problemId}`, { method: 'PUT', headers: { 'X-Admin-Password': password } }); setTeams(teams.map(t => t.id === teamId ? { ...t, problem: problems.find(p => p.id === Number(problemId)) } : t)); }
  async function importCsv(e) { const data = new FormData(); data.append('file', e.target.files[0]); const response = await fetch(`${API}/admin/teams/import`, { method: 'POST', headers: { 'X-Admin-Password': password }, body: data }); const body = await response.json(); setNotice(response.ok ? `${body.rowsImported} rows imported.` : body.message); if (response.ok) load(); }
  if (!authed) return <main className="login-shell admin-login"><form className="card login-card" onSubmit={e => { e.preventDefault(); load().catch(err => setNotice(err.message)); }}><span className="eyebrow">COORDINATOR DASHBOARD</span><h2>Admin access.</h2><p className="muted">Manage teams, challenges, and final submissions.</p><label>Admin password<input type="password" required value={password} onChange={e => setPassword(e.target.value)} /></label>{notice && <div className="error">{notice}</div>}<button>Open dashboard <span>→</span></button></form></main>;
  return <main className="app-shell"><header><div className="brand">VIT <span>HACKATHON</span></div><button className="ghost" onClick={() => setAuthed(false)}>Sign out</button></header><div className="content"><div className="welcome"><span className="eyebrow">COORDINATOR DASHBOARD</span><h1>Command center</h1><p className="muted">Import teams once, then assign challenges in seconds.</p></div><div className="admin-actions"><label className="upload card">＋ Import team CSV<input type="file" accept=".csv" onChange={importCsv} /></label><form className="card problem-form" onSubmit={addProblem}><span className="eyebrow">NEW PROBLEM</span><input required placeholder="Problem title" value={newProblem.title} onChange={e => setNewProblem({ ...newProblem, title: e.target.value })} /><textarea required placeholder="Problem statement" value={newProblem.statement} onChange={e => setNewProblem({ ...newProblem, statement: e.target.value })} /><button>Add problem</button></form></div>{notice && <div className="success">{notice}</div>}<section className="card table-card"><div className="card-top"><div><span className="eyebrow">TEAM ROSTER</span><h2>Assignments & submissions</h2></div><span className="pill">{teams.length} teams</span></div><div className="table-wrap"><table><thead><tr><th>Team</th><th>Members</th><th>Problem statement</th><th>Submission</th></tr></thead><tbody>{teams.map(team => <tr key={team.id}><td><b>{team.name}</b></td><td>{team.students.length} students</td><td><select value={team.problem?.id || ''} onChange={e => assign(team.id, e.target.value)}><option value="">Unassigned</option>{problems.map(p => <option key={p.id} value={p.id}>{p.id} · {p.title}</option>)}</select></td><td>{team.githubUrl && team.driveUrl ? <span className="submitted">Complete</span> : <span className="pending">Pending</span>}</td></tr>)}</tbody></table></div></section></div></main>;
}

function App() {
  const [mode, setMode] = useState(location.hash === '#admin' ? 'admin' : 'student');
  const [session, setSession] = useState(null);
  return <>{mode === 'admin' ? <Admin /> : session ? <Student team={session.team} credentials={session.credentials} logout={() => setSession(null)} /> : <Login onLogin={(team, credentials) => setSession({ team, credentials })} />}<button className="mode-switch" onClick={() => { setMode(mode === 'admin' ? 'student' : 'admin'); setSession(null); }}>{mode === 'admin' ? 'Student login' : 'Admin dashboard'}</button></>;
}

createRoot(document.getElementById('root')).render(<App />);
