package com.rafi.glasshome;

import android.content.Context;
import android.text.format.DateFormat;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Today's games for the teams in R.array.sports_teams, from ESPN's public (undocumented, keyless)
 * scoreboard API: one request per followed league,
 * {@code site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard?dates=yyyyMMdd}.
 * The scoreboard is used rather than the per-team endpoints because those are cached for ~5 min
 * and their nextEvent skips the game in progress. Responses are streamed with JsonReader
 * (an NFL Sunday is ~210 KB of JSON, ~20 KB gzipped) and only the needed fields are kept.
 */
final class Sports {
    static final class Game {
        final String team;      // our team's short display name from sports.xml, e.g. "Leafs"
        final String us, them;  // abbreviations, e.g. "TOR", "BOS"
        final int usScore, themScore;
        final boolean home;     // are we the home team
        final String state;     // "pre", "in" or "post"
        final String detail;    // live clock/period ("2nd 12:41"), "Final", or start time text
        final long start;       // epoch seconds
        final int color;        // our team colour (ARGB) from sports.xml

        Game(String team, String us, String them, int usScore, int themScore, boolean home,
             String state, String detail, long start, int color) {
            this.team = team;
            this.us = us;
            this.them = them;
            this.usScore = usScore;
            this.themScore = themScore;
            this.home = home;
            this.state = state;
            this.detail = detail;
            this.start = start;
            this.color = color;
        }

        @Override
        public String toString() {
            return team + " " + (home ? them + " @ " + us : us + " @ " + them) + " " + state
                    + " " + usScore + "-" + themScore + " [" + detail + "] start=" + start
                    + String.format(Locale.US, " #%08X", color);
        }
    }

    /** Followed games that are today (local time) or live now, live first; null = never fetched. */
    static volatile List<Game> games;
    /** Epoch seconds of the last successful fetch. */
    static volatile long fetchedAt;

