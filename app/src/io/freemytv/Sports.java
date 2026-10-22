package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Sports tab: Formula 1 (Jolpica, the Ergast API's successor: next Grand Prix, last podium, standings) and the NBA
 * and NFL (ESPN's public scoreboards: live scores, upcoming games, recent results). Neither needs a key. Cached so the
 * tab opens with something; F1 every 3 hours, the leagues every 30 minutes, every minute while a game is on. Only
 * fetched while the home screen is showing with the tab on.
 */
final class Sports {
    static final int PRE = 0, LIVE = 1, FINAL = 2;

    static final class Game {
        String away, home, awayScore = "", homeScore = "", awayLogo, homeLogo, detail = "";
        int state;
        long at;
        boolean awayWon, homeWon;
    }

    static final class F1 {
        String race = "", place = "", circuit = "", lastRace = "";
        long at;            // 0: no race left this season
        final List<String[]> podium = new ArrayList<>();      // {position, code, team}
        final List<String[]> standings = new ArrayList<>();   // {position, name, points}
    }

    interface Listener { void onSports(); }

    private static final String F1_API = "https://api.jolpi.ca/ergast/f1/current/";
    private static final String ESPN = "https://site.api.espn.com/apis/site/v2/sports/";
    private static final long F1_EVERY = 3 * 3600_000L, LEAGUE_EVERY = 30 * 60_000L, LIVE_EVERY = 60_000L;
    private static final long RETRY = 10 * 60_000L;

    private static Sports instance;

    static synchronized Sports get(Context c) {
        if (instance == null) instance = new Sports(c.getApplicationContext());
        return instance;
    }

    volatile F1 f1 = new F1();
    volatile List<Game> nba = new ArrayList<>(), nfl = new ArrayList<>();
    volatile long f1At, nbaAt, nflAt;
    /** Bumped on every change: the screen redraws its cards when it moves. */
    volatile int version;

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Bitmap> logos = new HashMap<>();
    private final Set<String> logoPending = new HashSet<>();
    private volatile boolean fetching;
    private volatile long failedAt;

    private Sports(Context c) {
        cache = c.getSharedPreferences("sports", Context.MODE_PRIVATE);
        try {
            String s = cache.getString("f1", null);
            if (s != null) f1 = parseF1(new JSONObject(s));
            s = cache.getString("nba", null);
            if (s != null) nba = parseGames(new JSONArray(s));
            s = cache.getString("nfl", null);
            if (s != null) nfl = parseGames(new JSONArray(s));
            f1At = cache.getLong("f1At", 0);
            nbaAt = cache.getLong("nbaAt", 0);
            nflAt = cache.getLong("nflAt", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** A game is on (or should have started): the leagues refresh every minute. */
    private static boolean live(List<Game> games, long now) {
        for (Game g : games) if (g.state == LIVE || (g.state == PRE && g.at <= now && now - g.at < 5 * 3600_000L)) return true;
        return false;
    }

    void refreshIfStale() {
        if (!Prefs.sports() || fetching) return;
        long now = System.currentTimeMillis();
        long sinceFail = now - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < RETRY) return;
        boolean f = stale(f1At, F1_EVERY, now) || (f1.at != 0 && now > f1.at + 3 * 3600_000L);
        boolean b = stale(nbaAt, live(nba, now) ? LIVE_EVERY : LEAGUE_EVERY, now);
        boolean n = stale(nflAt, live(nfl, now) ? LIVE_EVERY : LEAGUE_EVERY, now);
        if (!f && !b && !n) return;
        fetching = true;
        io.execute(() -> {
            boolean failed = false;
            try {
                SharedPreferences.Editor e = cache.edit();
                if (f) {
                    try {
                        JSONObject raw = new JSONObject()
                                .put("next", get(F1_API + "next.json"))
                                .put("last", get(F1_API + "last/results.json"))
                                .put("standings", get(F1_API + "driverStandings.json"));
                        f1 = parseF1(raw);
                        f1At = System.currentTimeMillis();
                        e.putString("f1", raw.toString()).putLong("f1At", f1At);
                    } catch (Exception ex) {
                        failed = true;
                        AppLog.w("formula 1: " + ex.getMessage());
                    }
                }
                if (b) {
                    try {
                        // US dates: yesterday's results and today's games (the NBA plays at night in the US).
                        JSONArray events = new JSONArray();
                        for (int day = -1; day <= 0; day++) {
                            JSONArray list = get(ESPN + "basketball/nba/scoreboard?dates=" + usDate(day)).optJSONArray("events");
                            for (int i = 0; list != null && i < list.length(); i++) events.put(list.get(i));
                        }
                        JSONArray games = games(events);
                        nba = parseGames(games);
                        nbaAt = System.currentTimeMillis();
                        e.putString("nba", games.toString()).putLong("nbaAt", nbaAt);
                    } catch (Exception ex) {
                        failed = true;
                        AppLog.w("nba: " + ex.getMessage());
                    }
                }
                if (n) {
                    try {
                        JSONArray games = games(get(ESPN + "football/nfl/scoreboard").optJSONArray("events"));
                        nfl = parseGames(games);
                        nflAt = System.currentTimeMillis();
                        e.putString("nfl", games.toString()).putLong("nflAt", nflAt);
                    } catch (Exception ex) {
                        failed = true;
                        AppLog.w("nfl: " + ex.getMessage());
                    }
                }
                e.apply();
            } finally {
                failedAt = failed ? System.currentTimeMillis() : 0;
                fetching = false;
                version++;
                notifyListeners();
            }
        });
    }

    private static boolean stale(long at, long every, long now) {
        return at == 0 || now - at > every || now < at;
    }

    /** The day `offset` days from today in New York, as ESPN wants it ("20261022"). */
    private static String usDate(int offset) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d%02d%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    // MARK: - Parsing (what's kept is small and saved as is)

    private static F1 parseF1(JSONObject raw) {
        F1 out = new F1();
        JSONObject race = first(raw.optJSONObject("next"), "RaceTable", "Races");
        if (race != null) {
            out.race = race.optString("raceName");
            JSONObject circuit = race.optJSONObject("Circuit");
            if (circuit != null) {
                out.circuit = circuit.optString("circuitName");
                JSONObject loc = circuit.optJSONObject("Location");
                if (loc != null) out.place = loc.optString("locality") + ", " + loc.optString("country");
            }
            String time = race.optString("time");
            out.at = time(race.optString("date") + "T" + (time.isEmpty() ? "12:00:00Z" : time));
        }
        JSONObject last = first(raw.optJSONObject("last"), "RaceTable", "Races");
        if (last != null) {
            out.lastRace = last.optString("raceName");
            JSONArray results = last.optJSONArray("Results");
            for (int i = 0; results != null && i < results.length() && i < 3; i++) {
                JSONObject r = results.optJSONObject(i);
                JSONObject d = r.optJSONObject("Driver"), t = r.optJSONObject("Constructor");
                String name = d == null ? "" : d.optString("familyName");
                out.podium.add(new String[] {r.optString("position"), name, t == null ? "" : t.optString("name")});
            }
        }
        JSONObject list = first(raw.optJSONObject("standings"), "StandingsTable", "StandingsLists");
        JSONArray drivers = list == null ? null : list.optJSONArray("DriverStandings");
        for (int i = 0; drivers != null && i < drivers.length() && i < 3; i++) {
            JSONObject r = drivers.optJSONObject(i);
            JSONObject d = r.optJSONObject("Driver");
            out.standings.add(new String[] {r.optString("position"), d == null ? "" : d.optString("familyName"), r.optString("points")});
        }
        return out;
    }

    private static JSONObject first(JSONObject o, String table, String list) {
        JSONObject md = o == null ? null : o.optJSONObject("MRData");
        JSONObject t = md == null ? null : md.optJSONObject(table);
        JSONArray a = t == null ? null : t.optJSONArray(list);
        return a == null || a.length() == 0 ? null : a.optJSONObject(0);
    }

    /** ESPN's events, reduced to what the card shows. */
    private static JSONArray games(JSONArray events) throws org.json.JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            JSONArray comps = e == null ? null : e.optJSONArray("competitions");
            JSONObject comp = comps == null || comps.length() == 0 ? null : comps.optJSONObject(0);
            if (comp == null) continue;
            JSONObject type = comp.optJSONObject("status") == null ? null : comp.optJSONObject("status").optJSONObject("type");
            JSONObject g = new JSONObject().put("at", e.optString("date"))
                    .put("state", type == null ? "pre" : type.optString("state"))
                    .put("detail", type == null ? "" : type.optString("shortDetail"));
            JSONArray teams = comp.optJSONArray("competitors");
            for (int j = 0; teams != null && j < teams.length(); j++) {
                JSONObject t = teams.optJSONObject(j);
                JSONObject team = t.optJSONObject("team");
                String side = "home".equals(t.optString("homeAway")) ? "home" : "away";
                g.put(side, team == null ? "" : team.optString("abbreviation"))
                        .put(side + "Score", t.optString("score"))
                        .put(side + "Logo", team == null ? "" : team.optString("logo"))
                        .put(side + "Won", t.optBoolean("winner"));
            }
            out.put(g);
        }
        return out;
    }

    /** Live first, then the next ones (soonest first), then the latest results. */
    private static List<Game> parseGames(JSONArray a) {
        List<Game> live = new ArrayList<>(), next = new ArrayList<>(), done = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Game g = new Game();
            g.at = time(o.optString("at"));
            String state = o.optString("state");
            g.state = "in".equals(state) ? LIVE : "post".equals(state) ? FINAL : PRE;
            g.detail = o.optString("detail");
            g.away = o.optString("away");
            g.home = o.optString("home");
            g.awayScore = o.optString("awayScore");
            g.homeScore = o.optString("homeScore");
            g.awayLogo = o.optString("awayLogo");
            g.homeLogo = o.optString("homeLogo");
            g.awayWon = o.optBoolean("awayWon");
            g.homeWon = o.optBoolean("homeWon");
            (g.state == LIVE ? live : g.state == FINAL ? done : next).add(g);
        }
        next.sort((x, y) -> Long.compare(x.at, y.at));
        done.sort((x, y) -> Long.compare(y.at, x.at));
        List<Game> out = new ArrayList<>(live);
        out.addAll(next);
        out.addAll(done);
        return out;
    }

    /** "2026-10-25T20:00:00Z" or "2026-10-22T23:00Z" → epoch ms (0 if unreadable). */
    private static long time(String s) {
        for (String pattern : new String[] {"yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mmX"}) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.parse(s).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    // MARK: - Team logos (small, from ESPN's resizer), loaded in the background

    /** The team's logo at 64 px, or null: loads it and notifies when it's ready (a failed one isn't tried again). */
    Bitmap logo(String url) {
        if (url == null || url.isEmpty()) return null;
        synchronized (logos) {
            Bitmap b = logos.get(url);
            if (b != null || !logoPending.add(url)) return b;
        }
        io.execute(() -> {
            try {
                String path = url.replaceFirst("^https?://a\\.espncdn\\.com", "");
                String small = path.equals(url) ? url : "https://a.espncdn.com/combiner/i?img=" + path + "&h=64&w=64";
                HttpURLConnection c = (HttpURLConnection) new URL(small).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                Bitmap b;
                try (InputStream in = c.getInputStream()) {
                    b = BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
                if (b == null) return;
                // Kept as plain pixels (not GPU): on Android 9 to 11 the cards are drawn into a bitmap first.
                synchronized (logos) {
                    logos.put(url, b);
                }
                version++;
                notifyListeners();
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("team logo: " + e.getMessage());
            }
        });
        return null;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onSports(); });
    }

    private static JSONObject get(String url) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            try (InputStream in = c.getInputStream()) {
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        } finally {
            c.disconnect();
        }
    }
}
