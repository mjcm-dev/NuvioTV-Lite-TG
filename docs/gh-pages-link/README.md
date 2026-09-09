# GitHub Pages link page (free QR web flow)

This folder contains a minimal static page to approve TV QR login codes using your own Supabase project.

## 1) Prerequisites

- You already ran: `docs/supabase_qr_rpc_bootstrap.sql` in Supabase SQL editor.
- In that SQL, `approve_tv_login_session(p_code text)` exists and grants execute to `authenticated`.

## 2) Configure the page

Edit `docs/gh-pages-link/index.html` and set:

- `SUPABASE_URL` -> your project base URL (no `/rest/v1`)
- `SUPABASE_ANON_KEY` -> your anon key

## 3) Publish with GitHub Pages

Simplest method:

1. Push this repo branch to GitHub.
2. In repo settings -> Pages:
   - Source: `Deploy from a branch`
   - Branch: `dev` (or any), folder: `/docs`
3. Your page URL will be:
   - `https://<your-user>.github.io/<repo>/gh-pages-link/`

## 4) Point Android build to your page

In `local.dev.properties` set:

```
DEVICE_LOGIN_WEB_BASE_URL=https://<your-user>.github.io/<repo>/gh-pages-link
TV_LOGIN_WEB_BASE_URL=https://<your-user>.github.io/<repo>/gh-pages-link
```

Then rebuild and reinstall the APK.

## 5) How flow works

1. TV app generates code + QR URL from Supabase RPC.
2. QR opens your GitHub Pages `/gh-pages-link`.
3. User signs in in the web page.
4. Page calls `approve_tv_login_session` with that code.
5. TV keeps polling and continues auth flow.

## Notes

- This page uses only public values (`SUPABASE_URL`, `SUPABASE_ANON_KEY`).
- Do **not** put service-role keys in frontend code.
- If TV still fails at final exchange, ensure your Supabase edge function
  `tv-logins-exchange` is deployed and compatible.
