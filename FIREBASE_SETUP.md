# X.MAX Projects & Quotations — Firebase Setup & Architecture Guide

## 1. Firebase Project & Database Configuration
- **Package Name (`applicationId`)**: `com.aistudio.xmax.krmxpq`
- **Firebase Project ID**: `gen-lang-client-0123003419`
- **Named Firestore Database ID**: `ai-studio-android-xmaxproj-3b763d99-0a84-4e8c-b562-67dd67a53298`
  (Configured in `app/src/main/res/values/firebase_applet_config.xml` and `app/google-services.json`).
- **Authentication**: Google Sign-In via Jetpack `CredentialManager` (`GetSignInWithGoogleOption`) + Firebase Authentication (`GoogleAuthProvider`). Anonymous sign-in is disabled and prohibited.
- **Database Initialization**: Every Firestore call resolves the named database via `FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))`.

## 2. Security Rules & Indexes
- `firestore.rules`: Enforces zero-trust RBAC, strict isolation of `member_private` (age/email/phone) and commercial records (`projects`, `payments`, `clients`, `quotations`), non-delegable reserved project permissions (`ACCEPT_PROJECT`, `DECLINE_PROJECT`, `DELETE_PROJECT`), Admin delegation revocation cascade checks, and field-level deliverable update guards for assigned Staff.
- `firestore.indexes.json`: Defines composite and field indexes for deliverables (`assignedToUid`, `projectId`), updates (`createdAt DESC`), activity logs, and quotations.

## 3. Trusted Super Admin & Starting Catalogue Seeding
- Typing `"X.MAX"` or requesting `"Manager"` during onboarding NEVER grants privileges; self-registered users are created strictly as `STAFF` + `PENDING`.
- The default bootstrapped Super Admin email (`xdotmax@gmail.com`, verified by Firebase Auth token) can provision `/companies/xmax` on first login and idempotently seed:
  - **40 Individual Services** (`srv_01`..`srv_40`)
  - **5 One-Time Packages** (`pkg_01`..`pkg_05`, including **Business Launch** at ₹55,000)
  - **11 Add-Ons** (`add_01`..`add_11`, including **Extra Page** at ₹2,000 and **Urgent Delivery 25% Surcharge**)
  - **5 Departments**, **8 Job Roles**, and **6 Default Routing Rules**.
- Additional Super Admins can be promoted or invited by any existing Super Admin in **More -> Team & Permissions**.

## 4. Running Local Verification
1. **Firestore Security Rules Emulator Tests**:
   ```bash
   FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
   ```
2. **Android Unit & Robolectric Tests (Catalogue, Quote Math, Permissions, Routing, A4 PDF)**:
   ```bash
   gradle :app:testDebugUnitTest
   ```
3. **Installing on a Physical Android Phone**:
   - Open this repository in Android Studio (or run `gradle :app:assembleDebug`).
   - If you generate a new keystore on another machine, add its SHA-1 and SHA-256 fingerprints in Firebase Console -> Project Settings -> Android App (`com.aistudio.xmax.krmxpq`) and replace `app/google-services.json`.
   - Ensure Google Sign-In is enabled in Firebase Authentication -> Sign-in providers.

## 5. Verification Coverage Summary
- **Tested Automatically**:
  - Firestore security rules against local emulator (`firestore.test.js` Scenarios 1, 2, 3, 7, 8 & PII isolation).
  - JVM/Robolectric tests (`XMaxDomainAndPdfTest`) for all 56 catalogue items, Business Launch + Extra Page totals (₹57,000 / ₹28,500), custom/actual-cost validation, urgent 25% surcharge & overlap warnings, permission delegation & revocation cascade, 1/N/0 work distribution, and A4 PDF generation + SHA-256 digest.
  - Debug Kotlin & APK compilation (`gradle :app:compileDebugKotlin`).
- **Requires Manual Verification on Android Device**:
  - Interactive Google Sign-In account chooser via Google Play Services (`CredentialManager`).
  - Native Android Share Sheet PDF handoff to WhatsApp / Email apps (`FileProvider`).
