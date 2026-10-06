import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const API = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

async function request(path, options = {}) {
  const response = await fetch(`${API}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || 'Something went wrong');
  return body;
}

function Login({ onLogin }) {
  const [form, setForm] = useState({ registerNumber: '', password: '' });
  const [error, setError] = useState('');
  async function submit(event) {
    event.preventDefault();
    try {
      onLogin(await request('/student/login', { method: 'POST', body: JSON.stringify(form) }), form);
    } catch (e) { setError(e.message); }
  }
  return <main className="login-shell"><section className="hero"><span className="eyebrow">VIT · JAVA HACKATHON 2026</span><h1>Build something<br /><em>that matters.</em></h1><p>Your team space for the problem statement, resources, and final submission.</p><div className="stat-row"><strong>500+ <small>students</small></strong><strong>100 <small>teams</small></strong><strong>24h <small>to build</small></strong></div></section>
    <form className="card login-card" onSubmit={submit}><span className="eyebrow">STUDENT PORTAL</span><h2>Welcome back.</h2><p className="muted">Sign in with the details shared by your coordinator.</p><label>VIT register number<input required value={form.registerNumber} onChange={e => setForm({ ...form, registerNumber: e.target.value })} placeholder="22BCE0001" /></label><label>Access password<input required type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} placeholder="••••••••" /></label>{error && <div className="error">{error}</div>}<button>Enter team space <span>→</span></button></form></main>;
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

const emptyStudent = { name: '', registerNumber: '', email: '', accessPassword: '' };

function Admin() {
  const [password, setPassword] = useState('');
  const [authed, setAuthed] = useState(false);
  const [teams, setTeams] = useState([]);
  const [problems, setProblems] = useState([]);
  const [teamName, setTeamName] = useState('');
  const [student, setStudent] = useState(emptyStudent);
  const [newProblem, setNewProblem] = useState({ title: '', statement: '' });
  const [editingProblem, setEditingProblem] = useState(null);
  const [notice, setNotice] = useState('');
  const headers = () => ({ 'X-Admin-Password': password });
  async function load() {
    const authHeaders = headers();
    const [loadedTeams, loadedProblems] = await Promise.all([
      request('/admin/teams', { headers: authHeaders }),
      request('/admin/problems', { headers: authHeaders })
    ]);
    setTeams(loadedTeams); setProblems(loadedProblems); setAuthed(true);
  }
  async function createTeam(e) {
    e.preventDefault();
    const created = await request('/admin/teams', { method: 'POST', headers: headers(), body: JSON.stringify({ name: teamName }) });
    setTeams([...teams, created]); setTeamName(''); setNotice('Team created.');
  }
  async function deleteTeam(id) {
    if (!confirm('Delete this team and all its students?')) return;
    await request(`/admin/teams/${id}`, { method: 'DELETE', headers: headers() });
    setTeams(teams.filter(team => team.id !== id)); setNotice('Team deleted.');
  }
  async function renameTeam(team) {
    const name = prompt('Team name', team.name);
    if (!name || name === team.name) return;
    const updated = await request(`/admin/teams/${team.id}`, { method: 'PUT', headers: headers(), body: JSON.stringify({ name }) });
    setTeams(teams.map(item => item.id === team.id ? updated : item));
  }
  async function addStudent(e, teamId) {
    e.preventDefault();
    const updated = await request(`/admin/teams/${teamId}/students`, { method: 'POST', headers: headers(), body: JSON.stringify(student) });
    setTeams(teams.map(team => team.id === teamId ? updated : team)); setStudent(emptyStudent); setNotice('Student added.');
  }
  async function deleteStudent(id) {
    if (!confirm('Delete this student?')) return;
    await request(`/admin/students/${id}`, { method: 'DELETE', headers: headers() });
    setTeams(teams.map(team => ({ ...team, students: team.students.filter(item => item.id !== id) })));
  }
  async function editStudent(item) {
    const name = prompt('Student name', item.name);
    if (!name) return;
    const registerNumber = prompt('Register number', item.registerNumber);
    if (!registerNumber) return;
    const email = prompt('Email', item.email || '') || '';
    const accessPassword = prompt('New access password, or leave blank to keep current', '');
    const updated = await request(`/admin/students/${item.id}`, {
      method: 'PUT',
      headers: headers(),
      body: JSON.stringify({ name, registerNumber, email, accessPassword: accessPassword || item.accessPassword })
    });
    setTeams(teams.map(team => ({ ...team, students: team.students.map(student => student.id === updated.id ? updated : student) })));
    setNotice('Student updated.');
  }
  async function saveProblem(e) {
    e.preventDefault();
    const method = editingProblem ? 'PUT' : 'POST';
    const path = editingProblem ? `/admin/problems/${editingProblem.id}` : '/admin/problems';
    const saved = await request(path, { method, headers: headers(), body: JSON.stringify(newProblem) });
    setProblems(editingProblem ? problems.map(item => item.id === saved.id ? saved : item) : [...problems, saved]);
    setNewProblem({ title: '', statement: '' }); setEditingProblem(null); setNotice('Problem saved.');
  }
  async function deleteProblem(id) {
    if (!confirm('Delete this problem statement?')) return;
    await request(`/admin/problems/${id}`, { method: 'DELETE', headers: headers() });
    setProblems(problems.filter(problem => problem.id !== id));
    setTeams(teams.map(team => team.problem?.id === id ? { ...team, problem: null } : team));
  }
  async function assign(teamId, problemId) {
    const updated = await request(problemId ? `/admin/teams/${teamId}/problem/${problemId}` : `/admin/teams/${teamId}/problem`, { method: problemId ? 'PUT' : 'DELETE', headers: headers() });
    setTeams(teams.map(team => team.id === teamId ? updated : team)); setNotice('Problem assigned.');
  }
  if (!authed) return <main className="login-shell admin-login"><form className="card login-card" onSubmit={e => { e.preventDefault(); load().catch(err => setNotice(err.message)); }}><span className="eyebrow">COORDINATOR DASHBOARD</span><h2>Admin access.</h2><p className="muted">Manage teams, students, challenges, and submissions one by one.</p><label>Admin password<input type="password" required value={password} onChange={e => setPassword(e.target.value)} /></label>{notice && <div className="error">{notice}</div>}<button>Open dashboard <span>→</span></button></form></main>;
  return <main className="app-shell"><header><div className="brand">VIT <span>HACKATHON</span></div><button className="ghost" onClick={() => setAuthed(false)}>Sign out</button></header><div className="content"><div className="welcome"><span className="eyebrow">COORDINATOR DASHBOARD</span><h1>Command center</h1><p className="muted">Create teams and members individually, assign challenges, and review every submission.</p></div>{notice && <div className="success">{notice}</div>}<div className="admin-actions"><form className="card inline-form" onSubmit={createTeam}><span className="eyebrow">NEW TEAM</span><input required placeholder="Team name" value={teamName} onChange={e => setTeamName(e.target.value)} /><button>Create team</button></form><form className="card problem-form" onSubmit={saveProblem}><span className="eyebrow">{editingProblem ? 'EDIT PROBLEM' : 'NEW PROBLEM'}</span><input required placeholder="Problem title" value={newProblem.title} onChange={e => setNewProblem({ ...newProblem, title: e.target.value })} /><textarea required placeholder="Problem statement" value={newProblem.statement} onChange={e => setNewProblem({ ...newProblem, statement: e.target.value })} /><button>{editingProblem ? 'Save changes' : 'Add problem'}</button></form></div><section className="card table-card"><div className="card-top"><div><span className="eyebrow">PROBLEM LIBRARY</span><h2>Problem statements</h2></div><span className="pill">{problems.length} problems</span></div><div className="problem-list">{problems.map(problem => <div className="problem-row" key={problem.id}><div><b>#{problem.id} · {problem.title}</b><p>{problem.statement}</p></div><div className="row-actions"><button className="small-button" onClick={() => { setEditingProblem(problem); setNewProblem({ title: problem.title, statement: problem.statement }); }}>Edit</button><button className="small-button danger-button" onClick={() => deleteProblem(problem.id)}>Delete</button></div></div>)}</div></section><section className="card table-card"><div className="card-top"><div><span className="eyebrow">TEAM ROSTER</span><h2>Teams & submissions</h2></div><span className="pill">{teams.length} teams</span></div><div className="table-wrap"><table><thead><tr><th>Team</th><th>Members</th><th>Problem</th><th>Submission</th><th>Actions</th></tr></thead><tbody>{teams.map(team => <tr key={team.id}><td><b>{team.name}</b></td><td>{team.students.length}</td><td><select value={team.problem?.id || ''} onChange={e => assign(team.id, e.target.value)}><option value="">Unassigned</option>{problems.map(p => <option key={p.id} value={p.id}>{p.id} · {p.title}</option>)}</select></td><td>{team.githubUrl && team.driveUrl ? <span className="submitted">Complete</span> : <span className="pending">Pending</span>}</td><td><button className="small-button" onClick={() => renameTeam(team)}>Rename</button><button className="small-button danger-button" onClick={() => deleteTeam(team.id)}>Delete</button></td></tr>)}</tbody></table></div></section>{teams.map(team => <section className="card team-card" key={team.id}><div className="card-top"><div><span className="eyebrow">TEAM MEMBERS</span><h2>{team.name}</h2></div><span className="pill">{team.students.length} members</span></div><div className="members admin-members">{team.students.map(item => <div className="member" key={item.id}><span>{item.name.charAt(0)}</span><div><b>{item.name}</b><small>{item.registerNumber} · {item.email}</small></div>  <button className="small-button" onClick={() => editStudent(item)}>Edit</button><button className="small-button danger-button" onClick={() => deleteStudent(item.id)}>Remove</button></div>)}</div><form className="student-form" onSubmit={e => addStudent(e, team.id)}><input required placeholder="Student name" value={student.name} onChange={e => setStudent({ ...student, name: e.target.value })} /><input required placeholder="Register number" value={student.registerNumber} onChange={e => setStudent({ ...student, registerNumber: e.target.value })} /><input type="email" placeholder="Email" value={student.email} onChange={e => setStudent({ ...student, email: e.target.value })} /><input required placeholder="Access password" value={student.accessPassword} onChange={e => setStudent({ ...student, accessPassword: e.target.value })} /><button>Add member</button></form></section>)}</div></main>;
}

function App() {
  const [mode, setMode] = useState(location.hash === '#admin' ? 'admin' : 'student');
  const [session, setSession] = useState(null);
  return <>{mode === 'admin' ? <Admin /> : session ? <Student team={session.team} credentials={session.credentials} logout={() => setSession(null)} /> : <Login onLogin={(team, credentials) => setSession({ team, credentials })} />}<button className="mode-switch" onClick={() => { setMode(mode === 'admin' ? 'student' : 'admin'); setSession(null); }}>{mode === 'admin' ? 'Student login' : 'Admin dashboard'}</button></>;
}

createRoot(document.getElementById('root')).render(<App />);
