// Browser client for the chat server. Plain JavaScript, no build step.
//
// State lives in `state`; the DOM is re-rendered from it. Messages are kept per channel in a Map
// keyed by message id, so live frames and history pages can arrive in any order and still render
// once each, sorted by id (the server's single ordering, even across instances).

const STORAGE = { token: "chat.token", user: "chat.user", rooms: "chat.rooms", current: "chat.current" };
const PING_INTERVAL_MS = 25_000;
const TYPING_SEND_INTERVAL_MS = 2_000;
const TYPING_SHOW_MS = 4_000;
const HISTORY_PAGE = 50;
const CLOSE_INVALID_TOKEN = 4401;
const CLOSE_TOO_MANY_CONNECTIONS = 4409;

const state = {
    token: null,
    user: null,
    ws: null,
    pingTimer: null,
    reconnectDelay: 1000,
    signingOut: false,
    online: new Set(),
    rooms: new Map(),          // id -> room from GET /api/rooms
    joined: new Set(),         // rooms this connection has joined
    members: new Map(),        // room id -> Set of usernames
    channels: new Map(),       // "room:<id>" | "dm:<user>" -> channel state
    current: null,             // key of the channel on screen
    lastTypingSent: new Map(), // channel key -> time of the last typing signal sent there
    pendingRefs: new Map(),    // ref -> channel key, for error reporting
    refCounter: 0,
};

const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------- storage

function load(key, fallback) {
    try {
        const value = localStorage.getItem(key);
        return value === null ? fallback : JSON.parse(value);
    } catch {
        return fallback;
    }
}

function save(key, value) {
    try {
        if (value === null || value === undefined) {
            localStorage.removeItem(key);
        } else {
            localStorage.setItem(key, JSON.stringify(value));
        }
    } catch {
        // Storage can be unavailable (private mode); the app still works for this tab.
    }
}

// ---------------------------------------------------------------- REST

