# Firebase setup — start from zero

## 1. Create Firebase project

Firebase Console → Add project → create a new project.

## 2. Create Firestore

Build → Firestore Database → Create database.

Choose a location and create the database.

Do not use Realtime Database for this application.

## 3. Create service account

Firebase Console → Project settings → Service accounts → Firebase Admin SDK → Generate new private key.

Keep the downloaded JSON private.

## 4. Get Project ID

Firebase Console → Project settings → General → Project ID.

Copy the exact Project ID.

## 5. Vercel variables

Create these under Production:

`BASE_URL`
- Value: your Vercel HTTPS URL

`FIREBASE_PROJECT_ID`
- Value: the Firebase Project ID

`FIREBASE_SERVICE_ACCOUNT_JSON`
- Value: the complete service-account JSON
- Type: Secret
- Environment: Production

## 6. Redeploy

After saving environment variables, redeploy the Production deployment.

## 7. Verify

Open:
`https://YOUR-DOMAIN/login.html`

Create a test account.

Then Firebase Console → Firestore Database → Data should contain:
`users`

The application also creates:
`sessions`
and:
`users/{userId}/faces`

## Troubleshooting

If the website reports Firebase unavailable, open Vercel deployment logs and look for:
`FIREBASE_INIT_FAILED:`

That line is the useful diagnostic. Never share the service-account JSON or private key.
