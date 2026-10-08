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
  if (!response.ok) {
    throw new Error(body.message || body.detail || body.title || 'Something went wrong');
  }
  return body;
}

function Login({ onLogin }) {
  const [form, setForm] = useState({ username: '', registerNumber: '' });
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
  return <main className="login-shell"><section className="hero"><img className="login-logo" src="/vit-logo-transparent.png" alt="Vellore Institute of Technology" /><span className="eyebrow">VIT - JAVA HACKATHON 2026</span><h1>Build something<br /><em>that matters.</em></h1><p>Your team space for the assigned problem statement and team details.</p><div className="stat-row"><strong>500+ <small>participants</small></strong><strong>100 <small>teams</small></strong><strong>24h <small>to build</small></strong></div></section>
    <form className="card login-card" onSubmit={submit}><span className="eyebrow">PARTICIPANT PORTAL</span><h2>Welcome back.</h2><p className="muted">Use your team username and your register number to sign in.</p><label>Team username<input required value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} placeholder="Username from your team registration" /></label><label>Register number<input required type="password" value={form.registerNumber} onChange={e => setForm({ ...form, registerNumber: e.target.value })} placeholder="Your register number" /></label>{error && <div className="error" role="alert">{error}</div>}<button disabled={submitting}>{submitting ? 'Signing in…' : 'Enter team space'} <span>→</span></button></form></main>;
}

