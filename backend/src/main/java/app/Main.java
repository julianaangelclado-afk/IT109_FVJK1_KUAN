package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.cdimascio.dotenv.Dotenv;
import io.javalin.Javalin;
import io.javalin.http.*;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/** Campus Event Booking & Ticketing - Javalin REST API (MySQL, Caffeine cache, PayMongo, Google OAuth, SMTP, QR). */
public class Main {
  static final Dotenv env = Dotenv.configure().ignoreIfMissing().load();
  static String e(String k, String d) { String v = env.get(k); return v == null || v.isBlank() ? d : v; }
  static final String FRONT = e("FRONTEND_URL", "http://localhost:3000");
  static final ObjectMapper M = new ObjectMapper();
  static final HttpClient HC = HttpClient.newHttpClient();
  static final Object LOCK = new Object();
  static HikariDataSource ds;

  // ---- caches: data cache (60s expiry, cleared on every write), sessions, captchas, rate limits
  static final Cache<String, Object> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(500).recordStats().build();
  static final Cache<String, Long> sessions = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(12)).build();
  static final Cache<String, String> captchas = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build();
  static final Cache<String, AtomicInteger> hits = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).build();

  static final String SCHEMA = """
    CREATE TABLE IF NOT EXISTS users(id BIGINT AUTO_INCREMENT PRIMARY KEY, google_sub VARCHAR(64) UNIQUE, email VARCHAR(190) UNIQUE NOT NULL, name VARCHAR(120), picture VARCHAR(500),
      role ENUM('attendee','organizer','admin') NOT NULL DEFAULT 'attendee', phone VARCHAR(20), region_code VARCHAR(12), region VARCHAR(120), province_code VARCHAR(12), province VARCHAR(120),
      city_code VARCHAR(12), city VARCHAR(120), barangay_code VARCHAR(12), barangay VARCHAR(120), active TINYINT NOT NULL DEFAULT 1, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
    CREATE TABLE IF NOT EXISTS events(id BIGINT AUTO_INCREMENT PRIMARY KEY, organizer_id BIGINT NOT NULL, title VARCHAR(120) NOT NULL, description TEXT, venue VARCHAR(120) NOT NULL,
      starts_at DATETIME NOT NULL, poster_url VARCHAR(500), category VARCHAR(30) NOT NULL, status ENUM('pending','published','rejected','cancelled') NOT NULL DEFAULT 'pending',
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(organizer_id) REFERENCES users(id));
    CREATE TABLE IF NOT EXISTS tiers(id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id BIGINT NOT NULL, name VARCHAR(60) NOT NULL, price_centavos INT NOT NULL, capacity INT NOT NULL,
      FOREIGN KEY(event_id) REFERENCES events(id) ON DELETE CASCADE);
    CREATE TABLE IF NOT EXISTS orders(id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, tier_id BIGINT NOT NULL, qty INT NOT NULL, amount_centavos INT NOT NULL,
      status ENUM('pending','paid','cancelled') NOT NULL DEFAULT 'pending', checkout_id VARCHAR(80), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(tier_id) REFERENCES tiers(id));
    CREATE TABLE IF NOT EXISTS tickets(id BIGINT AUTO_INCREMENT PRIMARY KEY, uuid CHAR(36) UNIQUE NOT NULL, order_id BIGINT NOT NULL, tier_id BIGINT NOT NULL, owner_id BIGINT NOT NULL,
      status ENUM('paid','used') NOT NULL DEFAULT 'paid', used_at DATETIME NULL, FOREIGN KEY(order_id) REFERENCES orders(id), FOREIGN KEY(tier_id) REFERENCES tiers(id), FOREIGN KEY(owner_id) REFERENCES users(id))
    """;

  // ---- db helpers (always parameterized -> no SQL injection)
  static List<Map<String, Object>> q(String sql, Object... p) throws Exception {
    try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
      for (int i = 0; i < p.length; i++) s.setObject(i + 1, p[i]);
      try (ResultSet r = s.executeQuery()) {
        ResultSetMetaData m = r.getMetaData(); List<Map<String, Object>> out = new ArrayList<>();
        while (r.next()) { Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= m.getColumnCount(); i++) { Object v = r.getObject(i); row.put(m.getColumnLabel(i), v instanceof TemporalAccessor ? String.valueOf(v) : v); }
          out.add(row); }
        return out;
      }
    }
  }
  static int upd(String sql, Object... p) throws Exception {
    try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement(sql)) { for (int i = 0; i < p.length; i++) s.setObject(i + 1, p[i]); return s.executeUpdate(); }
  }
  static long ins(String sql, Object... p) throws Exception {
    try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      for (int i = 0; i < p.length; i++) s.setObject(i + 1, p[i]); s.executeUpdate();
      try (ResultSet k = s.getGeneratedKeys()) { return k.next() ? k.getLong(1) : 0; }
    }
  }
  @SuppressWarnings("unchecked")
  static <T> T cached(String k, Callable<T> f) { return (T) cache.get(k, x -> { try { return f.call(); } catch (Exception ex) { throw new RuntimeException(ex); } }); }
  static int num(Map<String, Object> m, String k) { return ((Number) m.get(k)).intValue(); }
  static long lng(Map<String, Object> m, String k) { return ((Number) m.get(k)).longValue(); }

  // ---- validation (RegEx) + auth helpers
  @SuppressWarnings("unchecked") static Map<String, Object> body(Context c) { Map<String, Object> b = c.bodyAsClass(Map.class); return b == null ? new HashMap<>() : b; }
  static String s(Map<String, Object> b, String k) { Object v = b.get(k); return v == null ? "" : String.valueOf(v).trim(); }
  static String ck(Map<String, Object> b, String k, String re, String msg) { String v = s(b, k); if (!v.matches(re)) throw new BadRequestResponse(msg); return v; }
  static final String TXT = "^[\\p{L}\\p{N} .,:;'&!?()\\-]{3,120}$";
  static Map<String, Object> auth(Context c, String... roles) {
    Map<String, Object> u = c.attribute("u");
    if (u == null) throw new UnauthorizedResponse("Please sign in first");
    if (roles.length > 0 && !Arrays.asList(roles).contains(u.get("role"))) throw new ForbiddenResponse("Your role cannot do this");
    return u;
  }
  static boolean isAdmin(Map<String, Object> u) { return "admin".equals(u.get("role")); }
  static Map<String, Object> ownEvent(Map<String, Object> u, long id) throws Exception {
    var r = q("SELECT * FROM events WHERE id=?", id);
    if (r.isEmpty()) throw new NotFoundResponse("Event not found");
    if (!isAdmin(u) && lng(r.get(0), "organizer_id") != lng(u, "id")) throw new ForbiddenResponse("This is not your event");
    return r.get(0);
  }
  static boolean capOk(String id, String ans) { String a = captchas.getIfPresent(id); captchas.invalidate(id); return a != null && a.equals(ans.trim()); }
  static String hmac(String key, String data) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    StringBuilder sb = new StringBuilder(); for (byte x : m.doFinal(data.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x)); return sb.toString();
  }

  // ---- integrations
  static JsonNode pm(String path, Object body) throws Exception {
    if (e("PAYMONGO_SECRET_KEY", "").isEmpty()) throw new BadRequestResponse("PayMongo key is not configured");
    var b = HttpRequest.newBuilder(URI.create("https://api.paymongo.com/v1" + path)).header("Content-Type", "application/json")
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((e("PAYMONGO_SECRET_KEY", "") + ":").getBytes(StandardCharsets.UTF_8)));
    var req = body == null ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body))).build();
    var r = HC.send(req, HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() >= 300) throw new BadRequestResponse("PayMongo error: " + r.body());
    return M.readTree(r.body()).path("data");
  }
  static byte[] qr(String t) throws Exception {
    var m = new QRCodeWriter().encode(t, BarcodeFormat.QR_CODE, 320, 320, Map.of(EncodeHintType.MARGIN, 1));
    var o = new ByteArrayOutputStream(); MatrixToImageWriter.writeToStream(m, "PNG", o); return o.toByteArray();
  }
  static void mailAsync(String to, String subject, String html, List<String> qrIds) {
    String user = e("SMTP_USER", ""), pass = e("SMTP_APP_PASSWORD", "");
    if (user.isEmpty() || pass.isEmpty()) { System.out.println("[mail skipped] SMTP not configured"); return; }
    new Thread(() -> {
      try {
        Properties p = new Properties(); p.put("mail.smtp.auth", "true"); p.put("mail.smtp.starttls.enable", "true"); p.put("mail.smtp.host", "smtp.gmail.com"); p.put("mail.smtp.port", "587");
        Session ss = Session.getInstance(p, new jakarta.mail.Authenticator() { protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(user, pass); } });
        MimeMessage m = new MimeMessage(ss); m.setFrom(new InternetAddress(user, "Campus Tickets")); m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to)); m.setSubject(subject, "UTF-8");
        MimeMultipart mp = new MimeMultipart("related"); MimeBodyPart h = new MimeBodyPart(); h.setContent(html, "text/html; charset=utf-8"); mp.addBodyPart(h);
        for (int i = 0; i < qrIds.size(); i++) { MimeBodyPart img = new MimeBodyPart(); img.setDataHandler(new DataHandler(new ByteArrayDataSource(qr(qrIds.get(i)), "image/png"))); img.setHeader("Content-ID", "<qr" + i + ">"); img.setDisposition("inline"); mp.addBodyPart(img); }
        m.setContent(mp); Transport.send(m);
      } catch (Exception ex) { System.err.println("[mail failed] " + ex.getMessage()); }
    }).start();
  }
  static void markPaid(long oid) throws Exception {
    if (upd("UPDATE orders SET status='paid' WHERE id=? AND status='pending'", oid) == 0) return; // idempotent
    var o = q("SELECT o.*,us.email,us.name uname,t.name tier,e.title,e.venue,e.starts_at FROM orders o JOIN users us ON us.id=o.user_id JOIN tiers t ON t.id=o.tier_id JOIN events e ON e.id=t.event_id WHERE o.id=?", oid).get(0);
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < num(o, "qty"); i++) { String id = UUID.randomUUID().toString(); ins("INSERT INTO tickets(uuid,order_id,tier_id,owner_id) VALUES(?,?,?,?)", id, oid, o.get("tier_id"), o.get("user_id")); ids.add(id); }
    cache.invalidateAll();
    StringBuilder h = new StringBuilder("<div style='font-family:Georgia,serif;background:#e8e4dd;padding:24px;color:#2b2824'><h2>Admit " + ids.size() + " &mdash; " + esc(o.get("title")) + "</h2><p>" + esc(o.get("tier")) + " &middot; " + esc(o.get("venue")) + " &middot; " + esc(o.get("starts_at")) + "</p><p>Hello " + esc(o.get("uname")) + ", your payment was received. Present each QR code at the door.</p>");
    for (int i = 0; i < ids.size(); i++) h.append("<p><img src='cid:qr").append(i).append("' width='200' height='200'><br><small>").append(ids.get(i)).append("</small></p>");
    mailAsync((String) o.get("email"), "Your tickets: " + o.get("title"), h.append("</div>").toString(), ids);
  }
  static String esc(Object o) { return String.valueOf(o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;"); }

  static final String SALES = "SELECT e.id,e.title,e.status,e.starts_at," +
      "(SELECT COUNT(*) FROM tickets k JOIN tiers t ON t.id=k.tier_id WHERE t.event_id=e.id) sold," +
      "(SELECT COUNT(*) FROM tickets k JOIN tiers t ON t.id=k.tier_id WHERE t.event_id=e.id AND k.status='used') admitted," +
      "(SELECT COALESCE(SUM(o.amount_centavos),0) FROM orders o JOIN tiers t ON t.id=o.tier_id WHERE t.event_id=e.id AND o.status='paid') revenue " +
      "FROM events e WHERE (?=1 OR e.organizer_id=?) ORDER BY e.starts_at DESC";

  public static void main(String[] args) throws Exception {
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl("jdbc:mysql://" + e("DB_HOST", "localhost") + ":" + e("DB_PORT", "3306") + "/" + e("DB_NAME", "campus_tickets") + "?createDatabaseIfNotExist=true&serverTimezone=Asia/Manila&allowPublicKeyRetrieval=true&useSSL=false");
    hc.setUsername(e("DB_USER", "root")); hc.setPassword(e("DB_PASS", "")); hc.setMaximumPoolSize(10);
    ds = new HikariDataSource(hc);
    for (String stmt : SCHEMA.split(";")) if (!stmt.isBlank()) upd(stmt);

    Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(Integer.parseInt(e("PORT", "8080")));

    // rate limiting (HTTP 429) + auth token resolution
    app.before(c -> {
      boolean strict = c.path().matches("/api/(auth|checkout|captcha).*");
      String k = c.ip() + (strict ? "A" : "B");
      if (hits.get(k, x -> new AtomicInteger()).incrementAndGet() > (strict ? 30 : 400)) { c.status(429).json(Map.of("error", "Too many requests - slow down")); c.skipRemainingHandlers(); return; }
      String h = c.header("Authorization");
      if (h != null && h.startsWith("Bearer ")) {
        Long uid = sessions.getIfPresent(h.substring(7));
        if (uid != null) { var r = q("SELECT * FROM users WHERE id=? AND active=1", uid); if (!r.isEmpty()) c.attribute("u", r.get(0)); }
      }
    });
    app.exception(HttpResponseException.class, (ex, c) -> c.status(ex.getStatus()).json(Map.of("error", ex.getMessage())));
    app.exception(Exception.class, (ex, c) -> { ex.printStackTrace(); c.status(500).json(Map.of("error", "Server error - please try again")); });

    // ---------- auth & profile
    app.get("/api/captcha", c -> {
      Random r = new Random(); int a = 1 + r.nextInt(9), b = 1 + r.nextInt(9); String id = UUID.randomUUID().toString();
      captchas.put(id, String.valueOf(a + b)); c.json(Map.of("id", id, "question", "What is " + a + " + " + b + "?"));
    });
    app.post("/api/auth/google", c -> {
      String cred = ck(body(c), "credential", "^[A-Za-z0-9._\\-]{20,4000}$", "Invalid credential");
      var r = HC.send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/tokeninfo?id_token=" + URLEncoder.encode(cred, StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
      JsonNode j = M.readTree(r.body());
      if (r.statusCode() != 200 || !e("GOOGLE_CLIENT_ID", "-").equals(j.path("aud").asText()) || !"true".equals(j.path("email_verified").asText())) throw new UnauthorizedResponse("Google sign-in failed");
      String email = j.path("email").asText().toLowerCase(), sub = j.path("sub").asText();
      boolean adm = Arrays.asList(e("ADMIN_EMAILS", "").toLowerCase().split("\\s*,\\s*")).contains(email);
      var ex = q("SELECT * FROM users WHERE google_sub=? OR email=?", sub, email); long id;
      if (ex.isEmpty()) id = ins("INSERT INTO users(google_sub,email,name,picture,role) VALUES(?,?,?,?,?)", sub, email, j.path("name").asText(email), j.path("picture").asText(""), adm ? "admin" : "attendee");
      else { id = lng(ex.get(0), "id"); upd("UPDATE users SET google_sub=?,picture=?,active=1 WHERE id=?", sub, j.path("picture").asText(""), id); if (adm) upd("UPDATE users SET role='admin' WHERE id=?", id); }
      String tk = UUID.randomUUID().toString() + UUID.randomUUID(); sessions.put(tk, id);
      c.json(Map.of("token", tk, "user", q("SELECT * FROM users WHERE id=?", id).get(0)));
    });
    app.get("/api/me", c -> c.json(auth(c)));
    app.put("/api/me", c -> {
      var u = auth(c); var b = body(c);
      String name = ck(b, "name", "^[\\p{L} .'\\-]{2,80}$", "Enter a valid name");
      String phone = ck(b, "phone", "^(09|\\+639)\\d{9}$", "Phone must look like 09XXXXXXXXX");
      String[] f = {"regionCode", "provinceCode", "cityCode", "barangayCode"};
      for (String k : f) if (!s(b, k).matches("^\\d{0,12}$")) throw new BadRequestResponse("Invalid address code");
      for (String k : new String[]{"region", "province", "city", "barangay"}) if (!s(b, k).matches("^.{0,120}$")) throw new BadRequestResponse("Invalid address");
      upd("UPDATE users SET name=?,phone=?,region_code=?,region=?,province_code=?,province=?,city_code=?,city=?,barangay_code=?,barangay=? WHERE id=?", name, phone,
          s(b, "regionCode"), s(b, "region"), s(b, "provinceCode"), s(b, "province"), s(b, "cityCode"), s(b, "city"), s(b, "barangayCode"), s(b, "barangay"), u.get("id"));
      c.json(q("SELECT * FROM users WHERE id=?", u.get("id")).get(0));
    });
    app.delete("/api/me", c -> { var u = auth(c); upd("UPDATE users SET active=0 WHERE id=?", u.get("id")); c.json(Map.of("ok", true)); });

    // ---------- public events (cached)
    app.get("/api/events", c -> c.json(cached("events:list", () -> q("SELECT e.*,u.name organizer,(SELECT MIN(price_centavos) FROM tiers t WHERE t.event_id=e.id) min_price FROM events e JOIN users u ON u.id=e.organizer_id WHERE e.status='published' AND e.starts_at>=NOW()-INTERVAL 1 DAY ORDER BY e.starts_at"))));
    app.get("/api/events/{id}", c -> {
      long id = Long.parseLong(c.pathParam("id"));
      Map<String, Object> out = cached("event:" + id, () -> {
        var ev = q("SELECT e.*,u.name organizer FROM events e JOIN users u ON u.id=e.organizer_id WHERE e.id=? AND e.status='published'", id);
        if (ev.isEmpty()) return null;
        var tiers = q("SELECT t.*,t.capacity-COALESCE((SELECT SUM(qty) FROM orders o WHERE o.tier_id=t.id AND (o.status='paid' OR (o.status='pending' AND o.created_at>NOW()-INTERVAL 15 MINUTE))),0) remaining FROM tiers t WHERE t.event_id=? ORDER BY t.price_centavos", id);
        return Map.of("event", ev.get(0), "tiers", tiers);
      });
      if (out == null) throw new NotFoundResponse("Event not found");
      c.json(out);
    });

    // ---------- organizer: events & tiers
    app.get("/api/upload/sign", c -> {
      auth(c, "organizer", "admin");
      if (e("CLOUDINARY_API_SECRET", "").isEmpty()) throw new BadRequestResponse("Cloudinary is not configured");
      String ts = String.valueOf(System.currentTimeMillis() / 1000), folder = "campus-events";
      byte[] d = MessageDigest.getInstance("SHA-1").digest(("folder=" + folder + "&timestamp=" + ts + e("CLOUDINARY_API_SECRET", "")).getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(); for (byte x : d) sb.append(String.format("%02x", x));
      c.json(Map.of("cloudName", e("CLOUDINARY_CLOUD_NAME", ""), "apiKey", e("CLOUDINARY_API_KEY", ""), "timestamp", ts, "signature", sb.toString(), "folder", folder));
    });
    app.get("/api/org/events", c -> {
      var u = auth(c, "organizer", "admin"); int a = isAdmin(u) ? 1 : 0;
      var evs = q("SELECT * FROM events e WHERE (?=1 OR e.organizer_id=?) ORDER BY e.starts_at DESC", a, u.get("id"));
      var tiers = q("SELECT t.* FROM tiers t JOIN events e ON e.id=t.event_id WHERE (?=1 OR e.organizer_id=?)", a, u.get("id"));
      for (var ev : evs) { List<Object> l = new ArrayList<>(); for (var t : tiers) if (t.get("event_id").equals(ev.get("id"))) l.add(t); ev.put("tiers", l); }
      c.json(evs);
    });
    app.post("/api/org/events", c -> {
      var u = auth(c, "organizer", "admin"); var b = eventBody(c);
      long id = ins("INSERT INTO events(organizer_id,title,description,venue,starts_at,poster_url,category) VALUES(?,?,?,?,?,?,?)", u.get("id"), b[0], b[1], b[2], b[3], b[4], b[5]);
      cache.invalidateAll(); c.json(Map.of("id", id));
    });
    app.put("/api/org/events/{id}", c -> {
      var u = auth(c, "organizer", "admin"); long id = Long.parseLong(c.pathParam("id")); ownEvent(u, id); var b = eventBody(c);
      upd("UPDATE events SET title=?,description=?,venue=?,starts_at=?,poster_url=?,category=?,status=IF(status='rejected','pending',status) WHERE id=?", b[0], b[1], b[2], b[3], b[4], b[5], id);
      cache.invalidateAll(); c.json(Map.of("ok", true));
    });
    app.delete("/api/org/events/{id}", c -> {
      var u = auth(c, "organizer", "admin"); long id = Long.parseLong(c.pathParam("id")); ownEvent(u, id);
      upd("UPDATE events SET status='cancelled' WHERE id=?", id); cache.invalidateAll(); c.json(Map.of("ok", true));
    });
    app.post("/api/org/events/{id}/tiers", c -> {
      var u = auth(c, "organizer", "admin"); long id = Long.parseLong(c.pathParam("id")); ownEvent(u, id); var t = tierBody(c);
      long tid = ins("INSERT INTO tiers(event_id,name,price_centavos,capacity) VALUES(?,?,?,?)", id, t[0], t[1], t[2]); cache.invalidateAll(); c.json(Map.of("id", tid));
    });
    app.put("/api/org/tiers/{id}", c -> {
      var u = auth(c, "organizer", "admin"); long tid = Long.parseLong(c.pathParam("id")); ownTier(u, tid); var t = tierBody(c);
      upd("UPDATE tiers SET name=?,price_centavos=?,capacity=? WHERE id=?", t[0], t[1], t[2], tid); cache.invalidateAll(); c.json(Map.of("ok", true));
    });
    app.delete("/api/org/tiers/{id}", c -> {
      var u = auth(c, "organizer", "admin"); long tid = Long.parseLong(c.pathParam("id")); ownTier(u, tid);
      if (lng(q("SELECT COUNT(*) n FROM orders WHERE tier_id=?", tid).get(0), "n") > 0) throw new ConflictResponse("This tier already has orders and cannot be deleted");
      upd("DELETE FROM tiers WHERE id=?", tid); cache.invalidateAll(); c.json(Map.of("ok", true));
    });
    app.get("/api/org/sales", c -> { var u = auth(c, "organizer", "admin"); c.json(q(SALES, isAdmin(u) ? 1 : 0, u.get("id"))); });

    // ---------- ticket verification (QR scan)
    app.post("/api/tickets/verify", c -> {
      var u = auth(c, "organizer", "admin");
      String uuid = ck(body(c), "ticket_uuid", "^[0-9a-fA-F\\-]{36}$", "Not a valid ticket code").toLowerCase();
      var r = q("SELECT k.status,k.used_at,t.name tier,e.title,e.organizer_id,us.name holder FROM tickets k JOIN tiers t ON t.id=k.tier_id JOIN events e ON e.id=t.event_id JOIN users us ON us.id=k.owner_id WHERE k.uuid=?", uuid);
      if (r.isEmpty()) throw new NotFoundResponse("Ticket not found - INVALID");
      var k = r.get(0);
      if (!isAdmin(u) && lng(k, "organizer_id") != lng(u, "id")) throw new ForbiddenResponse("Ticket belongs to another organizer's event");
      if (upd("UPDATE tickets SET status='used',used_at=NOW() WHERE uuid=? AND status='paid'", uuid) == 0) throw new ConflictResponse("ALREADY USED at " + k.get("used_at"));
      c.json(Map.of("result", "valid", "event", k.get("title"), "tier", k.get("tier"), "holder", k.get("holder")));
    });
    app.get("/api/qr/{uuid}", c -> { String id = c.pathParam("uuid"); if (!id.matches("^[0-9a-fA-F\\-]{36}$")) throw new BadRequestResponse("Bad code"); c.contentType("image/png").result(qr(id.toLowerCase())); });

    // ---------- attendee: checkout, orders, tickets
    app.post("/api/checkout", c -> {
      var u = auth(c); var b = body(c);
      if (!capOk(s(b, "captchaId"), s(b, "captchaAnswer"))) throw new BadRequestResponse("Wrong CAPTCHA answer");
      long tid = Long.parseLong(ck(b, "tierId", "^\\d{1,10}$", "Invalid tier")); int qty = Integer.parseInt(ck(b, "qty", "^([1-9]|10)$", "Quantity must be 1 to 10"));
      synchronized (LOCK) {
        var r = q("SELECT t.*,e.title,e.status ev,t.capacity-COALESCE((SELECT SUM(qty) FROM orders o WHERE o.tier_id=t.id AND (o.status='paid' OR (o.status='pending' AND o.created_at>NOW()-INTERVAL 15 MINUTE))),0) remaining FROM tiers t JOIN events e ON e.id=t.event_id WHERE t.id=?", tid);
        if (r.isEmpty()) throw new NotFoundResponse("Tier not found");
        var t = r.get(0);
        if (!"published".equals(t.get("ev"))) throw new BadRequestResponse("This event is not open for sales");
        if (num(t, "remaining") < qty) throw new ConflictResponse("Not enough tickets left");
        int amt = num(t, "price_centavos") * qty; // PayMongo expects whole centavos
        long oid = ins("INSERT INTO orders(user_id,tier_id,qty,amount_centavos,status) VALUES(?,?,?,?,'pending')", u.get("id"), tid, qty, amt);
        cache.invalidateAll();
        if (amt == 0) { markPaid(oid); c.json(Map.of("orderId", oid)); return; }
        try {
          Map<String, Object> at = new LinkedHashMap<>();
          Map<String, Object> li = new LinkedHashMap<>(); li.put("currency", "PHP"); li.put("amount", num(t, "price_centavos")); li.put("name", t.get("title") + " - " + t.get("name")); li.put("quantity", qty);
          at.put("line_items", List.of(li)); at.put("payment_method_types", List.of("gcash", "card"));
          at.put("success_url", FRONT + "/orders/" + oid + "?paid=1"); at.put("cancel_url", FRONT + "/orders/" + oid);
          at.put("description", "Campus ticket order #" + oid); at.put("metadata", Map.of("order_id", String.valueOf(oid)));
          JsonNode d = pm("/checkout_sessions", Map.of("data", Map.of("attributes", at)));
          upd("UPDATE orders SET checkout_id=? WHERE id=?", d.path("id").asText(), oid);
          c.json(Map.of("orderId", oid, "checkoutUrl", d.path("attributes").path("checkout_url").asText()));
        } catch (Exception ex) { upd("UPDATE orders SET status='cancelled' WHERE id=?", oid); cache.invalidateAll(); throw ex; }
      }
    });
    app.post("/api/webhooks/paymongo", c -> {
      String raw = c.body(), sec = e("PAYMONGO_WEBHOOK_SECRET", "");
      if (!sec.isEmpty()) {
        Map<String, String> p = new HashMap<>(); for (String part : String.valueOf(c.header("Paymongo-Signature")).split(",")) { String[] kv = part.split("=", 2); if (kv.length == 2) p.put(kv[0].trim(), kv[1].trim()); }
        String exp = hmac(sec, p.getOrDefault("t", "") + "." + raw);
        if (!exp.equals(p.get("te")) && !exp.equals(p.get("li"))) throw new UnauthorizedResponse("Bad signature");
      }
      JsonNode a = M.readTree(raw).path("data").path("attributes");
      if ("checkout_session.payment.paid".equals(a.path("type").asText()))
        for (var o : q("SELECT id FROM orders WHERE checkout_id=?", a.path("data").path("id").asText())) markPaid(lng(o, "id"));
      c.result("ok");
    });
    app.post("/api/orders/{id}/sync", c -> { // fallback when webhook cannot reach localhost
      var u = auth(c); long id = Long.parseLong(c.pathParam("id"));
      var r = q("SELECT * FROM orders WHERE id=? AND user_id=?", id, u.get("id")); if (r.isEmpty()) throw new NotFoundResponse("Order not found");
      Object cid = r.get(0).get("checkout_id");
      if ("pending".equals(r.get(0).get("status")) && cid != null) {
        boolean paid = false; for (JsonNode p : pm("/checkout_sessions/" + cid, null).path("attributes").path("payments")) if ("paid".equals(p.path("attributes").path("status").asText())) paid = true;
        if (paid) markPaid(id);
      }
      c.json(Map.of("status", q("SELECT status FROM orders WHERE id=?", id).get(0).get("status")));
    });
    app.post("/api/orders/{id}/cancel", c -> {
      var u = auth(c); long id = Long.parseLong(c.pathParam("id"));
      if (upd("UPDATE orders SET status='cancelled' WHERE id=? AND user_id=? AND status='pending'", id, u.get("id")) == 0) throw new ConflictResponse("Only unpaid orders can be cancelled");
      cache.invalidateAll(); c.json(Map.of("ok", true));
    });
    app.get("/api/orders", c -> { var u = auth(c); c.json(q("SELECT o.id,o.qty,o.amount_centavos,o.status,o.created_at,e.title,t.name tier FROM orders o JOIN tiers t ON t.id=o.tier_id JOIN events e ON e.id=t.event_id WHERE o.user_id=? ORDER BY o.id DESC", u.get("id"))); });
    app.get("/api/orders/{id}", c -> {
      var u = auth(c); long id = Long.parseLong(c.pathParam("id"));
      var r = q("SELECT o.id,o.qty,o.amount_centavos,o.status,e.title,e.venue,e.starts_at,t.name tier FROM orders o JOIN tiers t ON t.id=o.tier_id JOIN events e ON e.id=t.event_id WHERE o.id=? AND o.user_id=?", id, u.get("id"));
      if (r.isEmpty()) throw new NotFoundResponse("Order not found");
      c.json(Map.of("order", r.get(0), "tickets", q("SELECT uuid,status FROM tickets WHERE order_id=?", id)));
    });
    app.get("/api/tickets", c -> { var u = auth(c); c.json(q("SELECT k.uuid,k.status,k.used_at,t.name tier,e.title,e.venue,e.starts_at,e.poster_url FROM tickets k JOIN tiers t ON t.id=k.tier_id JOIN events e ON e.id=t.event_id WHERE k.owner_id=? ORDER BY e.starts_at DESC", u.get("id"))); });
    app.post("/api/tickets/{uuid}/transfer", c -> { // unique feature: gift a ticket to another registered student
      var u = auth(c); String uuid = c.pathParam("uuid").toLowerCase(); String em = ck(body(c), "email", "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", "Enter a valid email").toLowerCase();
      var to = q("SELECT id FROM users WHERE email=? AND active=1", em); if (to.isEmpty()) throw new NotFoundResponse("That person has not signed in to Campus Tickets yet");
      if (upd("UPDATE tickets SET owner_id=? WHERE uuid=? AND owner_id=? AND status='paid'", to.get(0).get("id"), uuid, u.get("id")) == 0) throw new ConflictResponse("Only your unused tickets can be transferred");
      c.json(Map.of("ok", true));
    });

    // ---------- admin
    app.get("/api/admin/events", c -> { auth(c, "admin"); c.json(q("SELECT e.*,u.name organizer FROM events e JOIN users u ON u.id=e.organizer_id ORDER BY FIELD(e.status,'pending','published','rejected','cancelled'),e.starts_at")); });
    app.post("/api/admin/events/{id}/status", c -> {
      auth(c, "admin"); String st = ck(body(c), "status", "^(published|rejected|cancelled)$", "Invalid status");
      upd("UPDATE events SET status=? WHERE id=?", st, Long.parseLong(c.pathParam("id"))); cache.invalidateAll(); c.json(Map.of("ok", true));
    });
    app.get("/api/admin/users", c -> { auth(c, "admin"); c.json(q("SELECT id,email,name,role,active,created_at FROM users ORDER BY id DESC")); });
    app.put("/api/admin/users/{id}/role", c -> {
      var u = auth(c, "admin"); long id = Long.parseLong(c.pathParam("id")); String role = ck(body(c), "role", "^(attendee|organizer|admin)$", "Invalid role");
      if (id == lng(u, "id")) throw new BadRequestResponse("You cannot change your own role");
      upd("UPDATE users SET role=? WHERE id=?", role, id); c.json(Map.of("ok", true));
    });
    app.get("/api/admin/cache", c -> { auth(c, "admin"); var st = cache.stats(); c.json(Map.of("entries", cache.estimatedSize(), "keys", cache.asMap().keySet(), "hits", st.hitCount(), "misses", st.missCount(), "ttlSeconds", 60)); });
    System.out.println("Campus Tickets API ready on http://localhost:" + e("PORT", "8080"));
  }

  static String[] eventBody(Context c) {
    var b = body(c);
    String when = ck(b, "startsAt", "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$", "Pick a valid date and time").replace('T', ' ') + ":00";
    return new String[]{ck(b, "title", TXT, "Title: 3-120 letters/numbers"), ck(b, "description", "^[\\s\\S]{0,2000}$", "Description is too long"), ck(b, "venue", TXT, "Venue: 3-120 characters"),
        when, ck(b, "posterUrl", "^(https://\\S{5,480})?$", "Poster must be an https link"), ck(b, "category", "^[A-Za-z ]{3,30}$", "Category: letters only")};
  }
  static Object[] tierBody(Context c) {
    var b = body(c);
    String name = ck(b, "name", "^[\\p{L}\\p{N} \\-]{2,60}$", "Tier name: 2-60 characters");
    int cents = new BigDecimal(ck(b, "price", "^\\d+(\\.\\d{1,2})?$", "Price must be like 150 or 150.50")).movePointRight(2).intValue();
    if (cents != 0 && cents < 2000) throw new BadRequestResponse("Price must be free or at least 20.00 (PayMongo minimum)");
    int cap = Integer.parseInt(ck(b, "capacity", "^[1-9]\\d{0,4}$", "Capacity must be 1-99999"));
    return new Object[]{name, cents, cap};
  }
  static void ownTier(Map<String, Object> u, long tid) throws Exception {
    var r = q("SELECT event_id FROM tiers WHERE id=?", tid); if (r.isEmpty()) throw new NotFoundResponse("Tier not found"); ownEvent(u, lng(r.get(0), "event_id"));
  }
}
