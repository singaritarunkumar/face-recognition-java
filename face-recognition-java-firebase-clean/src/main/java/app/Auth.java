package app;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Auth {
    static final SecureRandom RNG = new SecureRandom();
    static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    static final String BASE = env("BASE_URL", "http://localhost:8080");

    static boolean handle(HttpExchange x, String p, String method) throws IOException {
        if (!p.startsWith("/auth/")) return false;

        if (method.equals("GET") && p.equals("/auth/health")) {
            if (!Database.ready) {
                Server.send(x, 503, "application/json",
                        "{\"ok\":false,\"error\":\"Firebase unavailable\",\"detail\":\"" + Server.esc(Database.initError) + "\"}");
            } else Server.send(x, 200, "application/json", "{\"ok\":true}");
            return true;
        }

        if (method.equals("GET") && p.equals("/auth/me")) {
            String[] u = user(x);
            if (u == null) Server.send(x, 401, "application/json", "{\"error\":\"Not signed in.\"}");
            else Server.send(x, 200, "application/json",
                    "{\"name\":\"" + Server.esc(u[2]) + "\",\"email\":\"" + Server.esc(u[1]) + "\"}");
            return true;
        }

        if (!method.equals("POST")) {
            Server.send(x, 405, "application/json", "{\"error\":\"Method not allowed.\"}");
            return true;
        }

        String body = new String(x.getRequestBody().readNBytes(20000), StandardCharsets.UTF_8);
        String email = js(body, "email").trim().toLowerCase(Locale.ROOT);
        String password = js(body, "password");

        if (!Database.ready) {
            Server.send(x, 503, "application/json",
                    "{\"error\":\"Firebase authentication is not available.\",\"detail\":\"" +
                            Server.esc(Database.initError) + "\"}");
            return true;
        }

        try {
            switch (p) {
                case "/auth/signup" -> signup(x, email, password, js(body, "name"));
                case "/auth/login" -> login(x, email, password);
                case "/auth/logout" -> logout(x);
                default -> Server.send(x, 404, "application/json", "{\"error\":\"Not found.\"}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            Server.send(x, 500, "application/json", "{\"error\":\"Authentication service error.\"}");
        }
        return true;
    }

    static void signup(HttpExchange x, String email, String pass, String name) throws Exception {
        if (!EMAIL.matcher(email).matches()) {
            Server.send(x, 400, "application/json", "{\"error\":\"Enter a valid email.\"}");
            return;
        }
        if (pass.length() < 8) {
            Server.send(x, 400, "application/json", "{\"error\":\"Password must contain at least 8 characters.\"}");
            return;
        }
        if (Database.findUserByEmail(email) != null) {
            Server.send(x, 409, "application/json", "{\"error\":\"Account already exists.\"}");
            return;
        }

        String id = random(18);
        String safeName = name == null || name.isBlank() ? email.substring(0, email.indexOf('@')) : name.trim();
        Database.saveUser(id, email, safeName, hashPassword(pass));
        startSession(x, id);
        Server.send(x, 200, "application/json", "{\"ok\":true}");
    }

    static void login(HttpExchange x, String email, String pass) throws Exception {
        String[] u = Database.findUserByEmail(email);
        if (u == null || !verifyPassword(pass, u[3])) {
            Server.send(x, 401, "application/json", "{\"error\":\"Wrong email or password.\"}");
            return;
        }
        startSession(x, u[0]);
        Server.send(x, 200, "application/json", "{\"ok\":true}");
    }

    static void logout(HttpExchange x) throws Exception {
        String token = cookie(x, "sid");
        if (!token.isEmpty()) Database.deleteSession(hash(token));
        x.getResponseHeaders().add("Set-Cookie", "sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + secure());
        Server.send(x, 200, "application/json", "{}");
    }

    static void startSession(HttpExchange x, String userId) throws Exception {
        String token = random(32);
        long expires = System.currentTimeMillis() + 24L * 60 * 60 * 1000;
        Database.saveSession(hash(token), userId, expires);
        x.getResponseHeaders().add("Set-Cookie",
                "sid=" + token + "; Path=/; HttpOnly; SameSite=Lax" + secure());
    }

    static String[] user(HttpExchange x) {
        try {
            String token = cookie(x, "sid");
            if (token.isEmpty() || !Database.ready) return null;
            String[] s = Database.findSession(hash(token));
            if (s == null || Long.parseLong(s[1]) < System.currentTimeMillis()) return null;
            return userById(s[0]);
        } catch (Exception e) {
            return null;
        }
    }

    static String[] userById(String id) throws Exception {
        for (var d : Database.db.collection("users").whereEqualTo("id", id).get().get().getDocuments()) {
            return new String[]{d.getString("id"), d.getString("email"), d.getString("name"), d.getString("passwordHash")};
        }
        return null;
    }

    static String hashPassword(String pass) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return pbkdf(pass, salt);
    }

    static boolean verifyPassword(String pass, String stored) {
        try {
            String[] p = stored.split(":", 2);
            if (p.length != 2) return false;
            return MessageDigest.isEqual(
                    pbkdf(pass, Base64.getDecoder().decode(p[0])).getBytes(StandardCharsets.UTF_8),
                    stored.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return false; }
    }

    static String pbkdf(String pass, byte[] salt) {
        try {
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pass.toCharArray(), salt, 120000, 256)).getEncoded();
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(h);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String cookie(HttpExchange x, String name) {
        String c = x.getRequestHeaders().getFirst("Cookie");
        if (c != null) for (String p : c.split(";\\s*"))
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        return "";
    }

    static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String js(String body, String key) {
        var m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : "";
    }

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    static String secure() { return BASE.startsWith("https") ? "; Secure" : ""; }
}