    private static final String BASE = "https://site.api.espn.com/apis/site/v2/sports/";
    private static final int CONNECT_TIMEOUT_MS = 4000, READ_TIMEOUT_MS = 6000;
    /** Before this local hour, yesterday's scoreboard is read so late games still running show. */
    private static final int NIGHT_HOUR = 5;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);

    /** Last good result per league, reused for a league whose request fails. */
    private static final Map<String, List<Game>> lastByLeague = new HashMap<>();

    /** One sports.xml entry. */
    static final class Team {
        final String league, id, name;
        final int color;

        Team(String league, String id, String name, int color) {
            this.league = league;
            this.id = id;
            this.name = name;
            this.color = color;
        }

        boolean matches(String teamId, String abbreviation) {
            return id.equals(teamId) || id.equalsIgnoreCase(abbreviation);
        }
    }

    private Sports() {}

    /** Blocking; called on a background thread. Throws on failure (keeps previous data). */
    static void fetch(Context context) throws Exception {
        Map<String, List<Team>> leagues = parseTeams(
                context.getResources().getStringArray(R.array.sports_teams));
        boolean is24h = DateFormat.is24HourFormat(context);
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();
        // ESPN's scoreboard days are US Eastern dates; for a Toronto TV that is the local date.
        LocalDate query = now.getHour() < NIGHT_HOUR ? today.minusDays(1) : today;

        List<Game> all = new ArrayList<>();
        Exception failure = null;
        int ok = 0;
        for (Map.Entry<String, List<Team>> e : leagues.entrySet()) {
            String league = e.getKey();
            List<Game> found;
            try {
                found = request(league, e.getValue(), query, today, zone, is24h);
                synchronized (lastByLeague) {
                    lastByLeague.put(league, found);
                }
                ok++;
            } catch (Exception ex) {
                failure = ex;
                synchronized (lastByLeague) {
                    found = lastByLeague.get(league);
                }
                if (found == null) continue;
                found = stillCurrent(found, today, zone);
            }
            all.addAll(found);
        }
        if (ok == 0 && failure != null) throw failure;
        sort(all);
        games = Collections.unmodifiableList(all);
        fetchedAt = System.currentTimeMillis() / 1000;
    }

    /**
     * True if any followed game is in progress. Also true for a game due to start within 15 min
     * (or past its start but not yet reported live), so the start isn't missed by a 15 min poll.
     */
    /** "Leafs 2–1 SJ · 3rd 8:52", "Leafs vs SJ · 7:00 PM", "Leafs 3–2 SJ · Final". */
    static String describe(Game g, boolean withTeam) {
        String name = withTeam ? g.team + " " : "";
        if ("pre".equals(g.state)) return name + (g.home ? "vs " : "at ") + g.them + " · " + g.detail;
        return name + g.usScore + "–" + g.themScore + " " + g.them + " · " + g.detail;
    }

    /** The game worth showing: live first, then later today, then today's final; null if none. */
    static Game headline() {
        List<Game> list = games;
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    static boolean anyLive() {
        List<Game> list = games;
        if (list == null) return false;
        long now = System.currentTimeMillis() / 1000;
        for (Game g : list) {
            if ("in".equals(g.state)) return true;
            if ("pre".equals(g.state) && g.start - now < 15 * 60 && now - g.start < 3 * 3600) {
                return true;
            }
        }
        return false;
    }

    // ---- network ----

    private static List<Game> request(String league, List<Team> teams, LocalDate query,
                                      LocalDate today, ZoneId zone, boolean is24h)
            throws IOException {
        URL url = new URL(BASE + league + "/scoreboard?dates=" + query.format(DAY));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        // ESPN answers 403 to the JDK's default "Java/x" agent; Dalvik's works, but be explicit.
        conn.setRequestProperty("User-Agent", "GlassHome/1.0 (Android TV)");
        // Asked for explicitly so it works the same on Android and on a plain JVM.
        conn.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException(league + ": HTTP " + code);
            }
            InputStream in = new BufferedInputStream(conn.getInputStream());
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) in = new GZIPInputStream(in);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parseScoreboard(reader, league, teams, today, zone, is24h);
            }
        } finally {
            conn.disconnect();
        }
    }

    // ---- configuration ----

    /** Parses sports.xml items ("hockey/nhl|TOR|Leafs|#00205B"), grouped by league in order. */
    static Map<String, List<Team>> parseTeams(String[] items) {
        Map<String, List<Team>> leagues = new LinkedHashMap<>();
        for (String item : items) {
            String[] p = item.split("\\|");
            if (p.length < 4) continue;
            String league = p[0].trim(), id = p[1].trim(), name = p[2].trim();
            if (league.isEmpty() || id.isEmpty()) continue;
            int color;
            try {
                color = parseColor(p[3].trim());
            } catch (NumberFormatException e) {
                color = 0xFFFFFFFF;
            }
            leagues.computeIfAbsent(league, k -> new ArrayList<>())
                    .add(new Team(league, id, name.isEmpty() ? id : name, color));
        }
        return leagues;
    }

    private static int parseColor(String s) {
        if (s.startsWith("#")) s = s.substring(1);
        long v = Long.parseLong(s, 16);
        if (s.length() == 6) v |= 0xFF000000L;
        else if (s.length() != 8) throw new NumberFormatException(s);
        return (int) v;
    }

    // ---- parsing ----

    /** Fields of one scoreboard event, collected while streaming (key order isn't guaranteed). */
    private static final class Event {
        String date;
        boolean timeValid = true;
        Status status, competitionStatus;
        final List<Side> sides = new ArrayList<>();
    }

    private static final class Status {
        String name = "", state = "", shortDetail = "", displayClock = "";
        int period;
    }

    private static final class Side {
        String id = "", abbreviation = "", homeAway = "";
        int score;
    }

    /**
     * Streams one scoreboard response and returns the games of {@code teams} that start on
     * {@code today} (in {@code zone}) or are in progress. Unsorted.
     */
    static List<Game> parseScoreboard(Reader json, String league, List<Team> teams,
                                      LocalDate today, ZoneId zone, boolean is24h)
            throws IOException {
        String sport = league.substring(0, Math.max(0, league.indexOf('/')));
        List<Game> out = new ArrayList<>();
        JsonReader r = new JsonReader(json);
        r.beginObject();
        while (r.hasNext()) {
            if (r.nextName().equals("events") && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    Event e = readEvent(r);
                    if (e != null) toGames(e, sport, teams, today, zone, is24h, out);
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return out;
    }

    private static Event readEvent(JsonReader r) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return null;
        }
        Event e = new Event();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "date":
                    e.date = string(r);
                    break;
                case "status":
                    e.status = readStatus(r);
                    break;
                case "competitions":
                    readCompetitions(r, e);
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        return e;
    }

    /** Reads the first competition; any others (none in practice) are skipped. */
    private static void readCompetitions(JsonReader r, Event e) throws IOException {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue();
            return;
        }
        r.beginArray();
        boolean first = true;
        while (r.hasNext()) {
            if (!first || r.peek() != JsonToken.BEGIN_OBJECT) {
                r.skipValue();
                continue;
            }
            first = false;
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "date":
                        String d = string(r);
                        if (e.date == null) e.date = d;
                        break;
                    case "timeValid":
                        if (r.peek() == JsonToken.BOOLEAN) e.timeValid = r.nextBoolean();
                        else r.skipValue();
                        break;
                    case "status":
                        e.competitionStatus = readStatus(r);
                        break;
                    case "competitors":
                        if (r.peek() != JsonToken.BEGIN_ARRAY) {
                            r.skipValue();
                            break;
                        }
                        r.beginArray();
                        while (r.hasNext()) {
                            Side s = readSide(r);
                            if (s != null) e.sides.add(s);
                        }
                        r.endArray();
                        break;
                    default:
                        r.skipValue();
                }
            }
            r.endObject();
        }
        r.endArray();
    }

    private static Side readSide(JsonReader r) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return null;
        }
        Side s = new Side();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "homeAway":
                    s.homeAway = string(r);
                    break;
                case "score":
                    s.score = score(r);
                    break;
                case "team":
                    if (r.peek() != JsonToken.BEGIN_OBJECT) {
                        r.skipValue();
                        break;
                    }
                    r.beginObject();
                    while (r.hasNext()) {
                        String n = r.nextName();
                        if (n.equals("id")) s.id = string(r);
                        else if (n.equals("abbreviation")) s.abbreviation = string(r);
                        else r.skipValue();
                    }
                    r.endObject();
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        return s;
    }

    private static Status readStatus(JsonReader r) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return null;
        }
        Status s = new Status();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "displayClock":
                    s.displayClock = string(r);
                    break;
                case "period":
                    s.period = (int) number(r);
                    break;
                case "type":
                    if (r.peek() != JsonToken.BEGIN_OBJECT) {
                        r.skipValue();
                        break;
                    }
                    r.beginObject();
                    while (r.hasNext()) {
                        switch (r.nextName()) {
                            case "name":
                                s.name = string(r);
                                break;
                            case "state":
                                s.state = string(r);
                                break;
                            case "shortDetail":
                                s.shortDetail = string(r);
                                break;
                            default:
                                r.skipValue();
                        }
                    }
                    r.endObject();
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        return s;
    }

    /** A string, number or boolean as text; anything else (null, objects) as "". */
    private static String string(JsonReader r) throws IOException {
        switch (r.peek()) {
            case STRING:
            case NUMBER:
                return r.nextString();
            case BOOLEAN:
                return String.valueOf(r.nextBoolean());
            default:
                r.skipValue();
                return "";
        }
    }

    private static double number(JsonReader r) throws IOException {
        String s = string(r);
        try {
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Scoreboard scores are strings ("3"); other ESPN feeds use {"value": 3.0, ...}. */
    private static int score(JsonReader r) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) return (int) number(r);
        int v = 0;
        r.beginObject();
        while (r.hasNext()) {
            if (r.nextName().equals("value")) v = (int) number(r);
            else r.skipValue();
        }
        r.endObject();
        return v;
    }

    private static void toGames(Event e, String sport, List<Team> teams, LocalDate today,
                                ZoneId zone, boolean is24h, List<Game> out) {
        Status st = e.status != null ? e.status : e.competitionStatus;
        if (st == null || e.date == null || e.sides.size() != 2) return;
        long start;
        try {
            start = OffsetDateTime.parse(e.date, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toEpochSecond();
        } catch (RuntimeException ex) {
            return;
        }
        String state = st.state;
        if (!state.equals("pre") && !state.equals("in") && !state.equals("post")) return;
        boolean isToday = Instant.ofEpochSecond(start).atZone(zone).toLocalDate().equals(today);
        if (!isToday && !state.equals("in")) return;

        for (Team t : teams) {
            for (int i = 0; i < 2; i++) {
                Side us = e.sides.get(i), them = e.sides.get(1 - i);
                if (!t.matches(us.id, us.abbreviation)) continue;
                String detail = detail(sport, st, start, e.timeValid, zone, is24h);
                out.add(new Game(t.name, us.abbreviation, them.abbreviation, us.score,
                        them.score, "home".equals(us.homeAway), state, detail, start, t.color));
            }
        }
    }

    /** "2nd 12:41", "Bot 7th", "Q3 4:20", "65'", "Half", "Final", "7:05 PM", ... */
    private static String detail(String sport, Status st, long start, boolean timeValid,
                                 ZoneId zone, boolean is24h) {
        String shortDetail = st.shortDetail == null ? "" : st.shortDetail.trim();
        String name = st.name == null ? "" : st.name;
        switch (st.state) {
            case "pre":
                if (!name.equals("STATUS_SCHEDULED") && !shortDetail.isEmpty()
                        && !shortDetail.equals("Scheduled") && !shortDetail.contains(" - ")) {
                    return shortDetail;  // "Delayed", "Postponed", ...
                }
                if (!timeValid) return "TBD";
                return DateTimeFormatter.ofPattern(is24h ? "H:mm" : "h:mm a", Locale.getDefault())
                        .format(Instant.ofEpochSecond(start).atZone(zone));
            case "post":
                if (shortDetail.isEmpty() || shortDetail.equals("FT")) return "Final";
                return shortDetail;  // "Final", "Final/OT", "Final/10", "Postponed", ...
            default:
                return live(sport, name, shortDetail, st.displayClock, st.period);
        }
    }

    private static String live(String sport, String name, String shortDetail, String clock,
                               int period) {
        boolean running = name.equals("STATUS_IN_PROGRESS") || name.equals("STATUS_OVERTIME")
                || name.equals("STATUS_FIRST_HALF") || name.equals("STATUS_SECOND_HALF");
        if (clock == null) clock = "";
        switch (sport) {
            case "hockey":
                boolean shootout = shortDetail.toUpperCase(Locale.US).contains("SHOOTOUT");
                if (running && period > 0 && !shootout) {
                    return join(period <= 3 ? ordinal(period) : overtime(period - 3), clock);
                }
                break;
            case "basketball":
            case "football":
                if (running && period > 0) {
                    return join(period <= 4 ? "Q" + period : overtime(period - 4), clock);
                }
                break;
            case "soccer":
                if (name.equals("STATUS_HALFTIME")) return "HT";
                if (running && clock.endsWith("'")) return clock;
                break;
            default:
                break;  // baseball: shortDetail is already "Top 6th", "Bot 7th", "Mid 3rd"
        }
        if (name.equals("STATUS_HALFTIME")) return "Half";
        if (shortDetail.startsWith("End of ")) return "End " + shortDetail.substring(7);
        return shortDetail.isEmpty() ? "Live" : shortDetail;
    }

    private static String join(String period, String clock) {
        return clock.isEmpty() ? period : period + " " + clock;
    }

    private static String overtime(int n) {
        return n <= 1 ? "OT" : n + "OT";
    }

    private static String ordinal(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) return n + "th";
        switch (n % 10) {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }

    /** Previous games of a league that failed this time, if they still belong on screen. */
    private static List<Game> stillCurrent(List<Game> previous, LocalDate today, ZoneId zone) {
        List<Game> out = new ArrayList<>();
        for (Game g : previous) {
            if (g.state.equals("in")
                    || Instant.ofEpochSecond(g.start).atZone(zone).toLocalDate().equals(today)) {
                out.add(g);
            }
        }
        return out;
    }

    /** Live games first, then upcoming, then finished; by start time within each. */
    static void sort(List<Game> list) {
        list.sort((a, b) -> {
            int c = Integer.compare(rank(a.state), rank(b.state));
            return c != 0 ? c : Long.compare(a.start, b.start);
        });
    }

    private static int rank(String state) {
        return state.equals("in") ? 0 : state.equals("pre") ? 1 : 2;
    }
}
