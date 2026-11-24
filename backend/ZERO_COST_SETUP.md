# X.MAX Zero-Cost Chat Infrastructure Setup Guide (₹0 Target)

This workspace uses strictly free-tier services without billing activation or credit cards:
1. **Firebase Spark Plan**: Authentication (Google Sign-In), Cloud Firestore (named DB), Firebase Realtime Database (presence/typing/recording), Firebase Cloud Messaging (push notifications).
2. **Supabase Storage Free Tier**: Private object storage (`1 GB` storage, `5 GB/month` egress, no credit card).
3. **Cloudflare Workers Free Plan**: Stateless security proxy (`100,000 req/day`, `10 ms` CPU/req, no credit card).

## Setup Steps (Zero Secrets in Android APK)

1. **Enable Firebase Realtime Database (Spark)**:
   - Open Firebase Console -> Realtime Database -> Create Database (test/locked rules, then set presence/typing/recording rules for authenticated users).
   - If using a non-US default RTDB URL, set `FIREBASE_RTDB_URL=https://<your-rtdb>.firebaseio.com` in the AI Studio **Secrets** panel.

2. **Create Free Supabase Storage Project**:
   - Create a free Supabase project (no credit card required).
   - Open **SQL Editor** and execute `backend/supabase/storage_setup.sql`.
   - Copy your Project URL and `service_role` key (keep `service_role` strictly for Step 3 below—never add it to `.env` or Android code).

3. **Deploy Cloudflare Worker Proxy (Free Plan)**:
   - In `backend/cloudflare-worker/`:
     ```bash
     npx wrangler deploy
     npx wrangler secret put SUPABASE_URL
     npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
     npx wrangler secret put FIREBASE_SERVICE_ACCOUNT_JSON
     ```
   - Set `CHAT_WORKER_BASE_URL=https://xmax-chat-worker.<your-subdomain>.workers.dev` in the AI Studio **Secrets** panel (this public URL is the only value exposed to Android `BuildConfig`).
