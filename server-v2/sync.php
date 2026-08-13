<?php
declare(strict_types=1);

/*
 * FamilyTasks — single-file, ciphertext-only sync server.
 * Requirements: PHP 8.1+, PDO SQLite. Put this file in public_html and open it.
 * Data is written one directory above this script by default; override with
 * MYCALENDAR_DATA_DIR when the hosting layout is different.
 */

const MAX_BLOB_BYTES = 5_242_880;
const PULL_LIMIT = 200;

header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
header("Content-Security-Policy: default-src 'self'; style-src 'unsafe-inline'");

function data_dir(): string {
    $custom = getenv('MYCALENDAR_DATA_DIR');
    if ($custom !== false && $custom !== '') return rtrim($custom, DIRECTORY_SEPARATOR);
    $parent = dirname(__DIR__);
    if (preg_match('/^(public_html|www|htdocs|httpdocs)$/i', basename($parent))) $parent = dirname($parent);
    return $parent . DIRECTORY_SEPARATOR . 'familytasks-private';
}

function db(): PDO {
    static $db;
    if ($db instanceof PDO) return $db;
    if (!extension_loaded('pdo_sqlite')) fail('pdo_sqlite is required', 500);
    $dir = data_dir();
    if (!is_dir($dir) && !mkdir($dir, 0700, true) && !is_dir($dir)) fail('Cannot create private data directory', 500);
    if (!is_writable($dir)) fail('Private data directory is not writable', 500);
    $db = new PDO('sqlite:' . $dir . DIRECTORY_SEPARATOR . 'familytasks.sqlite');
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $db->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
    $db->exec('PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000');
    $db->exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS accounts (
          id TEXT PRIMARY KEY, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS invites (
          code_hash TEXT PRIMARY KEY, account_id TEXT NULL, expires_at INTEGER NOT NULL,
          uses_left INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS devices (
          id TEXT PRIMARY KEY, account_id TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
          name TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL,
          last_seen INTEGER NOT NULL, FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS blobs (
          seq INTEGER PRIMARY KEY AUTOINCREMENT, account_id TEXT NOT NULL, blob_id TEXT NOT NULL,
          device_id TEXT NOT NULL, ciphertext TEXT NOT NULL, created_at INTEGER NOT NULL,
          UNIQUE(account_id, blob_id), FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS blobs_account_seq ON blobs(account_id, seq);");
    return $db;
}

function json_input(): array {
    $raw = file_get_contents('php://input');
    if ($raw === false || strlen($raw) > MAX_BLOB_BYTES * 2) fail('Request is too large', 413);
    $value = json_decode($raw, true);
    if (!is_array($value)) fail('JSON body required', 400);
    return $value;
}

function out(array $data, int $status = 200): never {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function fail(string $message, int $status = 400): never { out(['ok' => false, 'error' => $message], $status); }
function random_id(int $bytes = 16): string { return bin2hex(random_bytes($bytes)); }
function invite_hash(string $code): string { return hash('sha256', strtoupper(trim($code))); }
function b64url(int $bytes): string { return rtrim(strtr(base64_encode(random_bytes($bytes)), '+/', '-_'), '='); }
function require_post(): void { if ($_SERVER['REQUEST_METHOD'] !== 'POST') fail('POST required', 405); }

function meta(string $key): ?string {
    $s = db()->prepare('SELECT value FROM meta WHERE key=?');
    $s->execute([$key]);
    $v = $s->fetchColumn();
    return $v === false ? null : (string)$v;
}

function set_meta(string $key, string $value): void {
    $s = db()->prepare('INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value');
    $s->execute([$key, $value]);
}

function authenticated_device(): array {
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/^Bearer ([a-f0-9]{32})\.([A-Za-z0-9_-]{32,80})$/D', $header, $m)) fail('Unauthorized', 401);
    $s = db()->prepare('SELECT d.*, a.active account_active FROM devices d JOIN accounts a ON a.id=d.account_id WHERE d.id=?');
    $s->execute([$m[1]]);
    $device = $s->fetch();
    if (!$device || !$device['active'] || !$device['account_active'] || !hash_equals($device['token_hash'], hash('sha256', $m[2]))) {
        fail('Unauthorized', 401);
    }
    db()->prepare('UPDATE devices SET last_seen=? WHERE id=?')->execute([time(), $device['id']]);
    return $device;
}

function register_device(): never {
    require_post();
    $body = json_input();
    $code = (string)($body['invite'] ?? '');
    $name = trim((string)($body['deviceName'] ?? 'Android'));
    if ($code === '' || strlen($name) > 240) fail('Invalid invite or device name');
    $pdo = db();
    $pdo->beginTransaction();
    try {
        $s = $pdo->prepare('SELECT * FROM invites WHERE code_hash=?');
        $s->execute([invite_hash($code)]);
        $invite = $s->fetch();
        if (!$invite || (int)$invite['expires_at'] < time() || (int)$invite['uses_left'] < 1) fail('Invite is invalid or expired', 403);
        $account = $invite['account_id'] ?: random_id();
        if (!$invite['account_id']) {
            $pdo->prepare('INSERT INTO accounts(id,created_at) VALUES(?,?)')->execute([$account, time()]);
        }
        $device = random_id();
        $token = b64url(32);
        $pdo->prepare('INSERT INTO devices(id,account_id,token_hash,name,created_at,last_seen) VALUES(?,?,?,?,?,?)')
            ->execute([$device, $account, hash('sha256', $token), $name ?: 'Android', time(), time()]);
        $pdo->prepare('UPDATE invites SET uses_left=uses_left-1, account_id=? WHERE code_hash=?')
            ->execute([$account, invite_hash($code)]);
        $pdo->commit();
        out(['ok' => true, 'accountId' => $account, 'deviceId' => $device, 'token' => $token], 201);
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        if ($e instanceof RuntimeException) throw $e;
        fail('Registration failed', 500);
    }
}

function push_blob(): never {
    require_post();
    $device = authenticated_device();
    $body = json_input();
    $blobId = (string)($body['blobId'] ?? '');
    $ciphertext = (string)($body['ciphertext'] ?? '');
    if (!preg_match('/^[a-f0-9-]{16,80}$/D', $blobId)) fail('Invalid blobId');
    $decoded = base64_decode($ciphertext, true);
    if ($decoded === false || strlen($decoded) < 33 || strlen($decoded) > MAX_BLOB_BYTES) fail('Invalid ciphertext');
    $pdo = db();
    $s = $pdo->prepare('INSERT OR IGNORE INTO blobs(account_id,blob_id,device_id,ciphertext,created_at) VALUES(?,?,?,?,?)');
    $s->execute([$device['account_id'], $blobId, $device['id'], $ciphertext, time()]);
    $q = $pdo->prepare('SELECT seq FROM blobs WHERE account_id=? AND blob_id=?');
    $q->execute([$device['account_id'], $blobId]);
    $seq = (int)$q->fetchColumn();
    // Each package is a complete encrypted snapshot. Keeping the latest five per
    // device bounds storage without requiring the server to inspect plaintext.
    $pdo->prepare('DELETE FROM blobs WHERE account_id=? AND device_id=? AND seq NOT IN (SELECT seq FROM blobs WHERE account_id=? AND device_id=? ORDER BY seq DESC LIMIT 5)')
        ->execute([$device['account_id'], $device['id'], $device['account_id'], $device['id']]);
    out(['ok' => true, 'seq' => $seq, 'inserted' => $s->rowCount() === 1]);
}

function pull_blobs(): never {
    $device = authenticated_device();
    $after = max(0, (int)($_GET['after'] ?? 0));
    $s = db()->prepare('SELECT seq,blob_id,device_id,ciphertext,created_at FROM blobs WHERE account_id=? AND seq>? ORDER BY seq LIMIT ' . PULL_LIMIT);
    $s->execute([$device['account_id'], $after]);
    $rows = $s->fetchAll();
    $last = $after;
    foreach ($rows as &$row) {
        $row['seq'] = (int)$row['seq'];
        $row['created_at'] = (int)$row['created_at'];
        $last = max($last, $row['seq']);
    }
    out(['ok' => true, 'items' => $rows, 'lastSeq' => $last, 'hasMore' => count($rows) === PULL_LIMIT]);
}

function start_session(): void {
    session_name('familytasks_admin');
    session_set_cookie_params(['httponly' => true, 'secure' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off'), 'samesite' => 'Strict']);
    session_start();
}

function csrf(): string {
    if (empty($_SESSION['csrf'])) $_SESSION['csrf'] = b64url(24);
    return $_SESSION['csrf'];
}

function admin_page(): never {
    start_session();
    $pdo = db();
    $configured = meta('admin_hash') !== null;
    $message = '';
    $newInvite = '';

    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $op = (string)($_POST['op'] ?? '');
        if (!$configured && $op === 'setup') {
            $password = (string)($_POST['password'] ?? '');
            if (strlen($password) < 12) $message = 'Пароль должен быть не короче 12 символов.';
            else {
                set_meta('admin_hash', password_hash($password, defined('PASSWORD_ARGON2ID') ? PASSWORD_ARGON2ID : PASSWORD_DEFAULT));
                $_SESSION['admin'] = true;
                $configured = true;
                $message = 'Сервер настроен.';
            }
        } elseif ($op === 'login') {
            if (password_verify((string)($_POST['password'] ?? ''), meta('admin_hash') ?? '')) $_SESSION['admin'] = true;
            else $message = 'Неверный пароль.';
        } elseif (!empty($_SESSION['admin'])) {
            if (!hash_equals(csrf(), (string)($_POST['csrf'] ?? ''))) { http_response_code(403); exit('CSRF'); }
            if ($op === 'invite') {
                $newInvite = strtoupper(substr(b64url(18), 0, 24));
                $account = preg_match('/^[a-f0-9]{32}$/D', (string)($_POST['account'] ?? '')) ? (string)$_POST['account'] : null;
                $pdo->prepare('INSERT INTO invites(code_hash,account_id,expires_at,uses_left,created_at) VALUES(?,?,?,?,?)')
                    ->execute([invite_hash($newInvite), $account, time() + 86400, 1, time()]);
            } elseif ($op === 'revoke') {
                $pdo->prepare('UPDATE devices SET active=0 WHERE id=?')->execute([(string)$_POST['id']]);
            } elseif ($op === 'forget') {
                $id = (string)($_POST['id'] ?? '');
                $pdo->prepare('DELETE FROM invites WHERE account_id=?')->execute([$id]);
                $pdo->prepare('DELETE FROM accounts WHERE id=?')->execute([$id]);
                $message = 'Аккаунт и все его ciphertext-пакеты удалены.';
            } elseif ($op === 'logout') {
                session_destroy();
                header('Location: ?action=admin'); exit;
            }
        }
    }

    header('Content-Type: text/html; charset=utf-8');
    header('Cache-Control: no-store');
    $h = static fn(string $v): string => htmlspecialchars($v, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
    echo '<!doctype html><meta name="viewport" content="width=device-width"><title>FamilyTasks</title>';
    echo '<style>body{font:16px system-ui;max-width:900px;margin:40px auto;padding:0 16px;color:#25252b}form{margin:12px 0}input,button,select{font:inherit;padding:9px;margin:3px}table{border-collapse:collapse;width:100%}td,th{padding:8px;border-bottom:1px solid #ddd;text-align:left}.ok{padding:12px;background:#edf8ed}.code{font:700 20px monospace;background:#fff4c8;padding:14px}</style>';
    echo '<h1>FamilyTasks — сервер синхронизации</h1>';
    if ($message) echo '<p class="ok">'.$h($message).'</p>';
    if (!$configured) {
        echo '<p>Первичная настройка. База будет создана в <code>'.$h(data_dir()).'</code>.</p><form method=post><input type=hidden name=op value=setup><input type=password name=password minlength=12 required placeholder="Пароль администратора"><button>Настроить</button></form>'; exit;
    }
    if (empty($_SESSION['admin'])) {
        echo '<form method=post><input type=hidden name=op value=login><input type=password name=password required placeholder="Пароль"><button>Войти</button></form>'; exit;
    }
    if ($newInvite) echo '<p>Одноразовый код (показывается один раз):</p><p class=code>'.$h($newInvite).'</p>';
    $accounts = $pdo->query('SELECT a.id,a.created_at,COUNT(DISTINCT d.id) devices,COUNT(DISTINCT b.seq) blobs FROM accounts a LEFT JOIN devices d ON d.account_id=a.id LEFT JOIN blobs b ON b.account_id=a.id GROUP BY a.id ORDER BY a.created_at DESC')->fetchAll();
    echo '<h2>Новое подключение</h2><form method=post><input type=hidden name=csrf value="'.$h(csrf()).'"><input type=hidden name=op value=invite><select name=account><option value="">Новая семья</option>';
    foreach ($accounts as $a) echo '<option value="'.$h($a['id']).'">Добавить устройство к семье '.$h($a['id']).'</option>';
    echo '</select><button>Создать код на 24 часа</button></form><h2>Аккаунты</h2><table><tr><th>ID</th><th>Устройств</th><th>Пакетов</th><th></th></tr>';
    foreach ($accounts as $a) echo '<tr><td><code>'.$h($a['id']).'</code></td><td>'.$h((string)$a['devices']).'</td><td>'.$h((string)$a['blobs']).'</td><td><form method=post onsubmit="return confirm(\'Удалить аккаунт и все пакеты?\')"><input type=hidden name=csrf value="'.$h(csrf()).'"><input type=hidden name=op value=forget><input type=hidden name=id value="'.$h($a['id']).'"><button>Забыть</button></form></td></tr>';
    echo '</table><h2>Устройства</h2><table><tr><th>Имя</th><th>Аккаунт</th><th>Активно</th><th></th></tr>';
    foreach ($pdo->query('SELECT * FROM devices ORDER BY created_at DESC') as $d) echo '<tr><td>'.$h($d['name']).'</td><td><code>'.$h($d['account_id']).'</code></td><td>'.($d['active']?'да':'нет').'</td><td>'.($d['active']?'<form method=post><input type=hidden name=csrf value="'.$h(csrf()).'"><input type=hidden name=op value=revoke><input type=hidden name=id value="'.$h($d['id']).'"><button>Отозвать</button></form>':'').'</td></tr>';
    echo '</table><form method=post><input type=hidden name=csrf value="'.$h(csrf()).'"><input type=hidden name=op value=logout><button>Выйти</button></form>';
    exit;
}

$action = (string)($_GET['action'] ?? 'admin');
try {
    switch ($action) {
        case 'health': out(['ok' => true, 'service' => 'familytasks', 'version' => 1, 'configured' => meta('admin_hash') !== null]);
        case 'register': register_device();
        case 'push': push_blob();
        case 'pull': pull_blobs();
        case 'admin': admin_page();
        default: fail('Unknown action', 404);
    }
} catch (Throwable $e) {
    error_log('familytasks: ' . $e->getMessage());
    fail('Server error', 500);
}
