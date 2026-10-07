import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const API = import.meta.env.VITE_API_URL || 'https://vit-hackathon-api.onrender.com/api';
const SESSION_TTL = 8 * 60 * 60 * 1000;

function readSession(key) {
  try {
    const value = JSON.parse(sessionStorage.getItem(key) || 'null');
    if (!value?.expiresAt) return null;
    return value.expiresAt > Date.now() ? value : { expired: true };
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

async function request(path, options = {}) {
  const isFormData = options.body instanceof FormData;
  const response = await fetch(`${API}${path}`, {
    ...options,
    headers: { ...(isFormData ? {} : { 'Content-Type': 'application/json' }), ...(options.headers || {}) }
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || 'Something went wrong');
  return body;
}

function Login({ onLogin }) {
  const [form, setForm] = useState({ name: '', registerNumber: '' });
  const [error, setError] = useState('');
  async function submit(event) {
    event.preventDefault();
    try {
      onLogin(await request('/student/login', { method: 'POST', body: JSON.stringify(form) }), form);
    } catch (e) { setError(e.message); }
  }
  return <main className="login-shell"><section className="hero"><img className="login-logo" src="/vit-logo-transparent.png" alt="Vellore Institute of Technology" /><span className="eyebrow">VIT - JAVA HACKATHON 2026</span><h1>Build something<br /><em>that matters.</em></h1><p>Your team space for the assigned problem statement and team details.</p><div className="stat-row"><strong>500+ <small>participants</small></strong><strong>100 <small>teams</small></strong><strong>24h <small>to build</small></strong></div></section>
    <form className="card login-card" onSubmit={submit}><span className="eyebrow">PARTICIPANT PORTAL</span><h2>Welcome back.</h2><p className="muted">Use your name and VIT register number to sign in.</p><label>Name<input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="Name as provided to the coordinator" /></label><label>Register number<input required type="password" value={form.registerNumber} onChange={e => setForm({ ...form, registerNumber: e.target.value })} placeholder="Your register number" /></label>{error && <div className="error">{error}</div>}<button>Enter team space <span>→</span></button></form></main>;
}

function Student({ team, credentials, logout }) {
  const [links, setLinks] = useState({ googleDriveLink: team.submission?.googleDriveLink || '', githubLink: team.submission?.githubLink || '' });
  const [notice, setNotice] = useState('');
  async function submit(event) {
    event.preventDefault();
    try {
      const submission = await request('/student/submission', { method: 'PUT', body: JSON.stringify({ ...credentials, ...links }) });
      team.submission = submission;
      setNotice('Submission links saved.');
    } catch (error) { setNotice(error.message); }
  }
  return <main className="app-shell"><header><div className="brand"><img src="/vit-logo-transparent.png" alt="Vellore Institute of Technology" /><span>HACKATHON</span></div><div className="header-actions"><span className="session-label">Participant session active</span><button className="ghost" onClick={logout}>Sign out</button></div></header><div className="content"><div className="welcome"><span className="eyebrow">TEAM SPACE</span><h1>{team.name}</h1><p className="muted">Your group details and assigned problem statement.</p><div className="student-summary"><span><small>GROUP ID</small>#{team.teamNumber}</span><span><small>YOUR DETAILS</small>{team.ownName || 'Name not provided'} · {team.ownRegisterNumber}{team.ownEmail && <small>{team.ownEmail}</small>}</span><span><small>GROUP LEADER</small>{team.leaderRegisterNumber || 'Not assigned'}</span></div></div><div className="student-grid"><section className="card problem-card"><div className="card-top"><span className="eyebrow">PROBLEM {team.problem ? `#${team.problem.id}` : ''}</span><span className="pill">{team.problem ? 'Enabled' : 'Not enabled'}</span></div>{team.problem ? <><h2>{team.problem.title}</h2><p>{team.problem.statement}</p></> : <div className="empty">The problem statement has not been enabled yet.</div>}<h3>Group members</h3><div className="members">{team.students.map(s => <div className="member" key={s.registerNumber}><span>{(s.name || s.registerNumber).charAt(0)}</span><div><b>{s.name || 'Name not provided'}</b><small>{s.registerNumber}{s.leader ? ' - Group leader' : ''}</small></div></div>)}</div></section>{team.leader && <form className="card submission-card" onSubmit={submit}><span className="eyebrow">FINAL SUBMISSION</span><h2>Submit your links</h2><p className="muted">Only the group leader can submit or update these links.</p><label>Google Drive document link<input required type="url" value={links.googleDriveLink} onChange={e => setLinks({ ...links, googleDriveLink: e.target.value })} /></label><label>GitHub repository link<input required type="url" value={links.githubLink} onChange={e => setLinks({ ...links, githubLink: e.target.value })} /></label>{notice && <div className={notice.includes('saved') ? 'success' : 'error'}>{notice}</div>}<button>Upload final links</button></form>}</div></div></main>;
}

const emptyStudent = { name: '', registerNumber: '', email: '' };

function Admin() {
  const savedSession = readSession('hackathon-admin-session');
  const [password, setPassword] = useState(savedSession?.password || '');
  const [authed, setAuthed] = useState(Boolean(savedSession && !savedSession.expired));
  const [sessionExpired, setSessionExpired] = useState(Boolean(savedSession?.expired));
  const [teams, setTeams] = useState([]);
  const [problems, setProblems] = useState([]);
  const [teamName, setTeamName] = useState('');
  const [csvFile, setCsvFile] = useState(null);
  const [questionCsvFile, setQuestionCsvFile] = useState(null);
  const [student, setStudent] = useState(emptyStudent);
  const [newProblem, setNewProblem] = useState({ title: '', statement: '' });
  const [editingProblem, setEditingProblem] = useState(null);
  const [editingTeam, setEditingTeam] = useState(null);
  const [dialog, setDialog] = useState(null);
  const [notice, setNotice] = useState('');
  const [loginSettings, setLoginSettings] = useState({ loginEnabled: true, startTime: '', endTime: '' });
  const [submissions, setSubmissions] = useState([]);
  const headers = () => ({ 'X-Admin-Password': password });
  useEffect(() => {
    if (!notice) return undefined;
    const timeout = window.setTimeout(() => setNotice(''), 3500);
    return () => window.clearTimeout(timeout);
  }, [notice]);
  useEffect(() => {
    if (!authed && sessionStorage.getItem('hackathon-admin-session')) clearSession('hackathon-admin-session');
  }, [authed]);
  useEffect(() => {
    if (sessionExpired) setNotice('Your admin session expired. Sign in again. Use Sign out to clear the saved session.');
  }, [sessionExpired]);
  useEffect(() => {
    if (authed && password && teams.length === 0) load().catch(error => setNotice(error.message));
  }, [authed]);
  useEffect(() => {
    if (!authed || !password) return undefined;
    const timer = window.setInterval(() => {
      request('/admin/submissions', { headers: headers() })
        .then(setSubmissions)
        .catch(error => setNotice(error.message));
    }, 30000);
    return () => window.clearInterval(timer);
  }, [authed, password]);
  async function load() {
    const authHeaders = headers();
    const [loadedTeams, loadedProblems, loadedSettings, loadedSubmissions] = await Promise.all([
      request('/admin/teams', { headers: authHeaders }),
      request('/admin/problems', { headers: authHeaders }),
      request('/admin/settings', { headers: authHeaders }),
      request('/admin/submissions', { headers: authHeaders })
    ]);
    setTeams(loadedTeams); setProblems(loadedProblems); setLoginSettings(loadedSettings); setSubmissions(loadedSubmissions); setAuthed(true); setSessionExpired(false);
    saveSession('hackathon-admin-session', { password });
  }
  async function saveLoginSettings(event) {
    event.preventDefault();
    const saved = await request('/admin/settings', { method: 'PUT', headers: headers(), body: JSON.stringify(loginSettings) });
    setLoginSettings(saved); setNotice('Participant login settings updated.');
  }
  async function toggleProblem(problem) {
    const saved = await request(`/admin/problems/${problem.id}/enabled`, { method: 'PUT', headers: headers(), body: JSON.stringify({ enabled: !problem.enabled }) });
    setProblems(problems.map(item => item.id === saved.id ? saved : item));
    setNotice(saved.enabled ? 'Problem enabled for participants.' : 'Problem hidden from participants.');
  }
  async function createTeam(e) {
    e.preventDefault();
    const created = await request('/admin/teams', { method: 'POST', headers: headers(), body: JSON.stringify({ name: teamName }) });
    setTeams([...teams, created]); setTeamName(''); setNotice('Team created.');
  }
  async function importTeams(e) {
    e.preventDefault();
    if (!csvFile) return setNotice('Choose a CSV file first.');
    const form = e.currentTarget;
    const formData = new FormData();
    formData.append('file', csvFile);
    const imported = await request('/admin/teams/import', { method: 'POST', headers: headers(), body: formData });
    setTeams([...teams, ...imported]);
    setCsvFile(null);
    form.reset();
    setNotice(`${imported.length} teams imported.`);
  }
  async function importQuestions(e) {
    e.preventDefault();
    if (!questionCsvFile) return setNotice('Choose a question CSV file first.');
    const form = e.currentTarget;
    const formData = new FormData();
    formData.append('file', questionCsvFile);
    const imported = await request('/admin/problems/import', { method: 'POST', headers: headers(), body: formData });
    setProblems([...problems, ...imported]);
    setQuestionCsvFile(null);
    form.reset();
    setNotice(`${imported.length} questions imported.`);
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
    if (!confirm('Clear ALL teams and all their participants? This cannot be undone.')) return;
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
      const updated = await request(`/admin/students/${dialog.student.id}`, { method: 'PUT', headers: headers(), body: JSON.stringify(values) });
      setTeams(teams.map(team => ({ ...team, students: team.students.map(item => item.id === updated.id ? updated : item) })));
      setEditingTeam(current => current ? { ...current, students: current.students.map(item => item.id === updated.id ? updated : item) } : current);
      setNotice('Student updated.');
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
    await request(`/admin/students/${id}`, { method: 'DELETE', headers: headers() });
    setTeams(teams.map(team => ({ ...team, students: team.students.filter(item => item.id !== id) })));
    setEditingTeam(current => current ? { ...current, students: current.students.filter(item => item.id !== id) } : current);
  }
  async function assignLeader(studentId) {
    const updated = await request(`/admin/students/${studentId}/leader`, { method: 'PUT', headers: headers() });
    setTeams(teams.map(team => team.id === updated.id ? updated : team));
    setEditingTeam(updated);
    setNotice('Team leader assigned.');
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
    setNewProblem({ title: '', statement: '' }); setEditingProblem(null); setNotice('Problem saved.');
  }
  async function deleteProblem(id) {
    if (!confirm('Delete this problem statement?')) return;
    await request(`/admin/problems/${id}`, { method: 'DELETE', headers: headers() });
    setProblems(problems.filter(problem => problem.id !== id));
    setTeams(teams.map(team => team.problem?.id === id ? { ...team, problem: null } : team));
  }
  async function clearProblems() {
    if (!confirm('Clear ALL problem statements and remove all team assignments? This cannot be undone.')) return;
    await request('/admin/problems', { method: 'DELETE', headers: headers() });
    setProblems([]);
    setTeams(teams.map(team => ({ ...team, problem: null })));
    setNotice('All problem statements cleared.');
  }
  async function assign(teamId, problemId) {
    const updated = await request(problemId ? `/admin/teams/${teamId}/problem/${problemId}` : `/admin/teams/${teamId}/problem`, { method: problemId ? 'PUT' : 'DELETE', headers: headers() });
    setTeams(teams.map(team => team.id === teamId ? updated : team)); setNotice('Problem assigned.');
  }
  if (!authed && sessionExpired) return <main className="session-expired"><div className="card"><h2>Admin session expired</h2><p>Your admin session has expired for security. Please sign in again.</p><button onClick={() => { clearSession('hackathon-admin-session'); setSessionExpired(false); setNotice('Saved admin session cleared.'); }}>Sign out</button></div></main>;
  if (!authed) return <main className="login-shell admin-login"><form className="card login-card" onSubmit={e => { e.preventDefault(); load().catch(err => setNotice(err.message)); }}><span className="eyebrow">COORDINATOR DASHBOARD</span><h2>Admin access.</h2><p className="muted">Manage teams, participants, and challenges one by one.</p><label>Admin password<input type="password" required value={password} onChange={e => setPassword(e.target.value)} /></label>{notice && <div className="error">{notice}</div>}<button>Open dashboard <span>&gt;</span></button></form></main>;
  return <main className="app-shell">  <header><div className="brand"><img src="/vit-logo-transparent.png" alt="Vellore Institute of Technology" /><span>HACKATHON</span></div><div className="header-actions"><button className="ghost" onClick={() => setAuthed(false)}>Sign out</button></div></header>  <div className="content"><div className="welcome"><span className="eyebrow">COORDINATOR DASHBOARD</span><h1>Command center</h1><p className="muted">Create teams and participants individually, then assign problem statements.</p></div>{notice && <div className="success">{notice}</div>}<form className="card settings-form" onSubmit={saveLoginSettings}><div className="card-top"><div><span className="eyebrow">PARTICIPANT ACCESS</span><h2>Login window</h2></div><button>Save settings</button></div><label className="checkbox-label"><input type="checkbox" checked={loginSettings.loginEnabled} onChange={e => setLoginSettings({ ...loginSettings, loginEnabled: e.target.checked })} /> Allow participant login</label><div className="settings-fields"><label>Start time (optional)<input type="datetime-local" value={loginSettings.startTime ? loginSettings.startTime.slice(0, 16) : ''} onChange={e => setLoginSettings({ ...loginSettings, startTime: e.target.value ? new Date(e.target.value).toISOString() : '' })} /></label><label>End time (optional)<input type="datetime-local" value={loginSettings.endTime ? loginSettings.endTime.slice(0, 16) : ''} onChange={e => setLoginSettings({ ...loginSettings, endTime: e.target.value ? new Date(e.target.value).toISOString() : '' })} /></label></div></form><div className="admin-actions"><form className="card inline-form" onSubmit={createTeam}><span className="eyebrow">NEW TEAM</span><input required placeholder="Team name" value={teamName} onChange={e => setTeamName(e.target.value)} />  <button>Create team</button></form>  <form className="card csv-form" onSubmit={importTeams}><span className="eyebrow">IMPORT TEAMS</span><input type="file" accept=".csv,.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv" required onChange={e => setCsvFile(e.target.files[0] || null)} /><small>Upload a CSV export or .xlsx workbook with the Data Collected columns. Required: leader and member names and register numbers. Every source column is retained and available for admin review.</small><button>Import teams</button></form><form className="card csv-form" onSubmit={importQuestions}><span className="eyebrow">IMPORT QUESTIONS</span><input type="file" accept=".csv,text/csv" required onChange={e => setQuestionCsvFile(e.target.files[0] || null)} /><small>CSV only. Required: Statement, Problem Statement, Question, or Description. Optional: Title.</small><button>Import questions</button></form><form className="card problem-form" onSubmit={saveProblem}><span className="eyebrow">{editingProblem ? 'EDIT PROBLEM' : 'NEW PROBLEM'}</span><input required placeholder="Problem title" value={newProblem.title} onChange={e => setNewProblem({ ...newProblem, title: e.target.value })} /><textarea required placeholder="Problem statement" value={newProblem.statement} onChange={e => setNewProblem({ ...newProblem, statement: e.target.value })} /><button>{editingProblem ? 'Save changes' : 'Add problem'}</button>  </form></div><section className="card table-card"><div className="card-top"><div><span className="eyebrow">SUBMISSIONS</span><h2>Team submissions</h2></div><span className="pill">{submissions.length} submitted</span></div>{submissions.length === 0 ? <div className="empty">No team submissions yet.</div> : <div className="table-wrap"><table><thead><tr><th>Group</th><th>Google Drive</th><th>GitHub</th><th>Updated</th></tr></thead><tbody>{submissions.map(item => <tr key={item.id}><td><b>{item.team?.name || `#${item.team?.teamNumber || ''}`}</b></td><td><a href={item.googleDriveLink} target="_blank" rel="noreferrer">Open document</a></td><td><a href={item.githubLink} target="_blank" rel="noreferrer">Open repository</a></td><td>{item.updatedAt ? new Date(item.updatedAt).toLocaleString() : '-'}</td></tr>)}</tbody></table></div>}</section><section className="card table-card"><div className="card-top"><div><span className="eyebrow">PROBLEM LIBRARY</span><h2>Problem statements</h2></div>  <div className="problem-library-actions"><span className="pill">{problems.length} questions</span>  <button className="small-button" onClick={randomlyAssignQuestions}>Randomly assign evenly</button><button className="small-button danger-button" onClick={clearProblems}>Clear all</button></div></div><div className="problem-list">{problems.map(problem => <div className="problem-row" key={problem.id}><div><b>#{problem.id}  -  {problem.title}</b><p>{problem.statement}</p></div><div className="row-actions">  <button className="small-button" onClick={() => toggleProblem(problem)}>{problem.enabled ? 'Disable' : 'Enable'}</button><button className="small-button" onClick={() => { setEditingProblem(problem); setNewProblem({ title: problem.title, statement: problem.statement }); }}>Edit</button><button className="small-button danger-button" onClick={() => deleteProblem(problem.id)}>Delete</button></div></div>)}</div></section><section className="card table-card"><div className="card-top"><div><span className="eyebrow">TEAM ROSTER</span>  <h2>Teams and assignments</h2></div><div className="problem-library-actions"><span className="pill">{teams.length} teams</span><button className="small-button danger-button" onClick={clearTeams}>Clear all</button></div></div><div className="table-wrap"><table><thead><tr><th>Team</th><th>Members</th><th>Problem</th><th>Actions</th></tr></thead><tbody>{teams.map(team =>   <tr key={team.id}><td><b>#{team.teamNumber}  -  {team.name}</b></td><td>{team.students.length}</td><td><select value={team.problem?.id || ''} onChange={e => assign(team.id, e.target.value)}><option value="">Unassigned</option>{problems.map(p => <option key={p.id} value={p.id}>{p.id}  -  {p.title}</option>)}</select></td><td><button className="small-button" onClick={() => setEditingTeam(team)}>Edit members</button><button className="small-button" onClick={() => renameTeam(team)}>Rename</button><button className="small-button danger-button" onClick={() => deleteTeam(team.id)}>Delete</button></td></tr>)}</tbody></table></div></section></div>  {editingTeam && <div className="modal-backdrop" onClick={() => setEditingTeam(null)}><section className="member-modal card" onClick={e => e.stopPropagation()}><div className="card-top"><div><span className="eyebrow">EDIT MEMBERS</span><h2>{editingTeam.name}</h2></div><button type="button" className="ghost" onClick={() => setEditingTeam(null)}>Close</button></div><form className="student-form modal-student-form" onSubmit={e => addStudent(e, editingTeam.id)}><input required placeholder="Participant name" value={student.name} onChange={e => setStudent({ ...student, name: e.target.value })} /><input required placeholder="Register number" value={student.registerNumber} onChange={e => setStudent({ ...student, registerNumber: e.target.value })} /><input required={false} type="email" placeholder="Email (optional)" value={student.email} onChange={e => setStudent({ ...student, email: e.target.value })} />  <button>Add participant</button></form>{editingTeam.students.length === 0 ? <div className="empty">No participants in this team yet.</div> : <div className="modal-member-list">{editingTeam.students.map(item => <div className="modal-member" key={item.id}><div><b>{item.name}</b><small>{item.registerNumber}  -  {item.email || 'No email'}{item.leader ? '  -  Leader' : ''}</small></div><div className="row-actions">{!item.leader && <button type="button" className="small-button" onClick={() => assignLeader(item.id)}>Assign as leader</button>}<button type="button" className="small-button" onClick={() => editStudent(item)}>Edit</button><button type="button" className="small-button danger-button" onClick={() => deleteStudent(item.id)}>Remove</button></div></div>)}</div>}{editingTeam.importedFields?.length > 0 && <div className="imported-fields-form"><details><summary>Imported spreadsheet fields ({editingTeam.importedFields.length})</summary><div className="imported-fields-grid">{editingTeam.importedFields.map(field => <div className="imported-field" key={field.columnIndex}><b>{field.fieldName || `Column ${field.columnIndex + 1}`}</b><span>{field.fieldValue || '-'}</span></div>)}</div></details></div>}</section></div>}{dialog && <div className="modal-backdrop" onClick={() => setDialog(null)}><form className="card dialog-modal" onClick={e => e.stopPropagation()} onSubmit={saveDialog}><div className="card-top"><h2>{dialog.title}</h2><button type="button" className="ghost" onClick={() => setDialog(null)}>Close</button></div>{dialog.fields.map(field => <label key={field.name}>{field.label}<input required={field.name === 'registerNumber' || field.name === 'name'} type={field.name === 'password' ? 'password' : field.name === 'email' ? 'email' : 'text'} name={field.name} defaultValue={field.value} /></label>)}<button>Save</button></form></div>}</main>;
}

function App() {
  const [mode, setMode] = useState(location.hash === '#admin' ? 'admin' : 'student');
  const savedParticipantSession = readSession('hackathon-participant-session');
  const [session, setSession] = useState(savedParticipantSession?.team ? savedParticipantSession : null);
  const [sessionExpired, setSessionExpired] = useState(Boolean(savedParticipantSession?.expired));
  const logout = () => { clearSession('hackathon-participant-session'); setSession(null); setSessionExpired(false); };
  const onLogin = (team, credentials) => { saveSession('hackathon-participant-session', { team, credentials }); setSession(readSession('hackathon-participant-session')); setSessionExpired(false); };
  const switchMode = () => { setMode(mode === 'admin' ? 'student' : 'admin'); logout(); };
  return <>{mode === 'admin' ? <Admin /> : session ? <Student team={session.team} credentials={session.credentials} logout={logout} /> : <Login onLogin={onLogin} />}{mode === 'student' && sessionExpired && <div className="session-expired"><div className="card"><h2>Participant session expired</h2><p>Your session has expired for security. Please sign in again.</p><button onClick={logout}>Sign out</button></div></div>}<button className="mode-switch" onClick={switchMode}>{mode === 'admin' ? 'Participant login' : 'Admin dashboard'}</button></>;
}

createRoot(document.getElementById('root')).render(<App />);
