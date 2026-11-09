// Purse admin page. The admin key lives only in this variable, never in browser storage.

const $ = (id) => document.getElementById(id);
const naira = new Intl.NumberFormat('en-NG', { style: 'currency', currency: 'NGN' });
const fmt = (kobo) => naira.format(kobo / 100);
let adminKey = '';

async function adminApi(path) {
  const res = await fetch(path, { headers: { 'X-Admin-Key': adminKey } });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.detail || `Request failed (${res.status})`);
  return body;
}

function cell(text, className) {
  const td = document.createElement('td');
  td.textContent = text ?? '–';
  if (className) td.className = className;
  return td;
}

async function load() {
  const [summary, wallets, txs] = await Promise.all([
    adminApi('/api/admin/summary'),
    adminApi('/api/admin/wallets?limit=50'),
    adminApi('/api/admin/transactions?limit=50'),
  ]);

  $('s-wallets').textContent = summary.wallets;
  $('s-tx').textContent = summary.transactions;
  $('s-balance').textContent = fmt(summary.totalBalance);
  $('s-deposits').textContent = fmt(summary.totalDeposits);
  const books = $('books');
  books.className = summary.booksBalance ? 'books ok' : 'books bad';
  books.textContent = summary.booksBalance
    ? '✓ The books balance: money held equals money deposited, and the ledger agrees.'
    : '✗ The books do NOT balance. Money was created or lost. Investigate now.';

  const wRows = $('wallet-rows');
  wRows.replaceChildren();
  for (const w of wallets) {
    const tr = document.createElement('tr');
    tr.append(cell(w.id, 'mono'), cell(w.name), cell(w.email), cell(fmt(w.balance), 'num'),
      cell(new Date(w.createdAt).toLocaleString()));
    wRows.append(tr);
  }

  const tRows = $('tx-rows');
  tRows.replaceChildren();
  for (const t of txs) {
    const tr = document.createElement('tr');
    tr.append(cell(t.reference, 'mono'), cell(t.kind === 'DEPOSIT' ? 'Deposit' : 'Transfer'),
      cell(t.fromWallet || 'Top-up', 'mono'), cell(t.toWallet, 'mono'), cell(fmt(t.amount), 'num'),
      cell(t.note), cell(new Date(t.createdAt).toLocaleString()));
    tRows.append(tr);
  }
}

$('lock-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  adminKey = $('key').value;
  $('lock-error').textContent = '';
  try {
    await adminApi('/api/admin/check');
    $('lock-card').hidden = true;
    $('admin').hidden = false;
    await load();
  } catch (err) {
    adminKey = '';
    $('lock-error').textContent = err.message;
  }
});

$('refresh').addEventListener('click', () => {
  load().catch((err) => alert(err.message));
});