function Student({ team, credentials, logout }) {
  const [links, setLinks] = useState({ googleDriveLink: team.submission?.googleDriveLink || '', githubLink: team.submission?.githubLink || '' });
  const [notice, setNoticeState] = useState('');
  const [noticeType, setNoticeType] = useState('success');
  function setNotice(message) {
    setNoticeState(message);
    setNoticeType('success');
  }
  function setErrorNotice(message) {
    setNoticeState(message);
    setNoticeType('error');
  }
  const [submitting, setSubmitting] = useState(false);
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
  return <main className="app-shell"><header><div className="brand"><img src="/vit-logo-transparent.png" alt="Vellore Institute of Technology" /><span>HACKATHON</span></div><div className="header-actions"><span className="session-label">Participant session active</span><button className="ghost" onClick={logout}>Sign out</button></div></header><div className="content"><div className="welcome"><span className="eyebrow">TEAM SPACE</span><h1>{team.name}</h1><p className="muted">Your group details and assigned problem statement.</p><div className="student-summary"><span><small>GROUP ID</small>#{team.teamNumber}</span><span><small>YOUR DETAILS</small>{team.ownName || 'Name not provided'} · {team.ownRegisterNumber}{team.ownEmail && <small>{team.ownEmail}</small>}</span><span><small>GROUP LEADER</small>{team.leaderRegisterNumber || 'Not assigned'}</span></div></div><div className="student-grid"><section className="card problem-card"><div className="card-top"><span className="eyebrow">PROBLEM {team.problem ? `#${team.problem.id}` : ''}</span><span className="pill">{team.problem ? 'Enabled' : 'Not enabled'}</span></div>{team.problem ? <><h2>{team.problem.title}</h2><p>{team.problem.statement}</p></> : <div className="empty">The problem statement has not been enabled yet.</div>}<h3>Group members</h3><div className="members">{team.students.map(s => <div className="member" key={s.registerNumber}><span>{(s.name || s.registerNumber).charAt(0)}</span><div><b>{s.name || 'Name not provided'}</b><small>{s.registerNumber}{s.leader ? ' - Group leader' : ''}</small></div></div>)}</div></section>{team.leader && <form className="card submission-card" onSubmit={submit}><span className="eyebrow">FINAL SUBMISSION</span><h2>Submit your links</h2><p className="muted">Only the group leader can submit or update these links.</p><label>Google Drive document link<input required type="url" value={links.googleDriveLink} onChange={e => setLinks({ ...links, googleDriveLink: e.target.value })} /></label><label>GitHub repository link<input required type="url" value={links.githubLink} onChange={e => setLinks({ ...links, githubLink: e.target.value })} /></label>{notice && <div className={noticeType} role={noticeType === 'error' ? 'alert' : 'status'}>{notice}</div>}<button disabled={submitting}>{submitting ? 'Saving links…' : team.submission ? 'Update final links' : 'Submit final links'}</button></form>}</div></div></main>;
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
  const [password, setPassword] = useState('');
  const [authed, setAuthed] = useState(false);
  const [teams, setTeams] = useState([]);
  const [problems, setProblems] = useState([]);
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
  const [loginSettings, setLoginSettings] = useState({ loginEnabled: true, startTime: '', endTime: '' });
  const [submissions, setSubmissions] = useState([]);
  const [downloadingSubmissions, setDownloadingSubmissions] = useState(false);
  const [loadingData, setLoadingData] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const headers = () => ({ 'X-Admin-Password': password });
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
    clearSession('hackathon-admin-session');
  }, []);
  useEffect(() => {
    if (!authed) setPassword('');
  }, [authed]);
  useEffect(() => {
    if (!authed || !password) return undefined;
    const timer = window.setInterval(() => {
      request('/admin/submissions', { headers: headers() })
        .then(setSubmissions)
        .catch(error => setErrorNotice(error.message));
    }, 30000);
    return () => window.clearInterval(timer);
  }, [authed, password]);
  async function load() {
    setLoadingData(true);
    setLoadError(false);
    const authHeaders = headers();
    try {
      const [loadedTeams, loadedProblems, loadedSettings, loadedSubmissions] = await Promise.all([
        request('/admin/teams', { headers: authHeaders }),
        request('/admin/problems', { headers: authHeaders }),
        request('/admin/settings', { headers: authHeaders }),
        request('/admin/submissions', { headers: authHeaders })
      ]);
      setTeams(loadedTeams); setProblems(loadedProblems); setLoginSettings(loadedSettings); setSubmissions(loadedSubmissions); setAuthed(true);
    } catch (error) {
      setLoadError(true);
      throw error;
    } finally {
      setLoadingData(false);
    }
  }
  async function saveLoginSettings(event) {
    event.preventDefault();
    const saved = await request('/admin/settings', { method: 'PUT', headers: headers(), body: JSON.stringify(loginSettings) });
    setLoginSettings(saved); setNotice('Participant login settings updated.');
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
  async function toggleProblem(problem) {
    const saved = await request(`/admin/problems/${problem.id}/enabled`, { method: 'PUT', headers: headers(), body: JSON.stringify({ enabled: !problem.enabled }) });
    setProblems(problems.map(item => item.id === saved.id ? saved : item));
    setNotice(saved.enabled ? 'Problem enabled for participants.' : 'Problem hidden from participants.');
  }
  async function createTeam(e) {
    e.preventDefault();
    setTeamCreateError('');
    if (!newTeamFields[2].fieldValue.trim() || !newTeamFields[3].fieldValue.trim()) {
      setTeamCreateError('Enter the group leader’s name and register number.');
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
    setTeams(teams.map(team => team.id === teamId ? updated : team)); setNotice('Problem assigned.');
  }
  if (!authed) return <main className="login-shell admin-login"><form className="card login-card" onSubmit={e => { e.preventDefault(); load().catch(err => setErrorNotice(err.message)); }}><span className="eyebrow">COORDINATOR DASHBOARD</span><h2>Admin access.</h2><p className="muted">Manage teams, participants, and challenges one by one.</p><label>Admin password<input type="password" required value={password} onChange={e => setPassword(e.target.value)} /></label>{notice && <div className="error">{notice}</div>}<button disabled={loadingData}>{loadingData ? 'Connecting…' : 'Open dashboard'} <span>&gt;</span></button></form></main>;
  return <main className="app-shell">  <header><div className="brand"><img src="/vit-logo-transparent.png" alt="Vellore Institute of Technology" /><span>HACKATHON</span></div><div className="header-actions"><button className="ghost" onClick={() => setAuthed(false)}>Sign out</button></div></header>  <div className="content admin-content"><div className="welcome"><span className="eyebrow">COORDINATOR DASHBOARD</span><h1>Command center</h1><p className="muted">Create teams and participants individually, then assign problem statements.</p></div>{notice && <div className={noticeType} role={noticeType === 'error' ? 'alert' : 'status'}>{notice}</div>}<form className="card settings-form" onSubmit={saveLoginSettings}><div className="card-top"><div><span className="eyebrow">PARTICIPANT ACCESS</span><h2>Login window</h2></div><button>Save settings</button></div><label className="checkbox-label"><input type="checkbox" checked={loginSettings.loginEnabled} onChange={e => setLoginSettings({ ...loginSettings, loginEnabled: e.target.checked })} /> Allow participant login</label><div className="settings-fields"><label>Start time (optional)<input type="datetime-local" value={loginSettings.startTime ? loginSettings.startTime.slice(0, 16) : ''} onChange={e => setLoginSettings({ ...loginSettings, startTime: e.target.value ? new Date(e.target.value).toISOString() : '' })} /></label><label>End time (optional)<input type="datetime-local" value={loginSettings.endTime ? loginSettings.endTime.slice(0, 16) : ''} onChange={e => setLoginSettings({ ...loginSettings, endTime: e.target.value ? new Date(e.target.value).toISOString() : '' })} /></label></div></form><section className="card table-card"><div className="card-top"><div><span className="eyebrow">SUBMISSIONS</span><h2>Team submissions</h2></div><div className="problem-library-actions"><span className="pill">{loadingData ? 'Loading…' : submissions.length + ' submitted'}</span><button type="button" className="small-button" onClick={downloadSubmissions} disabled={downloadingSubmissions}>{downloadingSubmissions ? 'Downloading...' : 'Download Excel'}</button><button type="button" className="small-button danger-button" onClick={clearSubmissions} disabled={loadingData}>Clear all</button></div></div>{loadingData ? <LoadingState label="Loading team submissions…" /> : loadError ? <LoadErrorState onRetry={() => load().catch(error => setErrorNotice(error.message))} /> : submissions.length === 0 ? <div className="empty">No team submissions yet.</div> : <div className="table-wrap"><table><thead><tr><th>Group</th><th>Google Drive</th><th>GitHub</th><th>Updated</th></tr></thead><tbody>{submissions.map(item => <tr key={item.id}><td><b>{item.team?.name || `#${item.team?.teamNumber || ''}`}</b></td><td><a href={item.googleDriveLink} target="_blank" rel="noreferrer">Open document</a></td><td><a href={item.githubLink} target="_blank" rel="noreferrer">Open repository</a></td><td>{item.updatedAt ? new Date(item.updatedAt).toLocaleString() : '-'}</td></tr>)}</tbody></table></div>}</section><section className="card table-card"><div className="card-top library-card-top"><div><span className="eyebrow">PROBLEM LIBRARY</span><h2>Problem statements</h2></div>  <div className="problem-library-actions"><span className="pill">{loadingData ? 'Loading…' : problems.length + ' questions'}</span><button type="button" className="small-button" onClick={() => { setEditingProblem(null); setNewProblem({ title: '', statement: '' }); setActionDialog('problem'); }}>Add problem</button><button type="button" className="small-button" onClick={() => setImportDialog('questions')}>Import questions</button>  <button className="small-button" onClick={randomlyAssignQuestions}>Randomly assign evenly</button><button className="small-button danger-button" onClick={clearProblems}>Clear all</button></div></div><div className="problem-list">{loadingData ? <LoadingState label="Loading problem statements…" /> : loadError ? <LoadErrorState onRetry={() => load().catch(error => setErrorNotice(error.message))} /> : problems.length === 0 ? <div className="empty">No problem statements yet.</div> : problems.map(problem => <div className="problem-row" key={problem.id}><div><b>#{problem.id}  -  {problem.title}</b><p>{problem.statement}</p></div><div className="row-actions">  <button className="small-button" onClick={() => toggleProblem(problem)}>{problem.enabled ? 'Disable' : 'Enable'}</button><button className="small-button" onClick={() => { setEditingProblem(problem); setNewProblem({ title: problem.title, statement: problem.statement }); setActionDialog('problem'); }}>Edit</button><button className="small-button danger-button" onClick={() => deleteProblem(problem.id)}>Delete</button></div></div>)}</div></section><section className="card table-card"><div className="card-top library-card-top"><div><span className="eyebrow">TEAM ROSTER</span>  <h2>Teams and assignments</h2></div><div className="problem-library-actions"><span className="pill">{loadingData ? 'Loading…' : teams.length + ' teams'}</span><button type="button" className="small-button" onClick={() => setActionDialog('team')}>Create team</button><button type="button" className="small-button" onClick={() => setImportDialog('teams')}>Import teams</button><button className="small-button danger-button" onClick={clearTeams}>Clear all</button></div></div><div className="table-wrap"><table><thead><tr><th>Team</th><th>Members</th><th>Problem</th><th>Actions</th></tr></thead><tbody>{loadingData ? <tr><td colSpan="4"><LoadingState label="Loading teams…" /></td></tr> : loadError ? <tr><td colSpan="4"><LoadErrorState onRetry={() => load().catch(error => setErrorNotice(error.message))} /></td></tr> : teams.length === 0 ? <tr><td colSpan="4"><div className="empty">No teams yet.</div></td></tr> : teams.map(team =>   <tr key={team.id}><td><b>#{team.teamNumber}  -  {team.name}</b></td><td>{team.students.length}</td><td><select value={team.problem?.id || ''} onChange={e => assign(team.id, e.target.value)}><option value="">Unassigned</option>{problems.map(p => <option key={p.id} value={p.id}>{p.id}  -  {p.title}</option>)}</select></td>  <td><div className="row-actions"><button className="small-button" onClick={() => setEditingTeam(team)}>Edit</button><button className="small-button" onClick={() => renameTeam(team)}>Rename</button><button className="small-button danger-button" onClick={() => deleteTeam(team.id)}>Delete</button></div></td></tr>)}</tbody></table></div></section></div>  {editingTeam && <div className="modal-backdrop" onClick={() => setEditingTeam(null)}><section className="member-modal card" onClick={e => e.stopPropagation()}><div className="card-top"><div><span className="eyebrow">EDIT MEMBERS</span><h2>{editingTeam.name}</h2></div><button type="button" className="ghost" onClick={() => setEditingTeam(null)}>Close</button></div><form className="student-form modal-student-form" onSubmit={e => addStudent(e, editingTeam.id)}><input required placeholder="Participant name" value={student.name} onChange={e => setStudent({ ...student, name: e.target.value })} /><input required placeholder="Register number" value={student.registerNumber} onChange={e => setStudent({ ...student, registerNumber: e.target.value })} /><input required={false} type="email" placeholder="Email (optional)" value={student.email} onChange={e => setStudent({ ...student, email: e.target.value })} />  <button>Add participant</button></form>{leaderActionError && <div className="error" role="alert">{leaderActionError}</div>}{editingTeam.students.length === 0 ? <div className="empty">No participants in this team yet.</div> : <div className="modal-member-list">{editingTeam.students.map(item => <div className="modal-member" key={item.id}><div><b>{item.name}</b><small>{item.registerNumber}  -  {item.email || 'No email'}{item.leader ? '  -  Leader' : ''}</small></div><div className="row-actions">{!item.leader && <button type="button" className="small-button" disabled={assigningLeaderId !== null} onClick={() => assignLeader(item.id)}>{assigningLeaderId === item.id ? 'Assigning…' : 'Assign as leader'}</button>}<button type="button" className="small-button" onClick={() => editStudent(item)}>Edit</button><button type="button" className="small-button danger-button" onClick={() => deleteStudent(item.id)}>Remove</button></div></div>)}</div>}{editingTeam.importedFields?.length > 0 && <form className="imported-fields-form" onSubmit={saveImportedFields}><details open><summary>Team details ({importedFieldLabels.length} editable fields)</summary><div className="imported-fields-grid">{orderImportedFields(editingTeam.importedFields || []).slice(0, importedFieldLabels.length).map(field => <label className="imported-field" key={field.columnIndex}><b>{field.fieldName}</b><input maxLength={5000} value={field.fieldValue || ''} onChange={event => setEditingTeam(current => current ? { ...current, importedFields: orderImportedFields(current.importedFields || []).map(item => item.columnIndex === field.columnIndex ? { ...item, fieldValue: event.target.value } : item) } : current)} /></label>)}</div>  </details>{importedFieldsError && <div className="error" role="alert">{importedFieldsError}</div>}{importedFieldsNotice && <div className="success" role="status">{importedFieldsNotice}</div>}<button type="submit" className="small-button" disabled={savingImportedFields}>{savingImportedFields ? 'Saving…' : 'Save team details'}</button></form>}</section></div>}{dialog && <div className="modal-backdrop" onClick={() => setDialog(null)}><form className="card dialog-modal" onClick={e => e.stopPropagation()} onSubmit={saveDialog}><div className="card-top"><h2>{dialog.title}</h2><button type="button" className="ghost" onClick={() => setDialog(null)}>Close</button></div>{dialog.fields.map(field => <label key={field.name}>{field.label}<input required={field.name === 'registerNumber' || field.name === 'name'} type={field.name === 'password' ? 'password' : field.name === 'email' ? 'email' : 'text'} name={field.name} defaultValue={field.value} /></label>)}<button>Save</button></form></div>}{actionDialog && <div className="modal-backdrop import-modal-backdrop" onClick={closeActionDialog}><section className="card import-modal action-modal" role="dialog" aria-modal="true" aria-labelledby="action-dialog-title" onClick={e => e.stopPropagation()}><div className="card-top import-modal-header"><div><span className="eyebrow">COORDINATOR DASHBOARD</span><h2 id="action-dialog-title">{actionDialog === 'team' ? 'Create a team' : editingProblem ? 'Edit problem statement' : 'Add a problem statement'}</h2></div><button type="button" className="ghost" onClick={closeActionDialog}>Close</button></div><p className="muted">{actionDialog === 'team' ? 'Enter the registration details and participant roster. Leader name and register number are required.' : 'Give the problem a short title and describe the challenge for participants.'}</p>{actionDialog === 'team' ? <form className="action-dialog-form team-create-form" onSubmit={createTeam}>{teamCreateError && <div className="error" role="alert">{teamCreateError}</div>}<div className="team-create-fields">{newTeamFields.map((field, index) => <label key={field.columnIndex}>{field.fieldName}<input autoFocus={index === 2} type={index === 11 ? 'email' : index === 10 ? 'tel' : 'text'} required={index === 2 || index === 3} placeholder={index >= 4 && index <= 9 ? 'Leave both fields blank if this member is not part of the team' : ''} value={field.fieldValue} onChange={e => setNewTeamFields(current => current.map((item, fieldIndex) => fieldIndex === index ? { ...item, fieldValue: e.target.value } : item))} /></label>)}</div><div className="import-modal-actions"><button type="button" className="small-button" onClick={closeActionDialog}>Cancel</button><button className="secondary-button">Create team</button></div></form> : <form className="action-dialog-form" onSubmit={saveProblem}><label>Problem title<input autoFocus required placeholder="e.g. Sustainable Campus" value={newProblem.title} onChange={e => setNewProblem({ ...newProblem, title: e.target.value })} /></label><label>Problem statement<textarea required placeholder="Describe the problem for participants" value={newProblem.statement} onChange={e => setNewProblem({ ...newProblem, statement: e.target.value })} /></label><div className="import-modal-actions"><button type="button" className="small-button" onClick={closeActionDialog}>Cancel</button><button className="secondary-button">{editingProblem ? 'Save changes' : 'Add problem'}</button></div></form>}</section></div>}{importDialog && <div className="modal-backdrop import-modal-backdrop" onClick={closeImportDialog}><section className="card import-modal" role="dialog" aria-modal="true" aria-labelledby="import-dialog-title" onClick={e => e.stopPropagation()}><div className="card-top import-modal-header"><div><span className="eyebrow">BULK IMPORT</span><h2 id="import-dialog-title">{importDialog === 'teams' ? 'Import teams' : 'Import questions'}</h2></div><button type="button" className="ghost" onClick={closeImportDialog}>Close</button></div><p className="muted">{importDialog === 'teams' ? 'Team uploads accept only the exact 19 team table columns shown below, in the same order. Extra, missing, or renamed columns are rejected.' : 'Choose a CSV file with a Statement, Problem Statement, Question, or Description column. Title is optional.'}</p>{importDialog === 'teams' && <div className="team-import-guidance"><button type="button" className="small-button" onClick={downloadTeamImportTemplate}>Download exact CSV template</button><ol>{importedFieldLabels.map(label => <li key={label}><code>{label}</code></li>)}</ol></div>}<form onSubmit={importDialog === 'teams' ? importTeams : importQuestions}><label className="import-file-picker"><input type="file" accept={importDialog === 'teams' ? '.csv,.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv' : '.csv,text/csv'} required onChange={e => importDialog === 'teams' ? setCsvFile(e.target.files[0] || null) : setQuestionCsvFile(e.target.files[0] || null)} /><span className="import-file-button">Choose file</span><span className="import-file-name">{(importDialog === 'teams' ? csvFile : questionCsvFile)?.name || 'No file selected'}</span></label><div className="import-modal-actions"><button type="button" className="small-button" onClick={closeImportDialog}>Cancel</button><button className="secondary-button" disabled={!(importDialog === 'teams' ? csvFile : questionCsvFile)}>Import {importDialog === 'teams' ? 'teams' : 'questions'}</button></div></form></section></div>}</main>;
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
