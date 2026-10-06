import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';

const source = readFileSync(new URL('../js/staff.js', import.meta.url), 'utf8');
class Element {
  constructor() { this.children = []; this.listeners = new Map(); this.classList = { toggle() {} }; this.fields = new Map(); this.dataset = {}; }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  addEventListener(type, handler) { this.listeners.set(type, handler); }
  setAttribute() {}
  querySelectorAll() { return []; }
  showModal() { this.open = true; }
  close() { this.open = false; this.listeners.get('close')?.(); }
  reset() { this.fields.clear(); }
  async event(type) { return this.listeners.get(type)?.({ preventDefault() {}, currentTarget: this }); }
}
function harness(options = {}) {
  const nodes = new Map(), requests = [], intervals = [];
  const node = id => { if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id); };
  node('newTaskButton').disabled = true; // initial disabled attribute in staff/index.html
  const data = { tenant: 'AERIS', manageStaff: options.manageStaff !== false,
    staff: [{ staffId: 'anna', displayName: 'Anna', role: 'WAITER', active: true, shift: 'OPEN' }], tasks: [] };
  let deny = false, lost = !!options.lostReply, offline = false;
  const api = {
    initialize: async () => options.authenticated !== false,
    login: async () => {}, logout() {},
    request: async (path, init) => {
      requests.push({ path, init });
      if (deny) throw Object.assign(new Error('Denied'), { status: 403, code: 'STAFF_INACTIVE' });
      if (offline) throw new Error('Network error');
      if (path.endsWith('/dashboard')) return structuredClone(data);
      if (init?.method === 'POST' && path === '/api/admin/staff-tasks') {
        const body = JSON.parse(init.body);
        if (!data.tasks.length) data.tasks.push({ ...body.task, taskId: 'task-1', status: 'ASSIGNED', version: 1,
          stages: body.task.stages.map(stage => ({ ...stage, done: false, evidence: 0 })) });
        if (lost) { lost = false; throw new Error('Lost response after commit'); }
        return structuredClone(data.tasks[0]);
      }
      return [];
    }
  };
  vm.runInNewContext(source, {
    window: { AstorStaffApi: api, localStorage: { getItem: () => null }, matchMedia: () => ({ matches: false }) },
    document: { body: new Element(), hidden: false, createElement: () => new Element(), getElementById: node,
      querySelectorAll: () => [node('taskDialog'), node('newTaskDialog')] },
    crypto: webcrypto, setInterval: callback => intervals.push(callback),
    FormData: class { constructor(form) { this.form = form; } get(key) { return this.form.fields.get(key); } }
  });
  const flush = () => new Promise(resolve => setImmediate(resolve));
  const fields = { tableCode: '13', title: 'Prepare table', instruction: 'Check setting', stages: 'Setting', assignee: 'anna' };
  for (const [key, value] of Object.entries(fields)) node('newTaskForm').fields.set(key, value);
  return { node, requests, data, flush, poll: async () => { await intervals[0](); await flush(); },
    deny: () => { deny = true; }, offline: () => { offline = true; } };
}
test('unauthenticated cabinet is empty, cannot create and never requests demo JSON', async () => {
  const h = harness({ authenticated: false }); await h.flush();
  assert.equal(h.node('loginButton').hidden, false); assert.equal(h.node('newTaskButton').disabled, true);
  await h.node('newTaskForm').event('submit');
  assert.equal(h.requests.length, 0); assert.equal(h.node('taskList').children.length, 0);
});
test('lost mutation response is not an optimistic success; retry reuses the event UUID', async () => {
  const h = harness({ lostReply: true }); await h.flush();
  await h.node('newTaskForm').event('submit');
  assert.equal(h.node('taskList').children.length, 0); assert.equal(h.node('newTaskButton').disabled, true);
  assert.equal(h.node('newTaskForm').fields.size, 5);
  assert.match(h.node('connectionStatus').textContent, /не подтверждён/);
  await h.poll();
  assert.equal(h.node('taskList').children.length, 1);
  await h.node('newTaskForm').event('submit');
  const creates = h.requests.filter(r => r.path === '/api/admin/staff-tasks');
  assert.equal(creates.length, 2);
  assert.equal(JSON.parse(creates[0].init.body).eventId, JSON.parse(creates[1].init.body).eventId);
  assert.equal(h.data.tasks.length, 1); assert.equal(h.node('newTaskForm').fields.size, 0);
});
test('403 clears old private state while network failure marks it stale and disables actions', async () => {
  for (const denial of [false, true]) {
    const h = harness(); await h.flush(); await h.node('newTaskForm').event('submit');
    assert.equal(h.node('taskList').children.length, 1);
    if (denial) h.deny(); else h.offline();
    await h.poll();
    assert.equal(h.node('newTaskButton').disabled, true);
    assert.equal(h.node('taskList').children.length, denial ? 0 : 1);
    assert.equal(h.node('memberRegistration').hidden, true);
  }
});
test('hostess sees no directory/shift controls and staff commands are not impersonated by manager UI', async () => {
  const h = harness({ manageStaff: false }); await h.flush();
  assert.equal(h.node('memberRegistration').hidden, true);
  assert.equal(h.node('staffList').children[0].children.some(n => n.textContent === 'Закрыть смену'), false);
  assert.doesNotMatch(source, /function apply\(|staff-demo\.json|Сыграть за официанта/);
});
