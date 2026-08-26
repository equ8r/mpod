const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content || '';
const messageEl = document.getElementById('auth-message');

function showMessage(msg, ok) {
    if (!messageEl) return;
    messageEl.textContent = msg;
    messageEl.className = ok ? 'auth-message success' : 'auth-message error';
}

function apiPost(url, body) {
    return fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-Token': csrfToken,
        },
        credentials: 'same-origin',
        body: JSON.stringify(body),
    }).then(r => r.json());
}

const loginForm = document.getElementById('login-form');
if (loginForm) {
    loginForm.addEventListener('submit', e => {
        e.preventDefault();
        const form = new FormData(loginForm);
        apiPost('api/users.php?action=login', {
            email: form.get('email'),
            password: form.get('password'),
            remember: form.get('remember') === 'on',
        }).then(data => {
            if (data.ok) {
                window.location.href = 'index.php';
            } else {
                showMessage(data.error || 'Login failed', false);
            }
        });
    });
}

const registerForm = document.getElementById('register-form');
if (registerForm) {
    registerForm.addEventListener('submit', e => {
        e.preventDefault();
        const form = new FormData(registerForm);
        const password = form.get('password');
        const confirm = form.get('confirm');
        if (password !== confirm) {
            showMessage('Passwords do not match', false);
            return;
        }
        apiPost('api/users.php?action=register', {
            username: form.get('username'),
            email: form.get('email'),
            password,
        }).then(data => {
            if (data.ok) {
                showMessage('Account created. Check your email to verify.', true);
            } else {
                showMessage(data.error || 'Registration failed', false);
            }
        });
    });
}

const resetRequestForm = document.getElementById('reset-request-form');
if (resetRequestForm) {
    resetRequestForm.addEventListener('submit', e => {
        e.preventDefault();
        const form = new FormData(resetRequestForm);
        apiPost('api/users.php?action=reset_request', {
            email: form.get('email'),
        }).then(data => {
            if (data.ok) {
                showMessage('If that email exists, a reset link was sent.', true);
            } else {
                showMessage(data.error || 'Reset request failed', false);
            }
        });
    });
}

const resetForm = document.getElementById('reset-form');
if (resetForm) {
    resetForm.addEventListener('submit', e => {
        e.preventDefault();
        const form = new FormData(resetForm);
        const password = form.get('password');
        const confirm = form.get('confirm');
        if (password !== confirm) {
            showMessage('Passwords do not match', false);
            return;
        }
        const token = resetForm.dataset.token;
        apiPost('api/users.php?action=reset', {
            token,
            password,
        }).then(data => {
            if (data.ok) {
                showMessage('Password reset. You can now log in.', true);
            } else {
                showMessage(data.error || 'Reset failed', false);
            }
        });
    });
}
