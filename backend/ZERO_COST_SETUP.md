# X.MAX Free-Tier Firebase + Cloudflare Chat File Storage Setup

This workspace integrates **Firebase Spark Plan** with **Cloudflare Workers Free Plan** and **Cloudflare R2 Free Tier**:
1. **Firebase Spark Plan (₹0)**:
   - Authentication (Google Sign-In via Credential Manager)
   - Cloud Firestore (named DB `ai-studio-android-xteam-28ae82d9-933c-4b9e-8445-3ca11186cacb` storing chat channels, messages, and R2 object metadata)
   - Firebase Realtime Database (typing/recording/presence)
2. **Cloudflare Workers Free Plan ($0 / ₹0)**:
   - `100,000` requests/day, `10 ms` CPU time per request
   - Verifies Firebase Auth ID tokens (`Authorization: Bearer <token>`) against Google public JWKs
   - Verifies Firestore membership (`APPROVED` & active) and `chat_channels` access before uploading or serving any file
3. **Cloudflare R2 Free Tier ($0 / ₹0 egress)**:
   - `10 GB`/month storage, `1,000,000` Class A writes/month, `10,000,000` Class B reads/month
   - Stores chat images (up to 5 MB), audio notes (up to 3 MB), and documents/PDFs/Office/ZIP files (up to 10 MB) outside Firestore so Firestore stays fast and compact.
   - Small attachments (`<= 520 KB`) automatically fall back to Firestore inline storage if the Cloudflare Worker is offline or not yet deployed.

## Deployment Steps (Zero Secrets in the Android APK)

1. **Authenticate Wrangler with Cloudflare** (run from `backend/cloudflare-worker/`):
   ```bash
   npx wrangler login
   ```

2. **Create the Free Cloudflare R2 Bucket**:
   ```bash
   npx wrangler r2 bucket create xmax-chat-files
   ```

3. **Deploy the Worker**:
   ```bash
   npx wrangler deploy
   ```
   Verify health at `https://<your-worker>.workers.dev/v1/health`:
   It should return `"storageProvider": "CLOUDFLARE_R2"`.

4. **Point the Android App to Your Worker URL**:
   - In the AI Studio **Secrets** panel, set `CHAT_WORKER_BASE_URL` to your deployed `https://<your-worker>.workers.dev` URL (no trailing slash).
   - No R2 API keys, secret tokens, or service account keys are ever placed in the Android APK or `.env`.
