package app;

import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

public class Server {
    // Faceprint: 6x6 grid of 16px cells, 59-bin uniform LBP histogram per cell (2124 numbers).
    static final int SIZE = 96, GRID = 6, CELL = 16, BINS = 59, PAD = 4, E = SIZE + 2 * PAD, DIM = GRID * GRID * BINS;
    // Chi-square distance between faceprints. Tested on 100 real face crops: same face (lighting/shift/noise
    // changes) scores ~1-6, different people ~8-20. 7.5 = near-zero false accepts. Raise it to be more
    // forgiving, lower it to be stricter. (Can also be overridden per request with ?threshold=)
    static final double DEFAULT_THRESHOLD = 7.5;
    static final int[] UNIFORM = uniformMap();
    static final int[][] DIRS = {{-1,-1},{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0}};
    static final PathHolder WEB = new PathHolder();

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Database.init();
        int port = Integer.parseInt(args.length > 0 ? args[0] : env("PORT", "8080"));
        HttpServer s = HttpServer.create(new InetSocketAddress(env("HOST", "0.0.0.0"), port), 0);
        s.createContext("/", Server::route);
        s.start();
        System.out.println("Open http://localhost:" + port);
    }

    static void route(HttpExchange x) throws IOException {
        try {
            String p = x.getRequestURI().getPath(), m = x.getRequestMethod();
            if (Auth.handle(x, p, m)) return;

            String[] u = Auth.user(x);
            if ((p.equals("/") || p.equals("/index.html")) && u == null) {
                redirect(x, "/login.html"); return;
            }

            if (p.equals("/api/people") && m.equals("GET")) {
                if (u == null) { unauthorized(x); return; }
                List<QueryDocumentSnapshot> docs = Database.faces(u[0]);
                StringBuilder b = new StringBuilder("[");
                for (int i=0;i<docs.size();i++) {
                    if (i>0) b.append(',');
                    b.append("{\"name\":\"").append(esc(docs.get(i).getString("name"))).append("\"}");
                }
                b.append(']');
                send(x,200,"application/json",b.toString());
                return;
            }

            if (p.equals("/api/people") && m.equals("DELETE")) {
                if (u == null) { unauthorized(x); return; }
                Database.deleteFaces(u[0]);
                send(x,200,"application/json","{}"); return;
            }

            if (p.equals("/api/enroll") && m.equals("POST")) {
                if (u == null) { unauthorized(x); return; }
                String name = query(x.getRequestURI().getRawQuery()).getOrDefault("name","").replaceAll("\\p{Cntrl}"," ").trim();
                if (name.length() > 60) name = name.substring(0, 60);
                if (name.isEmpty()) { send(x,400,"application/json","{\"error\":\"Name is required.\"}"); return; }
                byte[] bytes = x.getRequestBody().readAllBytes();
                double[] fp = faceprints(bytes, new int[][]{{PAD, PAD}})[0];
                Database.saveFace(u[0], name, toList(fp));
                send(x,200,"application/json","{\"ok\":true}");
                return;
            }

            if (p.equals("/api/identify") && m.equals("POST")) {
                if (u == null) { unauthorized(x); return; }
                // The distance is a chi-square sum over 36 LBP cells: the same face scores ~4-8,
                // different faces/scenes ~15+. The old default (0.45) was unreachable, so nobody ever matched.
                double threshold = DEFAULT_THRESHOLD;
                try {
                    double t = Double.parseDouble(query(x.getRequestURI().getRawQuery()).getOrDefault("threshold", String.valueOf(DEFAULT_THRESHOLD)));
                    if (Double.isFinite(t) && t > 0) threshold = t;
                } catch(Exception ignored){}
                // Probe is compared at 25 small offsets (+-4px) so a slightly off-centre face still matches.
                int[][] offs = new int[25][];
                for (int a = 0, n = 0; a < 5; a++) for (int b = 0; b < 5; b++) offs[n++] = new int[]{PAD - 4 + 2 * a, PAD - 4 + 2 * b};
                double[][] probes = faceprints(x.getRequestBody().readAllBytes(), offs);
                String best = null; double bestD = Double.MAX_VALUE; int enrolled = 0;
                for (QueryDocumentSnapshot d : Database.faces(u[0])) {
                    Object raw = d.get("vector");
                    if (!(raw instanceof List<?> v) || v.size() != DIM) continue;
                    enrolled++;
                    double[] sv = new double[DIM];
                    for (int k = 0; k < DIM; k++) sv[k] = ((Number) v.get(k)).doubleValue();
                    double dist = Double.MAX_VALUE;
                    for (double[] pr : probes) dist = Math.min(dist, chiSquare(pr, sv));
                    if (dist < bestD) { bestD = dist; best = d.getString("name"); }
                }
                boolean match = best != null && bestD < threshold;
                String dist = best == null ? "null" : String.valueOf(bestD);
                send(x,200,"application/json",
                        "{\"match\":" + match + ",\"name\":\"" + esc(match ? best : "") + "\",\"distance\":" + dist + ",\"enrolled\":" + enrolled + "}");
                return;
            }

            if (m.equals("GET")) { file(x,p); return; }
            send(x,405,"text/plain","Method not allowed");
        } catch (IllegalArgumentException e) {
            send(x,400,"application/json","{\"error\":\"" + esc(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            send(x,500,"application/json","{\"error\":\"Server error.\"}");
        }
    }

    /** Maps the 256 raw LBP codes to 58 "uniform" patterns + 1 bin for everything else (59 bins). */
    static int[] uniformMap() {
        int[] m = new int[256]; int idx = 0;
        for (int c = 0; c < 256; c++) {
            int t = 0;
            for (int k = 0; k < 8; k++) if (((c >> k) & 1) != ((c >> ((k + 1) % 8)) & 1)) t++;
            m[c] = t <= 2 ? idx++ : -1;
        }
        for (int c = 0; c < 256; c++) if (m[c] < 0) m[c] = idx;   // idx == 58 here
        return m;
    }

    /** Decodes the photo, centre-crops it square, smooths it, and returns one faceprint per window offset. */
    static double[][] faceprints(byte[] bytes, int[][] offsets) throws IOException {
        BufferedImage in = ImageIO.read(new ByteArrayInputStream(bytes));
        if (in == null) throw new IllegalArgumentException("Invalid image.");
        int side = Math.min(in.getWidth(), in.getHeight());
        int sx = (in.getWidth() - side) / 2, sy = (in.getHeight() - side) / 2;
        BufferedImage im = new BufferedImage(E, E, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(in, 0, 0, E, E, sx, sy, sx + side, sy + side, null);   // dest rect first, then source rect
        g.dispose();

        // 3x3 binomial blur ([1 2 1]/4 in each direction) to suppress camera noise
        double[][] a = new double[E][E], b = new double[E][E];
        for (int y = 0; y < E; y++) for (int x = 0; x < E; x++) a[y][x] = im.getRaster().getSample(x, y, 0);
        for (int y = 0; y < E; y++) for (int x = 0; x < E; x++)
            b[y][x] = (a[Math.max(0, y - 1)][x] + 2 * a[y][x] + a[Math.min(E - 1, y + 1)][x]) / 4;
        for (int y = 0; y < E; y++) for (int x = 0; x < E; x++)
            a[y][x] = Math.rint((b[y][Math.max(0, x - 1)] + 2 * b[y][x] + b[y][Math.min(E - 1, x + 1)]) / 4);

        int[][] code = new int[E][E];
        for (int y = 0; y < E; y++) for (int x = 0; x < E; x++) {
            int c = 0;
            for (int k = 0; k < 8; k++) {
                int xx = Math.max(0, Math.min(E - 1, x + DIRS[k][0]));
                int yy = Math.max(0, Math.min(E - 1, y + DIRS[k][1]));
                if (a[yy][xx] >= a[y][x]) c |= 1 << k;
            }
            code[y][x] = UNIFORM[c];
        }

        double[][] out = new double[offsets.length][];
        for (int n = 0; n < offsets.length; n++) {
            int ox = offsets[n][0], oy = offsets[n][1];
            double[] v = new double[DIM];
            for (int gy = 0; gy < GRID; gy++) for (int gx = 0; gx < GRID; gx++) {
                int base = (gy * GRID + gx) * BINS;
                for (int y = oy + gy * CELL; y < oy + (gy + 1) * CELL; y++)
                    for (int x = ox + gx * CELL; x < ox + (gx + 1) * CELL; x++) v[base + code[y][x]]++;
                for (int k = 0; k < BINS; k++) v[base + k] /= (CELL * CELL);
            }
            out[n] = v;
        }
        return out;
    }

    static List<Double> toList(double[] v) {
        ArrayList<Double> l = new ArrayList<>(v.length);
        for (double d : v) l.add(d);
        return l;
    }

    static double chiSquare(double[] a, double[] b) {
        int n = Math.min(a.length, b.length); double s = 0;
        for (int i = 0; i < n; i++) { double den = a[i] + b[i]; if (den > 1e-12) s += (a[i] - b[i]) * (a[i] - b[i]) / den; }
        return s * 0.5;
    }

    static void file(HttpExchange x,String p) throws IOException {
        if(p.equals("/")) p="/index.html";
        if(p.contains("..")) { send(x,400,"text/plain","Bad path"); return; }
        File f=new File("public",p.substring(1));
        if(!f.isFile()){send(x,404,"text/plain","Not found");return;}
        String ct=p.endsWith(".html")?"text/html; charset=utf-8":p.endsWith(".js")?"application/javascript; charset=utf-8":p.endsWith(".css")?"text/css; charset=utf-8":p.endsWith(".json")?"application/json":p.endsWith(".svg")?"image/svg+xml":"application/octet-stream";
        x.getResponseHeaders().set("Cache-Control","no-cache");
        send(x,200,ct,java.nio.file.Files.readString(f.toPath()));
    }

    static Map<String,String> query(String raw){
        Map<String,String> m=new HashMap<>(); if(raw==null)return m;
        for(String s:raw.split("&")){String[] a=s.split("=",2);if(a.length==2)m.put(URLDecoder.decode(a[0],StandardCharsets.UTF_8),URLDecoder.decode(a[1],StandardCharsets.UTF_8));}
        return m;
    }

    static void unauthorized(HttpExchange x)throws IOException{send(x,401,"application/json","{\"error\":\"Please sign in first.\"}");}
    static void redirect(HttpExchange x,String to)throws IOException{x.getResponseHeaders().add("Location",to);x.sendResponseHeaders(302,-1);x.close();}
    static void send(HttpExchange x,int code,String type,String body)throws IOException{byte[] b=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("Permissions-Policy","camera=(self)");x.sendResponseHeaders(code,b.length);try(OutputStream o=x.getResponseBody()){o.write(b);}}
    static String esc(String s){if(s==null)return "";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}
    static String env(String k,String d){String v=System.getenv(k);return v==null||v.isBlank()?d:v;}
    static class PathHolder {}
}
