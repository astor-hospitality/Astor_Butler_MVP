/* ============================================================
   Astor — server-backed manager cabinet. Only server acknowledgements
   change the displayed data. No demo-data fallback or waiter impersonation.
   ============================================================ */

(function () {
  "use strict";

  const api = window.AstorStaffApi;
  let authenticated = false, busy = false, pendingMutation = null;

  const STATUS_LABELS = {
    ASSIGNED: "Назначено",
    ACCEPTED: "Принято",
    IN_PROGRESS: "В работе",
    HELP_REQUESTED: "Нужна помощь",
    DONE: "Готово",
    CANCELLED: "Отменено",
  };
  const ROLE_LABELS = { WAITER: "Официант", HOSTESS: "Хостес", MANAGER: "Менеджер" };
  const EVENT_LABELS = {
    ASSIGNED: "Назначено",
    DELIVERED: "Доставлено на телефон",
    VOICED: "Озвучено в очках",
    ACCEPT: "Принято",
    STAGE_DONE: "Шаг готов",
    EVIDENCE: "Фото получено",
    HELP: "Нужна помощь",
    HELP_RESOLVED: "Помощь оказана",
    COMPLETE: "Завершено",
    CANCEL: "Отменено",
    REASSIGN: "Переназначено",
  };
  const FILTERS = [
    ["all", "Все"],
    ["active", "В работе"],
    ["help", "Нужна помощь"],
    ["done", "Закрытые"],
  ];

  const state = { tenant: "", staff: [], tasks: [], manageStaff: false, filter: "all", openTaskId: null, message: "" };

  /* ---------- Helpers ---------- */
  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function button(label, className, onClick) {
    const node = el("button", className, label);
    node.type = "button";
    node.addEventListener("click", onClick);
    return node;
  }

  function time(at) {
    return new Date(at).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
  }

  function isFinal(task) {
    return task.status === "DONE" || task.status === "CANCELLED";
  }

  function staffById(staffId) {
    return state.staff.find((person) => person.staffId === staffId) || null;
  }

  function staffName(staffId) {
    const person = staffById(staffId);
    return person ? person.displayName : "не назначен";
  }

  function currentStage(task) {
    return task.stages.find((stage) => !stage.done) || null;
  }

  function lastEventAt(task) {
    return task.events.length ? task.events[task.events.length - 1].at : 0;
  }

  /* ---------- Data ---------- */
  function normalize(data) {
    state.tenant = data.tenant || "";
    state.manageStaff = data.manageStaff === true;
    state.staff = Array.isArray(data.staff) ? data.staff : [];
    state.tasks = (Array.isArray(data.tasks) ? data.tasks : []).map((task) => ({
      taskId: task.taskId,
      sourceRef: task.sourceRef || "",
      tableCode: String(task.tableCode),
      title: task.title,
      instruction: task.instruction || "",
      priority: task.priority === "HIGH" ? "HIGH" : "NORMAL",
      deadlineAt: task.deadline ? Date.parse(task.deadline) : null,
      assigneeStaffId: task.assigneeStaffId,
      status: task.status,
      version: task.version,
      deliveredAt: task.deliveredAt ? Date.parse(task.deliveredAt) : null,
      voicedAt: task.voicedAt ? Date.parse(task.voicedAt) : null,
      stages: task.stages.map((stage) => ({ ...stage })),
      events: [],
    }));
  }

  /* ---------- Server commands ---------- */
  let connected = false;
  function message(error) {
    const codes = { VERSION_CONFLICT: "Поручение уже изменено. Данные обновлены — проверьте и повторите действие.",
      ASSIGNEE_OFF_SHIFT: "У сотрудника закрыта смена.", STAFF_INACTIVE: "Сотрудник не активен в заведении.",
      EVENT_CONFLICT: "Запрос уже использован для другого действия.", FORBIDDEN: "Недостаточно прав для кабинета.",
      NOT_FOUND: "Поручение или сотрудник не найден.", EVIDENCE_REQUIRED: "Для шага требуется настоящее фото." };
    return codes[error.code] || (error.status === 401 ? "Сессия истекла. Войдите снова." : error.message || "Нет связи с сервером.");
  }
  async function mutate(path, body, method = "POST", withEvent = true) {
    if (busy || !connected) return false;
    busy = true;
    document.getElementById("newTaskButton").disabled = true;
    const key = method + path + JSON.stringify(body);
    if (withEvent) {
      if (!pendingMutation || pendingMutation.key !== key) pendingMutation = { key, eventId: crypto.randomUUID() };
      body = { ...body, eventId: pendingMutation.eventId };
    }
    try {
      await api.request(path, { method, body: JSON.stringify(body) });
      pendingMutation = null;
      state.message = "Изменение подтверждено сервером.";
      await refresh();
      return true;
    } catch (error) {
      if (error.status && error.status < 500) pendingMutation = null;
      state.message = message(error);
      if (error.status === 409) await refresh();
      if (error.status === 401 || error.status === 403) disconnect(error);
      else {
        if (!error.status || error.status >= 500) connected = false;
        document.getElementById("connectionStatus").textContent = "Запрос не подтверждён. " + message(error)
          + (!connected ? " Последние данные устарели, действия недоступны." : "");
      }
      render();
      return false;
    } finally {
      busy = false;
      document.getElementById("newTaskButton").disabled = !connected;
    }
  }
  function run(task, type, staffId) {
    const routes = { REASSIGN: "reassign", CANCEL: "cancel", HELP_RESOLVED: "resolve-help" };
    if (!routes[type]) return;
    return mutate("/api/admin/staff-tasks/" + encodeURIComponent(task.taskId) + "/" + routes[type],
      { expectedVersion: task.version, ...(staffId ? { staffId } : {}) });
  }

  /* ---------- Rendering ---------- */
  function statusChip(task) {
    return el("span", "chip chip-" + task.status.toLowerCase().replace("_", "-"), STATUS_LABELS[task.status]);
  }

  function deliveryText(task) {
    if (isFinal(task)) return "";
    if (!task.deliveredAt) return "Ещё не доставлено сотруднику";
    return "Доставлено " + time(task.deliveredAt) + (task.voicedAt ? " · озвучено " + time(task.voicedAt) : "");
  }

  function progressText(task) {
    const done = task.stages.filter((stage) => stage.done).length;
    const photos = task.stages.reduce((sum, stage) => sum + stage.evidence, 0);
    return "Шаги: " + done + " из " + task.stages.length + (photos ? " · фото: " + photos : "");
  }

  function headRow(task) {
    const row = el("div", "task-head");
    row.append(el("span", "table-chip", "Стол " + task.tableCode), statusChip(task));
    if (task.priority === "HIGH" && !isFinal(task)) row.append(el("span", "chip chip-high", "Срочно"));
    return row;
  }

  function metaText(task) {
    const parts = [staffName(task.assigneeStaffId), task.sourceRef];
    if (task.deadlineAt && !isFinal(task)) parts.push("до " + time(task.deadlineAt));
    return parts.filter(Boolean).join(" · ");
  }

  function taskCard(task) {
    const item = el("li");
    const card = button("", "task-card", () => openTask(task.taskId));
    card.setAttribute("aria-label", "Стол " + task.tableCode + ": " + task.title + ". " + STATUS_LABELS[task.status]);
    // Styling hooks for the line along the bottom edge of help, urgent and done cards.
    card.dataset.status = task.status.toLowerCase().replace("_", "-");
    if (task.priority === "HIGH" && !isFinal(task)) card.dataset.priority = "high";
    card.append(headRow(task), el("p", "task-title", task.title), el("p", "task-meta", metaText(task)));
    const delivery = deliveryText(task);
    if (delivery) card.append(el("p", "task-meta", delivery));
    card.append(el("p", "task-progress", progressText(task)));
    item.append(card);
    return item;
  }

  function visibleTasks() {
    const group = (task) => (task.status === "HELP_REQUESTED" ? 0 : isFinal(task) ? 2 : 1);
    return state.tasks
      .filter((task) => {
        if (state.filter === "active") return !isFinal(task);
        if (state.filter === "help") return task.status === "HELP_REQUESTED";
        if (state.filter === "done") return isFinal(task);
        return true;
      })
      .sort((a, b) => group(a) - group(b) || lastEventAt(b) - lastEventAt(a));
  }

  function renderFilters() {
    const box = document.getElementById("filters");
    box.replaceChildren(...FILTERS.map(([key, label]) => {
      const node = button(label, "filter", () => { state.filter = key; render(); });
      node.setAttribute("aria-pressed", String(state.filter === key));
      return node;
    }));
  }

  function renderStaff() {
    const list = document.getElementById("staffList");
    list.replaceChildren(...state.staff.map((person) => {
      const open = state.tasks.filter((task) => task.assigneeStaffId === person.staffId && !isFinal(task));
      const item = el("li", "staff");
      item.append(el("p", "staff-name", person.displayName));
      item.append(el("p", "staff-meta", [ROLE_LABELS[person.role] || person.role, person.deviceId || "без очков"].join(" · ")));
      const onShift = person.shift === "OPEN";
      const line = onShift ? "На смене · поручений: " + open.length : "Смена закрыта";
      item.append(el("p", "staff-meta" + (onShift ? "" : " is-off"), line));
      if (open.some((task) => task.status === "HELP_REQUESTED")) item.append(el("span", "chip chip-help-requested", "Просит помощь"));
      if (person.active && state.manageStaff && connected) item.append(button(onShift ? "Закрыть смену" : "Открыть смену", "btn btn-small",
        () => mutate("/api/admin/staff/members/" + encodeURIComponent(person.staffId) + "/shift",
          { open: !onShift, deviceId: person.deviceId || null }, "POST", false)));
      return item;
    }));
  }

  function stageRow(stage, index, isCurrent) {
    const row = el("li", "stage" + (stage.done ? " is-done" : isCurrent ? " is-current" : ""));
    row.append(el("span", "stage-num", String(index + 1)));
    const body = el("div");
    body.append(el("p", "stage-title", stage.title));
    const stateText = stage.done ? "Готов" : isCurrent ? "Текущий шаг" : "Ждёт";
    body.append(el("p", "stage-meta", stateText + (stage.evidenceRequired ? " · нужно фото" : "")));
    if (stage.evidence) {
      const photos = el("div", "photos");
      for (let i = 0; i < stage.evidence; i += 1) photos.append(el("span", "photo", "Фото " + (i + 1)));
      body.append(photos);
    }
    row.append(body);
    return row;
  }

  function reassignControl(task) {
    const wrap = el("div", "inline-control");
    const select = el("select");
    select.setAttribute("aria-label", "Кому переназначить");
    state.staff.filter((person) => person.active && person.shift === "OPEN" && person.staffId !== task.assigneeStaffId).forEach((person) => {
      const option = el("option", "", person.displayName + (person.shift === "OPEN" ? "" : " (смена закрыта)"));
      option.value = person.staffId;
      select.append(option);
    });
    wrap.append(select, button("Переназначить", "btn btn-quiet btn-small", () => run(task, "REASSIGN", select.value)));
    return wrap;
  }

  function renderTaskDialog() {
    const dialog = document.getElementById("taskDialog");
    const task = state.tasks.find((item) => item.taskId === state.openTaskId);
    if (!task) {
      if (dialog.open) dialog.close();
      return;
    }
    document.getElementById("taskDialogTitle").textContent = task.title;
    const body = document.getElementById("taskDialogBody");
    const stage = currentStage(task);
    const nodes = [headRow(task), el("p", "task-meta", metaText(task)), el("p", "task-meta", "Версия " + task.version)];

    if (task.instruction) {
      const say = el("div", "say");
      say.append(el("p", "say-label", "Озвучивается в очках"), el("p", "", task.instruction));
      nodes.push(say);
    }

    const stages = el("ol", "stages");
    task.stages.forEach((item, index) => stages.append(stageRow(item, index, !isFinal(task) && item === stage)));
    nodes.push(el("h3", "sub-title", "Шаги"), stages);

    const message = el("p", "sheet-message", state.message);
    message.setAttribute("role", "status");
    nodes.push(message);

    if (!isFinal(task) && connected) {
      const manager = el("div", "actions");
      if (task.status === "HELP_REQUESTED") manager.append(button("Помощь оказана", "btn btn-primary btn-small", () => run(task, "HELP_RESOLVED")));
      manager.append(reassignControl(task), button("Отменить поручение", "btn btn-quiet btn-small", () => run(task, "CANCEL")));
      nodes.push(el("h3", "sub-title", "Действия менеджера"), manager);

      nodes.push(el("p", "task-meta", "Принятие, выполнение и отметки доставки поступают от авторизованного сотрудника, не из кабинета менеджера."));
    }

    const history = el("ol", "history");
    task.events.slice().reverse().forEach((event) => {
      const row = el("li", "history-row");
      row.append(el("span", "history-time", time(event.at)));
      row.append(el("span", "", [EVENT_LABELS[event.type] || event.type, event.detail, event.actor].filter(Boolean).join(" · ")));
      history.append(row);
    });
    nodes.push(el("h3", "sub-title", "История"), history);

    body.replaceChildren(...nodes);
    if (!dialog.open) dialog.showModal();
  }

  function render() {
    document.getElementById("memberRegistration").hidden = !state.manageStaff || !connected;
    document.getElementById("tenantTitle").textContent = state.tenant;
    renderFilters();
    renderStaff();
    const tasks = visibleTasks();
    document.getElementById("taskList").replaceChildren(...tasks.map(taskCard));
    const status = document.getElementById("feedStatus");
    status.hidden = tasks.length > 0;
    status.textContent = "Поручений в этом разделе нет.";
    renderTaskDialog();
  }

  async function openTask(taskId, preserveMessage = false) {
    state.openTaskId = taskId;
    if (!preserveMessage) state.message = "";
    render();
    try {
      const events = await api.request("/api/admin/staff-tasks/" + encodeURIComponent(taskId) + "/history");
      if (state.openTaskId !== taskId) return;
      const task = state.tasks.find((item) => item.taskId === taskId);
      if (task) task.events = events.map((event) => ({ ...event, at: Date.parse(event.at) })).reverse();
      render();
    } catch (error) {
      if (error.status === 401 || error.status === 403) disconnect(error);
      else { state.message = "История не загружена. " + message(error); render(); }
    }
  }

  /* ---------- New task ---------- */
  function setupNewTask() {
    const dialog = document.getElementById("newTaskDialog");
    const form = document.getElementById("newTaskForm");
    document.getElementById("newTaskButton").addEventListener("click", () => {
      const select = document.getElementById("newTaskAssignee");
      select.replaceChildren(...state.staff.filter((person) => person.active && person.shift === "OPEN").map((person) => {
        const option = el("option", "", person.displayName + " · " + (ROLE_LABELS[person.role] || person.role));
        option.value = person.staffId;
        return option;
      }));
      dialog.showModal();
    });
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      const data = new FormData(form);
      const titles = String(data.get("stages")).split("\n").map((line) => line.trim()).filter(Boolean).slice(0, 6);
      if (!titles.length) return;
      const task = {
        sourceRef: "Менеджер",
        tableCode: String(data.get("tableCode")).trim(),
        title: String(data.get("title")).trim(),
        instruction: String(data.get("instruction")).trim(),
        priority: data.get("high") ? "HIGH" : "NORMAL",
        deadline: null,
        assigneeStaffId: data.get("assignee"),
        stages: titles.map((title, index) => ({
          code: "s" + (index + 1),
          title,
          evidenceRequired: false,
        })),
      };
      if (await mutate("/api/admin/staff-tasks", { task })) {
        form.reset(); dialog.close(); state.filter = "all"; render();
      } else {
        // Keep entered fields for a safe retry with the same event id after a network failure.
        if (connected) document.getElementById("connectionStatus").textContent = state.message;
      }
    });
  }

  function setupDialogs() {
    document.querySelectorAll("dialog").forEach((dialog) => {
      dialog.querySelectorAll("[data-close]").forEach((node) => node.addEventListener("click", () => dialog.close()));
      // A click on the backdrop lands on the dialog element itself.
      dialog.addEventListener("click", (event) => { if (event.target === dialog) dialog.close(); });
    });
    document.getElementById("taskDialog").addEventListener("close", () => { state.openTaskId = null; state.message = ""; });
  }

  function applyTheme() {
    let stored = null;
    try { stored = window.localStorage.getItem("astor-theme"); } catch (e) { /* storage may be blocked */ }
    const systemLight = window.matchMedia("(prefers-color-scheme: light)").matches;
    document.body.classList.toggle("light-theme", (stored || (systemLight ? "light" : "dark")) === "light");
  }

  function disconnect(error) {
    authenticated = connected = false;
    state.staff = []; state.tasks = []; state.tenant = ""; state.manageStaff = false; state.openTaskId = null;
    document.getElementById("loginButton").hidden = false;
    document.getElementById("logoutButton").hidden = true;
    document.getElementById("connectionStatus").textContent = message(error);
    document.getElementById("newTaskButton").disabled = true;
    render();
  }
  async function refresh() {
    try {
      const data = await api.request("/api/admin/staff-tasks/dashboard");
      connected = true;
      normalize(data);
      document.getElementById("connectionStatus").textContent = "Сервер Astor · обновлено " + time(Date.now());
      document.getElementById("newTaskButton").disabled = busy;
      render();
      if (state.openTaskId) await openTask(state.openTaskId, true);
    } catch (error) {
      connected = false;
      document.getElementById("newTaskButton").disabled = true;
      if (error.status === 401 || error.status === 403) disconnect(error);
      else {
        document.getElementById("connectionStatus").textContent = "Нет актуальной связи. Показаны последние полученные данные, действия недоступны.";
        render();
      }
    }
  }
  async function load() {
    try {
      authenticated = await api.initialize();
      document.getElementById("loginButton").hidden = authenticated;
      document.getElementById("logoutButton").hidden = !authenticated;
      if (authenticated) await refresh();
      else document.getElementById("connectionStatus").textContent = "Войдите через Keycloak Astor. Учебные данные не используются.";
    } catch (error) { disconnect(error); }
  }

  applyTheme();
  setupDialogs();
  setupNewTask();
  document.getElementById("loginButton").addEventListener("click", () => api.login().catch(disconnect));
  document.getElementById("logoutButton").addEventListener("click", () => api.logout());
  document.getElementById("memberForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.currentTarget, data = new FormData(form);
    if (await mutate("/api/admin/staff/members/" + encodeURIComponent(data.get("staffId")),
      { displayName: data.get("displayName"), role: data.get("role"), active: true }, "PUT", false)) form.reset();
  });
  load();
  setInterval(() => { if (authenticated && !busy && !document.hidden) refresh(); }, 15000);
})();
