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
    static final int SIZE = 96, GRID = 6, CELL = 16, BINS = 256;
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
                String name = query(x.getRequestURI().getRawQuery()).getOrDefault("name","").trim();
                if (name.isEmpty()) { send(x,400,"application/json","{\"error\":\"Name is required.\"}"); return; }
                byte[] bytes = x.getRequestBody().readAllBytes();
                List<Double> vector = faceprint(bytes);
                Database.saveFace(u[0], name, vector);
                send(x,200,"application/json","{\"ok\":true}");
                return;
            }

            if (p.equals("/api/identify") && m.equals("POST")) {
                if (u == null) { unauthorized(x); return; }
                double threshold = 0.45;
                try { threshold = Double.parseDouble(query(x.getRequestURI().getRawQuery()).getOrDefault("threshold","0.45")); } catch(Exception ignored){}
                List<Double> probe = faceprint(x.getRequestBody().readAllBytes());
                String best = null; double bestD = Double.MAX_VALUE;
                for (QueryDocumentSnapshot d : Database.faces(u[0])) {
                    List<Double> v = (List<Double>) d.get("vector");
                    double dist = chiSquare(probe, v);
                    if (dist < bestD) { bestD = dist; best = d.getString("name"); }
                }
                boolean match = best != null && bestD < threshold;
                send(x,200,"application/json",
                        "{\"match\":" + match + ",\"name\":\"" + esc(match ? best : "") + "\",\"distance\":" + bestD + "}");
                return;
            }

            if (m.equals("GET")) { file(x,p); return; }
            send(x,405,"text/plain","Method not allowed");
        } catch (Exception e) {
            e.printStackTrace();
            send(x,500,"application/json","{\"error\":\"Server error.\"}");
        }
    }

    static List<Double> faceprint(byte[] bytes) throws IOException {
        BufferedImage in = ImageIO.read(new ByteArrayInputStream(bytes));
        if (in == null) throw new IllegalArgumentException("Invalid image.");
        int side = Math.min(in.getWidth(), in.getHeight());
        int sx=(in.getWidth()-side)/2, sy=(in.getHeight()-side)/2;
        BufferedImage im = new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g=im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(in,sx,sy,sx+side,sy+side,0,0,SIZE,SIZE,null); g.dispose();

        ArrayList<Double> out=new ArrayList<>(GRID*GRID*BINS);
        for(int gy=0;gy<GRID;gy++) for(int gx=0;gx<GRID;gx++){
            double[] h=new double[BINS];
            for(int y=gy*CELL;y<(gy+1)*CELL;y++) for(int x=gx*CELL;x<(gx+1)*CELL;x++){
                int c=im.getRaster().getSample(x,y,0), code=0;
                int[][] d={{-1,-1},{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0}};
                for(int k=0;k<8;k++){
                    int xx=Math.max(0,Math.min(SIZE-1,x+d[k][0]));
                    int yy=Math.max(0,Math.min(SIZE-1,y+d[k][1]));
                    if(im.getRaster().getSample(xx,yy,0)>=c) code|=(1<<k);
                }
                h[code]++;
            }
            for(double v:h) out.add(v/(CELL*CELL));
        }
        return out;
    }

    static double chiSquare(List<Double> a,List<Double> b){
        int n=Math.min(a.size(),b.size()); double s=0;
        for(int i=0;i<n;i++){double x=a.get(i),y=b.get(i); double den=x+y; if(den>1e-12)s+=(x-y)*(x-y)/den;}
        return s*0.5;
    }

    static void file(HttpExchange x,String p) throws IOException {
        if(p.equals("/")) p="/index.html";
        if(p.contains("..")) { send(x,400,"text/plain","Bad path"); return; }
        File f=new File("public",p.substring(1));
        if(!f.isFile()){send(x,404,"text/plain","Not found");return;}
        String ct=p.endsWith(".html")?"text/html; charset=utf-8":p.endsWith(".js")?"application/javascript":"application/octet-stream";
        send(x,200,ct,java.nio.file.Files.readString(f.toPath()));
    }

    static Map<String,String> query(String raw){
        Map<String,String> m=new HashMap<>(); if(raw==null)return m;
        for(String s:raw.split("&")){String[] a=s.split("=",2);if(a.length==2)m.put(URLDecoder.decode(a[0],StandardCharsets.UTF_8),URLDecoder.decode(a[1],StandardCharsets.UTF_8));}
        return m;
    }

    static void unauthorized(HttpExchange x)throws IOException{send(x,401,"application/json","{\"error\":\"Please sign in first.\"}");}
    static void redirect(HttpExchange x,String to)throws IOException{x.getResponseHeaders().add("Location",to);x.sendResponseHeaders(302,-1);x.close();}
    static void send(HttpExchange x,int code,String type,String body)throws IOException{byte[] b=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(code,b.length);try(OutputStream o=x.getResponseBody()){o.write(b);}}
    static String esc(String s){if(s==null)return "";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}
    static String env(String k,String d){String v=System.getenv(k);return v==null||v.isBlank()?d:v;}
    static class PathHolder {}
}
