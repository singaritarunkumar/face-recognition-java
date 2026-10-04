package app;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.*;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;

final class Database {
    static boolean ready = false;
    static Firestore db;
    static String initError = "";

    static void init() {
        try {
            String json = System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON");
            String project = System.getenv("FIREBASE_PROJECT_ID");
            if (json == null || json.isBlank()) throw new IllegalStateException("FIREBASE_SERVICE_ACCOUNT_JSON is missing");
            if (project == null || project.isBlank()) throw new IllegalStateException("FIREBASE_PROJECT_ID is missing");

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(
                            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
                    .setProjectId(project)
                    .build();

            if (FirebaseApp.getApps().isEmpty()) FirebaseApp.initializeApp(options);
            db = FirestoreClient.getFirestore();

            // Real Firestore connectivity check.
            db.collection("_health").document("check").get().get();
            ready = true;
            System.out.println("Firebase Firestore ready.");
        } catch (Exception e) {
            ready = false;
            initError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            System.err.println("FIREBASE_INIT_FAILED: " + initError);
        }
    }

    static String[] findUserByEmail(String email) throws Exception {
        if (!ready) return null;
        DocumentSnapshot d = db.collection("users").document(key(email)).get().get();
        if (!d.exists()) return null;
        return new String[]{
                d.getString("id"),
                d.getString("email"),
                d.getString("name"),
                d.getString("passwordHash")
        };
    }

    static void saveUser(String id, String email, String name, String passwordHash) throws Exception {
        db.collection("users").document(key(email)).set(Map.of(
                "id", id,
                "email", email,
                "name", name,
                "passwordHash", passwordHash,
                "createdAt", System.currentTimeMillis()
        )).get();
    }

    static void saveSession(String tokenHash, String userId, long expiresAt) throws Exception {
        db.collection("sessions").document(tokenHash).set(Map.of(
                "userId", userId,
                "expiresAt", expiresAt
        )).get();
    }

    static String[] findSession(String tokenHash) throws Exception {
        DocumentSnapshot d = db.collection("sessions").document(tokenHash).get().get();
        if (!d.exists()) return null;
        return new String[]{
                d.getString("userId"),
                String.valueOf(d.getLong("expiresAt"))
        };
    }

    static void deleteSession(String tokenHash) throws Exception {
        db.collection("sessions").document(tokenHash).delete().get();
    }

    static void saveFace(String userId, String name, List<Double> vector) throws Exception {
        String id = UUID.randomUUID().toString();
        db.collection("users").document(userId).collection("faces").document(id).set(Map.of(
                "name", name,
                "vector", vector,
                "createdAt", System.currentTimeMillis()
        )).get();
    }

    static List<QueryDocumentSnapshot> faces(String userId) throws Exception {
        return db.collection("users").document(userId).collection("faces").get().get().getDocuments();
    }

    static void deleteFaces(String userId) throws Exception {
        for (DocumentSnapshot d : faces(userId)) d.getReference().delete().get();
    }

    static String key(String email) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
