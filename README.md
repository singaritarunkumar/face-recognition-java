# Face Recognition Java — Firebase + Vercel

A B.Tech final-year demonstration project using Java 17, a browser camera UI, a lightweight LBP faceprint, Cloud Firestore, and a Vercel container deployment.

## Architecture

Browser → Java HTTP Server → Firebase Admin SDK → Cloud Firestore

Firestore collections:
- `users`
- `sessions`
- `users/{userId}/faces`

No Firebase service-account JSON is stored in this repository.

## Local requirements

- JDK 17
- Maven 3.9+
- Internet access for Maven dependencies
- A Firebase project is only required when testing cloud authentication.

## Local run

Windows:
```bat
run.bat
```

Linux/macOS:
```bash
./run.sh
```

For local Firebase testing set:
```bat
set FIREBASE_PROJECT_ID=your-project-id
set FIREBASE_SERVICE_ACCOUNT_JSON={"type":"service_account",...}
set BASE_URL=http://localhost:8080
```

Do not commit the service-account JSON.

## Vercel environment variables

Required in Production:
```text
BASE_URL=https://YOUR-VERCEL-DOMAIN.vercel.app
FIREBASE_PROJECT_ID=your-firebase-project-id
FIREBASE_SERVICE_ACCOUNT_JSON=<complete Firebase Admin SDK service-account JSON>
```

`FIREBASE_SERVICE_ACCOUNT_JSON` must be a Vercel Secret.

## Deployment

1. Create a Firebase project.
2. Enable Cloud Firestore.
3. Create a Firebase Admin SDK service-account key.
4. Create a GitHub repository.
5. Push this project.
6. Import the GitHub repository into Vercel.
7. Add the three Production environment variables.
8. Redeploy.
9. Open `/login.html`.
10. Create an account.
11. Confirm the user document appears in Firestore.

## Important

This is an academic/demo face-recognition implementation. It uses a handcrafted LBP descriptor and does not provide automatic face detection, liveness detection, or production-grade biometric security. Do not use it to identify people without consent.

Vercel container instances are stateless; Firestore is therefore used for users, sessions and faceprints in this clean version.
