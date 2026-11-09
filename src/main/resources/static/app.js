// Purse front end. The wallet token lives only in this variable, never in browser storage.

const $ = (id) => document.getElementById(id);
const naira = new Intl.NumberFormat('en-NG', { style: 'currency', currency: 'NGN' });
const fmt = (kobo) => naira.format(kobo / 100);
const toKobo = (value) => Math.round(Number(value) * 100);

let session = null; // { id, token }

async function api(path, { method = 'GET', body, headers = {} } = {}) {
  const res = await fetch(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(session ? { 'X-Wallet-Token': session.token } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.detail || `Request failed (${res.status})`);
  return data;
}

async function loadInfo() {
  try {
    const info = await api('/api/info');
    $('status').className = 'status ok';
    $('status-text').textContent = 'Online';
    $('version').textContent = `version ${info.version}`;
    $('env').textContent = `${info.environment} · ${info.database}`;
  } catch {
    $('status').className = 'status down';
    $('status-text').textContent = 'Offline';
  }
}

function showSignedIn(on) {
  $('signed-out').hidden = on;
  $('signed-in').hidden = !on;
}

async function refresh() {
  const s = await api(`/api/wallets/${session.id}/transactions?limit=30`);
  $('w-owner').textContent = s.wallet.name;
  $('w-balance').textContent = fmt(s.wallet.balance);
  $('w-id').textContent = s.wallet.id;

  const list = $('lines');
  list.replaceChildren();
  for (const line of s.lines) {
    const li = document.createElement('li');
    li.className = 'line';

    const left = document.createElement('div');
    const title = document.createElement('p');
    title.className = 'l-title';
    title.textContent = line.kind === 'DEPOSIT'
      ? 'Money added'
      : (line.amount < 0 ? `Sent to ${line.counterparty}` : `Received from ${line.counterparty}`);
    const meta = document.createElement('p');
    meta.className = 'muted small';
    meta.textContent = `${new Date(line.createdAt).toLocaleString()} · ${line.reference}` + (line.note ? ` · ${line.note}` : '');
    left.append(title, meta);

    const right = document.createElement('div');
    right.className = 'l-right';
    const amt = document.createElement('p');
    amt.className = line.amount < 0 ? 'amt out' : 'amt in';
    amt.textContent = (line.amount < 0 ? '−' : '+') + fmt(Math.abs(line.amount));
    const bal = document.createElement('p');
    bal.className = 'muted small';
    bal.textContent = `Balance ${fmt(line.balanceAfter)}`;
    right.append(amt, bal);

    li.append(left, right);
    list.append(li);
  }
  $('lines-empty').hidden = s.lines.length > 0;
}

async function signIn(id, token) {
  session = { id: id.trim().toUpperCase(), token: token.trim() };
  try {
    await refresh();
    showSignedIn(true);
  } catch (err) {
    session = null;
    throw err;
  }
}

$('open-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('open-error').textContent = '';
  try {
    const w = await api('/api/wallets', { method: 'POST', body: { name: $('o-name').value, email: $('o-email').value } });
    $('nw-id').textContent = w.id;
    $('nw-token').textContent = w.token;
    $('open-form').hidden = true;
    $('new-wallet').hidden = false;
    $('nw-continue').onclick = async () => {
      await signIn(w.id, w.token);
      $('open-form').reset();
      $('open-form').hidden = false;
      $('new-wallet').hidden = true;
    };
  } catch (err) {
    $('open-error').textContent = err.message;
  }
});

$('signin-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('signin-error').textContent = '';
  try {
    await signIn($('s-id').value, $('s-token').value);
    $('signin-form').reset();
  } catch (err) {
    $('signin-error').textContent = err.message;
  }
});

$('signout').addEventListener('click', () => {
  session = null;
  showSignedIn(false);
});

$('deposit-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('deposit-error').textContent = '';
  try {
    await api(`/api/wallets/${session.id}/deposits`, {
      method: 'POST',
      body: { amount: toKobo($('d-amount').value) },
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    });
    $('deposit-form').reset();
    await refresh();
  } catch (err) {
    $('deposit-error').textContent = err.message;
  }
});

// One Idempotency-Key per send attempt: if the network drops and the user presses Send again,
// the server applies the transfer only once.
let pendingKey = null;

$('send-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('send-error').textContent = '';
  $('send-ok').textContent = '';
  const btn = $('send-btn');
  btn.disabled = true;
  pendingKey = pendingKey || crypto.randomUUID();
  try {
    const tx = await api(`/api/wallets/${session.id}/transfers`, {
      method: 'POST',
      body: { toWallet: $('t-to').value, amount: toKobo($('t-amount').value), note: $('t-note').value || null },
      headers: { 'Idempotency-Key': pendingKey },
    });
    pendingKey = null;
    $('send-ok').textContent = `Sent ${fmt(tx.amount)} to ${tx.toWallet}. Reference ${tx.reference}.`;
    $('send-form').reset();
    await refresh();
  } catch (err) {
    // Keep the same key only if the request may have reached the server (network error).
    if (!(err instanceof TypeError)) pendingKey = null;
    $('send-error').textContent = err.message;
  } finally {
    btn.disabled = false;
  }
});

loadInfo();