async function api(path, options = {}) {
    const headers = { Accept: "application/json", ...(options.headers || {}) };
    if (state.token) headers.Authorization = `Bearer ${state.token}`;
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(path, {
        method: options.method || "GET",
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
    if (response.status === 401 && path !== "/api/sessions") {
        signOut("Your session has expired, please sign in again.");
        throw new Error("Session expired");
    }
    const text = await response.text();
    const data = text ? JSON.parse(text) : null;
    if (!response.ok) {
        const error = new Error((data && data.detail) || `HTTP ${response.status}`);
        error.code = data && data.code;
        throw error;
    }
    return data;
}

// ---------------------------------------------------------------- channels

function roomKey(id) { return `room:${id}`; }
function dmKey(user) { return `dm:${user}`; }

function channel(key) {
    let ch = state.channels.get(key);
    if (!ch) {
        ch = { key, messages: new Map(), notices: [], unread: 0, historyLoaded: false, hasOlder: false, typing: new Map() };
        state.channels.set(key, ch);
    }
    return ch;
}

function addMessage(key, message) {
    const ch = channel(key);
    const isNew = !ch.messages.has(message.id);
    ch.messages.set(message.id, message);
    if (isNew && key !== state.current && message.from !== state.user) {
        ch.unread += 1;
    }
    // A new message from someone ends their typing indicator.
    const timer = ch.typing.get(message.from);
    if (timer) {
        clearTimeout(timer);
        ch.typing.delete(message.from);
    }
    return isNew;
}

async function loadHistory(key, older = false) {
    const ch = channel(key);
    const ids = [...ch.messages.keys()];
    const before = older && ids.length ? Math.min(...ids) : null;
    const query = `?limit=${HISTORY_PAGE}` + (before ? `&before=${before}` : "");
    const [kind, name] = splitKey(key);
    const path = kind === "room"
        ? `/api/rooms/${encodeURIComponent(name)}/messages${query}`
        : `/api/direct/${encodeURIComponent(name)}/messages${query}`;
    const page = await api(path);
    for (const message of page) {
        channel(key).messages.set(message.id, message);
    }
    ch.historyLoaded = true;
    if (!older || page.length) ch.hasOlder = page.length === HISTORY_PAGE;
    if (key === state.current) renderMessages({ keepScroll: older });
}

function splitKey(key) {
    const index = key.indexOf(":");
    return [key.slice(0, index), key.slice(index + 1)];
}

// ---------------------------------------------------------------- WebSocket

function connect() {
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${scheme}://${location.host}/ws`);
    state.ws = ws;
    setStatus(false, "Connecting...");

    ws.addEventListener("open", () => {
        ws.send(JSON.stringify({ type: "auth", token: state.token }));
    });

    ws.addEventListener("message", (event) => {
        let frame;
        try {
            frame = JSON.parse(event.data);
        } catch {
            return;
        }
        handleFrame(frame);
    });

    ws.addEventListener("close", (event) => {
        clearInterval(state.pingTimer);
        if (state.ws !== ws) return;
        state.ws = null;
        state.joined.clear();
        setStatus(false, "Disconnected");
        if (state.signingOut) return;
        if (event.code === CLOSE_INVALID_TOKEN) {
            signOut("Your session has expired, please sign in again.");
            return;
        }
        if (event.code === CLOSE_TOO_MANY_CONNECTIONS) {
            showBanner("You have too many tabs open with this username. Close one and reload.");
            return;
        }
        const delay = state.reconnectDelay;
        state.reconnectDelay = Math.min(delay * 2, 30_000);
        showBanner(`Connection lost. Reconnecting in ${Math.round(delay / 1000)}s...`);
        setTimeout(() => { if (!state.ws && state.token) connect(); }, delay);
        renderSidebar();
    });
}

function send(frame) {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
        state.ws.send(JSON.stringify(frame));
        return true;
    }
    return false;
}

function handleFrame(frame) {
    switch (frame.type) {
        case "welcome": return onWelcome(frame);
        case "joined": return onJoined(frame);
        case "left": return onLeft(frame);
        case "message": {
            const key = roomKey(frame.room);
            addMessage(key, frame);
            return key === state.current ? renderMessages() : renderSidebar();
        }
        case "direct": {
            const other = frame.from === state.user ? frame.to : frame.from;
            const key = dmKey(other);
            const ch = channel(key);
            addMessage(key, frame);
            if (!ch.historyLoaded) loadHistory(key).catch(() => {});
            return key === state.current ? renderMessages() : renderSidebar();
        }
        case "presence":
            if (frame.online) state.online.add(frame.user); else state.online.delete(frame.user);
            if (!frame.online) state.members.forEach((set) => set.delete(frame.user));
            renderSidebar();
            return renderHeader();
        case "membership": {
            const set = state.members.get(frame.room) || new Set();
            if (frame.joined) set.add(frame.user); else set.delete(frame.user);
            state.members.set(frame.room, set);
            if (frame.user !== state.user) {
                addNotice(roomKey(frame.room), `${frame.user} ${frame.joined ? "joined" : "left"}`);
            }
            return renderHeader();
        }
        case "typing": return onTyping(frame);
        case "room_created":
            if (!state.rooms.has(frame.id)) {
                state.rooms.set(frame.id, { ...frame, online: 0 });
                renderSidebar();
            }
            return;
        case "error": return onError(frame);
        case "ack":
            state.pendingRefs.delete(frame.ref);
            return;
        default:
            return; // pong, or a frame type from a newer server
    }
}

async function onWelcome(frame) {
    state.reconnectDelay = 1000;
    state.online = new Set(frame.online);
    hideBanner();
    setStatus(true, `Connected to ${frame.instance}`);
    $("instance").textContent = `Server instance ${frame.instance}`;
    clearInterval(state.pingTimer);
    state.pingTimer = setInterval(() => send({ type: "ping" }), PING_INTERVAL_MS);

    try {
        const [rooms, conversations] = await Promise.all([api("/api/rooms"), api("/api/direct")]);
        state.rooms = new Map(rooms.map((room) => [room.id, room]));
        for (const conversation of conversations) {
            channel(dmKey(conversation.with)).messages.set(conversation.last.id, conversation.last);
        }
    } catch (error) {
        showBanner(error.message);
    }

    // Rejoin what this browser had open, dropping rooms that no longer exist.
    const wanted = load(STORAGE.rooms, ["general"]).filter((id) => state.rooms.has(id));
    for (const id of wanted) send({ type: "join", room: id });

    // After a reconnect, fetch anything missed while offline (the Map de-duplicates).
    for (const key of state.channels.keys()) {
        if (channel(key).historyLoaded) loadHistory(key).catch(() => {});
    }

    const saved = state.current || load(STORAGE.current, null);
    if (saved && (saved.startsWith("dm:") || wanted.includes(splitKey(saved)[1]))) {
        select(saved);
    } else if (wanted.length) {
        select(roomKey(wanted[0]));
    } else {
        renderAll();
    }
}

function onJoined(frame) {
    state.joined.add(frame.room);
    state.members.set(frame.room, new Set(frame.members));
    save(STORAGE.rooms, [...new Set([...load(STORAGE.rooms, []), frame.room])]);
    const key = roomKey(frame.room);
    if (!channel(key).historyLoaded) loadHistory(key).catch((e) => showBanner(e.message));
    renderAll();
}

function onLeft(frame) {
    state.joined.delete(frame.room);
    state.members.delete(frame.room);
    save(STORAGE.rooms, load(STORAGE.rooms, []).filter((id) => id !== frame.room));
    if (state.current === roomKey(frame.room)) {
        state.current = null;
        save(STORAGE.current, null);
    }
    renderAll();
}

function onTyping(frame) {
    const key = frame.room ? roomKey(frame.room) : dmKey(frame.from);
    const ch = channel(key);
    clearTimeout(ch.typing.get(frame.from));
    ch.typing.set(frame.from, setTimeout(() => {
        ch.typing.delete(frame.from);
        renderTyping();
    }, TYPING_SHOW_MS));
    renderTyping();
}

function onError(frame) {
    const key = (frame.ref && state.pendingRefs.get(frame.ref)) || state.current;
    if (frame.ref) state.pendingRefs.delete(frame.ref);
    if (key) {
        addNotice(key, frame.message, true);
    } else {
        showBanner(frame.message);
    }
}

function addNotice(key, text, isError = false) {
    const ch = channel(key);
    // Notices sort after the newest message they follow.
    const ids = [...ch.messages.keys()];
    ch.notices.push({ after: ids.length ? Math.max(...ids) : 0, text, isError });
    if (ch.notices.length > 50) ch.notices.shift();
    if (key === state.current) renderMessages();
}

// ---------------------------------------------------------------- actions

async function signIn(username) {
    const session = await api("/api/sessions", { method: "POST", body: { username } });
    state.token = session.token;
    state.user = session.username;
    save(STORAGE.token, session.token);
    save(STORAGE.user, session.username);
    showApp();
    connect();
}

function signOut(message) {
    state.signingOut = true;
    if (state.ws) state.ws.close(1000, "sign out");
    state.ws = null;
    clearInterval(state.pingTimer);
    state.token = null;
    state.user = null;
    state.channels.clear();
    state.joined.clear();
    state.current = null;
    save(STORAGE.token, null);
    save(STORAGE.user, null);
    save(STORAGE.current, null);
    $("login-error").textContent = message || "";
    $("app").hidden = true;
    $("login").hidden = false;
    $("username").focus();
    state.signingOut = false;
}

function select(key) {
    state.current = key;
    save(STORAGE.current, key);
    const ch = channel(key);
    ch.unread = 0;
    const [kind, name] = splitKey(key);
    if (kind === "room" && !state.joined.has(name)) {
        send({ type: "join", room: name });
    } else if (!ch.historyLoaded) {
        loadHistory(key).catch((e) => showBanner(e.message));
    }
    renderAll();
    $("message-input").focus();
}

function sendCurrent() {
    const input = $("message-input");
    const content = input.value.trim();
    if (!content || !state.current) return;
    const [kind, name] = splitKey(state.current);
    const ref = `b${++state.refCounter}`;
    const frame = kind === "room"
        ? { type: "message", room: name, content, ref }
        : { type: "direct", to: name, content, ref };
    if (!send(frame)) {
        addNotice(state.current, "Not connected: your message was not sent.", true);
        return;
    }
    state.pendingRefs.set(ref, state.current);
    input.value = "";
    autosize(input);
}

function signalTyping() {
    const now = Date.now();
    if (!state.current || now - (state.lastTypingSent.get(state.current) || 0) < TYPING_SEND_INTERVAL_MS) return;
    state.lastTypingSent.set(state.current, now);
    const [kind, name] = splitKey(state.current);
    send(kind === "room" ? { type: "typing", room: name } : { type: "typing", to: name });
}

// ---------------------------------------------------------------- rendering

function renderAll() {
    renderSidebar();
    renderHeader();
    renderMessages();
    renderTyping();
}

function renderSidebar() {
    $("me-name").textContent = state.user || "";

    const roomList = $("room-list");
    roomList.replaceChildren(...[...state.rooms.values()].map((room) => {
        const key = roomKey(room.id);
        const item = listItem(`# ${room.id}`, channel(key).unread, key === state.current);
        item.title = room.description ? `${room.name}: ${room.description}` : room.name;
        if (!state.joined.has(room.id)) item.classList.add("not-joined");
        item.addEventListener("click", () => select(key));
        return item;
    }));

    const dmKeys = [...state.channels.keys()].filter((key) => key.startsWith("dm:"));
    $("dm-list").replaceChildren(...dmKeys.map((key) => {
        const user = splitKey(key)[1];
        const item = listItem(`@ ${user}`, channel(key).unread, key === state.current);
        if (!state.online.has(user)) item.classList.add("not-joined");
        item.addEventListener("click", () => select(key));
        return item;
    }));

    const users = [...state.online].sort();
    $("online-count").textContent = String(users.length);
    $("online-list").replaceChildren(...users.map((user) => {
        const item = document.createElement("li");
        item.textContent = user === state.user ? `${user} (you)` : user;
        if (user === state.user) {
            item.classList.add("self");
        } else {
            item.title = `Message ${user}`;
            item.addEventListener("click", () => select(dmKey(user)));
        }
        return item;
    }));
}

function listItem(label, unread, active) {
    const item = document.createElement("li");
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = label;
    item.append(name);
    if (unread) {
        const badge = document.createElement("span");
        badge.className = "badge";
        badge.textContent = unread > 99 ? "99+" : String(unread);
        item.append(badge);
    }
    if (active) item.classList.add("active");
    return item;
}

function renderHeader() {
    const title = $("channel-title");
    const subtitle = $("channel-subtitle");
    const leave = $("leave-room");
    const input = $("message-input");
    if (!state.current) {
        title.textContent = "Choose a room";
        subtitle.textContent = "Pick a room on the left, or click someone who is online to message them.";
        leave.hidden = true;
        input.disabled = true;
        $("send").disabled = true;
        return;
    }
    const [kind, name] = splitKey(state.current);
    if (kind === "room") {
        const room = state.rooms.get(name);
        const members = [...(state.members.get(name) || [])].sort();
        title.textContent = `# ${name}`;
        subtitle.textContent = `${room && room.description ? room.description + " · " : ""}`
            + `${members.length} here${members.length ? ": " + members.join(", ") : ""}`;
        leave.hidden = !state.joined.has(name);
        input.placeholder = `Message #${name}`;
    } else {
        title.textContent = `@ ${name}`;
        subtitle.textContent = state.online.has(name) ? "Online" : "Offline: they will see history when they return, but you can only send while they are online.";
        leave.hidden = true;
        input.placeholder = `Message @${name}`;
    }
    const connected = !!state.ws && state.ws.readyState === WebSocket.OPEN;
    input.disabled = !connected;
    $("send").disabled = !connected;
}

function renderMessages({ keepScroll = false } = {}) {
    const container = $("messages");
    const list = $("message-list");
    const older = $("load-older");
    if (!state.current) {
        list.replaceChildren();
        older.hidden = true;
        return;
    }
    const ch = channel(state.current);
    const nearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
    const previousHeight = container.scrollHeight;

    const messages = [...ch.messages.values()].sort((a, b) => a.id - b.id);
    const nodes = [];
    let noticeIndex = 0;
    const notices = [...ch.notices].sort((a, b) => a.after - b.after);
    for (const message of messages) {
        while (noticeIndex < notices.length && notices[noticeIndex].after < message.id) {
            nodes.push(noticeNode(notices[noticeIndex++]));
        }
        nodes.push(messageNode(message));
    }
    while (noticeIndex < notices.length) nodes.push(noticeNode(notices[noticeIndex++]));
    list.replaceChildren(...nodes);
    older.hidden = !ch.hasOlder;

    if (keepScroll) {
        container.scrollTop = container.scrollHeight - previousHeight;
    } else if (nearBottom || messages.length <= HISTORY_PAGE) {
        container.scrollTop = container.scrollHeight;
    }
    ch.unread = 0;
    renderSidebar();
}

const timeFormat = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" });
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

function messageNode(message) {
    const item = document.createElement("li");
    item.className = "message" + (message.from === state.user ? " own" : "");
    const meta = document.createElement("div");
    meta.className = "meta";
    const author = document.createElement("span");
    author.className = "author";
    author.textContent = message.from;
    const time = document.createElement("time");
    const sent = new Date(message.sentAt);
    time.dateTime = message.sentAt;
    time.textContent = timeFormat.format(sent);
    time.title = dateFormat.format(sent);
    meta.append(author, time);
    const content = document.createElement("div");
    content.className = "content";
    content.textContent = message.content; // never innerHTML: message text is untrusted
    item.append(meta, content);
    return item;
}

function noticeNode(notice) {
    const item = document.createElement("li");
    item.className = "notice" + (notice.isError ? " error" : "");
    item.textContent = notice.text;
    return item;
}

function renderTyping() {
    const typing = state.current ? [...channel(state.current).typing.keys()] : [];
    $("typing").textContent = typing.length === 0 ? ""
        : typing.length === 1 ? `${typing[0]} is typing...`
            : `${typing.slice(0, 3).join(", ")} are typing...`;
}

function setStatus(connected, label) {
    const dot = $("status-dot");
    dot.classList.toggle("online", connected);
    dot.title = label;
    renderHeader();
}

function showBanner(text) {
    const banner = $("banner");
    banner.textContent = text;
    banner.hidden = false;
}

function hideBanner() {
    $("banner").hidden = true;
}

function showApp() {
    $("login").hidden = true;
    $("app").hidden = false;
    renderAll();
}

function autosize(textarea) {
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 160)}px`;
}

// ---------------------------------------------------------------- wiring

function wire() {
    $("login-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const error = $("login-error");
        error.textContent = "";
        try {
            await signIn($("username").value);
        } catch (e) {
            error.textContent = e.message;
        }
    });

    $("sign-out").addEventListener("click", () => signOut());

    $("composer").addEventListener("submit", (event) => {
        event.preventDefault();
        sendCurrent();
    });

    const input = $("message-input");
    input.addEventListener("keydown", (event) => {
        if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
            event.preventDefault();
            sendCurrent();
        }
    });
    input.addEventListener("input", () => {
        autosize(input);
        if (input.value.trim()) signalTyping();
    });

    $("leave-room").addEventListener("click", () => {
        if (state.current && state.current.startsWith("room:")) {
            send({ type: "leave", room: splitKey(state.current)[1] });
        }
    });

    $("load-older").addEventListener("click", () => {
        if (state.current) loadHistory(state.current, true).catch((e) => showBanner(e.message));
    });

    const dialog = $("room-dialog");
    $("new-room").addEventListener("click", () => {
        $("room-error").textContent = "";
        $("room-form").reset();
        dialog.showModal();
    });
    $("room-cancel").addEventListener("click", () => dialog.close());
    $("room-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        try {
            const room = await api("/api/rooms", {
                method: "POST",
                body: { name: $("room-name").value, description: $("room-description").value },
            });
            state.rooms.set(room.id, room);
            dialog.close();
            select(roomKey(room.id));
        } catch (e) {
            $("room-error").textContent = e.message;
        }
    });
}

async function start() {
    wire();
    state.token = load(STORAGE.token, null);
    state.user = load(STORAGE.user, null);
    if (!state.token) {
        $("login").hidden = false;
        return;
    }
    try {
        await api("/api/sessions/me");
        showApp();
        connect();
    } catch {
        if (state.token) signOut();
    }
}

start();
