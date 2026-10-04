/* ============================================================
   Astor — manager cabinet prototype: shift, task feed, stages, photos.

   No backend. Data comes from data/staff-demo.json and every action
   changes only this page. The rules below mirror the proposed contract
   in docs/operations/GLASSES_STAFF_TASKS_P1_DRAFT.md so the prototype
   can be used to discuss it: versions, allowed transitions, photo
   required before a stage or the task can be closed.
   ============================================================ */

(function () {
  "use strict";

  const DATA_URL = new URL("../data/staff-demo.json", window.location.href);
  const MINUTE = 60000;

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

  const state = { tenant: "", staff: [], tasks: [], filter: "all", openTaskId: null, message: "", nextId: 200 };

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
    const now = Date.now();
    const ago = (minutes) => (typeof minutes === "number" ? now - minutes * MINUTE : null);
    state.tenant = data.tenant || "";
    state.staff = Array.isArray(data.staff) ? data.staff : [];
    state.tasks = (Array.isArray(data.tasks) ? data.tasks : []).map((task) => ({
      taskId: task.taskId,
      sourceRef: task.sourceRef || "",
      tableCode: String(task.tableCode),
      title: task.title,
      instruction: task.instruction || "",
      priority: task.priority === "HIGH" ? "HIGH" : "NORMAL",
      deadlineAt: typeof task.deadlineInMinutes === "number" ? now + task.deadlineInMinutes * MINUTE : null,
      assigneeStaffId: task.assigneeStaffId,
      status: task.status,
      version: task.version,
      deliveredAt: ago(task.deliveredMinutesAgo),
      voicedAt: ago(task.voicedMinutesAgo),
      stages: task.stages.map((stage) => ({ ...stage })),
      events: task.events.map((event) => ({ type: event.type, actor: event.actor, at: ago(event.minutesAgo), detail: event.detail || "" })),
    }));
  }

  /* ---------- Rules: what the server would accept ---------- */
  function reject(message) {
    return { ok: false, message };
  }

  function record(task, type, actor, detail) {
    task.events.push({ type, actor, at: Date.now(), detail: detail || "" });
  }

  function apply(task, type, payload) {
    const waiter = staffName(task.assigneeStaffId);
    if (isFinal(task)) return reject("Поручение уже закрыто, статус не меняется.");
    const working = task.status === "ACCEPTED" || task.status === "IN_PROGRESS";
    const stage = currentStage(task);

    switch (type) {
      case "DELIVER":
        if (task.deliveredAt) return reject("Поручение уже доставлено.");
        task.deliveredAt = task.voicedAt = Date.now();
        record(task, "DELIVERED", "телефон сотрудника");
        record(task, "VOICED", "очки");
        return { ok: true }; // delivery is tracked separately and does not change the version
      case "ACCEPT":
        if (!task.deliveredAt) return reject("Поручение ещё не доставлено сотруднику.");
        if (task.status !== "ASSIGNED") return reject("Принять можно только назначенное поручение.");
        task.status = "ACCEPTED";
        record(task, "ACCEPT", waiter);
        break;
      case "EVIDENCE":
        if (!working) return reject("Фото принимается только по принятому поручению.");
        if (!stage) return reject("Все шаги уже закрыты, фото прикрепить не к чему.");
        stage.evidence += 1;
        record(task, "EVIDENCE", "очки", stage.title);
        break;
      case "STAGE_DONE":
        if (!working) return reject("Шаг закрывается только по принятому поручению.");
        if (!stage) return reject("Все шаги уже закрыты.");
        if (stage.evidenceRequired && !stage.evidence) return reject("Для шага «" + stage.title + "» нужно фото.");
        stage.done = true;
        task.status = "IN_PROGRESS";
        record(task, "STAGE_DONE", waiter, stage.title);
        break;
      case "HELP":
        if (!working) return reject("Помощь запрашивается по принятому поручению.");
        task.status = "HELP_REQUESTED";
        record(task, "HELP", waiter);
        break;
      case "HELP_RESOLVED":
        if (task.status !== "HELP_REQUESTED") return reject("Сотрудник не просил помощь.");
        task.status = "IN_PROGRESS";
        record(task, "HELP_RESOLVED", "Менеджер");
        break;
      case "COMPLETE":
        if (!working) return reject("Завершить можно только принятое поручение.");
        if (stage) return reject("Остались незакрытые шаги: «" + stage.title + "».");
        task.status = "DONE";
        record(task, "COMPLETE", waiter);
        break;
      case "CANCEL":
        task.status = "CANCELLED";
        record(task, "CANCEL", "Менеджер");
        break;
      case "REASSIGN": {
        const target = staffById(payload);
        if (!target || target.staffId === task.assigneeStaffId) return reject("Выберите другого сотрудника.");
        if (target.shift !== "OPEN") return reject("У сотрудника закрыта смена.");
        task.assigneeStaffId = target.staffId;
        task.status = "ASSIGNED";
        task.deliveredAt = task.voicedAt = null;
        record(task, "REASSIGN", "Менеджер", target.displayName);
        break;
      }
      default:
        return reject("Неизвестная команда.");
    }
    task.version += 1;
    return { ok: true };
  }

  function run(task, type, payload) {
    const result = apply(task, type, payload);
    state.message = result.ok ? "" : result.message;
    render();
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
    // Styling hooks: the card itself shows that someone needs help or that the task is urgent.
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
      if (open.some((task) => task.status === "HELP_REQUESTED")) {
        item.dataset.help = "true";
        item.append(el("span", "chip chip-help-requested", "Просит помощь"));
      }
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
    state.staff.filter((person) => person.staffId !== task.assigneeStaffId).forEach((person) => {
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

    if (!isFinal(task)) {
      const manager = el("div", "actions");
      if (task.status === "HELP_REQUESTED") manager.append(button("Помощь оказана", "btn btn-primary btn-small", () => run(task, "HELP_RESOLVED")));
      manager.append(reassignControl(task), button("Отменить поручение", "btn btn-quiet btn-small", () => run(task, "CANCEL")));
      nodes.push(el("h3", "sub-title", "Действия менеджера"), manager);

      const waiter = el("div", "actions");
      [["DELIVER", "Доставить и озвучить"], ["ACCEPT", "Принять"], ["EVIDENCE", "Фото шага"], ["STAGE_DONE", "Шаг готов"],
        ["HELP", "Нужна помощь"], ["COMPLETE", "Завершить"]].forEach(([type, label]) => {
        waiter.append(button(label, "btn btn-quiet btn-small", () => run(task, type)));
      });
      nodes.push(el("h3", "sub-title", "Сыграть за официанта"),
        el("p", "task-meta", "Учебная имитация команд из очков. Отказы показывают, что отклонил бы сервер."), waiter);
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

  function openTask(taskId) {
    state.openTaskId = taskId;
    state.message = "";
    render();
  }

  /* ---------- New task ---------- */
  function setupNewTask() {
    const dialog = document.getElementById("newTaskDialog");
    const form = document.getElementById("newTaskForm");
    document.getElementById("newTaskButton").addEventListener("click", () => {
      const select = document.getElementById("newTaskAssignee");
      select.replaceChildren(...state.staff.filter((person) => person.shift === "OPEN").map((person) => {
        const option = el("option", "", person.displayName + " · " + (ROLE_LABELS[person.role] || person.role));
        option.value = person.staffId;
        return option;
      }));
      dialog.showModal();
    });
    form.addEventListener("submit", () => {
      const data = new FormData(form);
      const titles = String(data.get("stages")).split("\n").map((line) => line.trim()).filter(Boolean).slice(0, 6);
      state.nextId += 1;
      state.tasks.push({
        taskId: "t-" + state.nextId,
        sourceRef: "Менеджер",
        tableCode: String(data.get("tableCode")).trim(),
        title: String(data.get("title")).trim(),
        instruction: String(data.get("instruction")).trim(),
        priority: data.get("high") ? "HIGH" : "NORMAL",
        deadlineAt: null,
        assigneeStaffId: data.get("assignee"),
        status: "ASSIGNED",
        version: 1,
        deliveredAt: null,
        voicedAt: null,
        stages: titles.map((title, index) => ({
          code: "s" + (index + 1),
          title,
          evidenceRequired: Boolean(data.get("evidence")) && index === titles.length - 1,
          done: false,
          evidence: 0,
        })),
        events: [{ type: "ASSIGNED", actor: "Менеджер", at: Date.now(), detail: "" }],
      });
      form.reset();
      state.filter = "all";
      render();
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

  async function load() {
    try {
      const response = await fetch(DATA_URL, { cache: "no-cache" });
      if (!response.ok) throw new Error("Demo data error: " + response.status);
      normalize(await response.json());
      render();
    } catch (error) {
      document.getElementById("feedStatus").textContent = "Не удалось загрузить учебные данные. Обновите страницу.";
    }
  }

  applyTheme();
  setupDialogs();
  setupNewTask();
  load();

  // Expose the rules for checks.
  window.AstorStaff = { state, apply };
})();
