package com.rafi.glasshome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.JsonReader;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live data for the screensaver: subway, bus, Citi Bike and weather.
 * Everything runs on one low-priority background thread that exists only while the
 * screensaver is showing. Each request has short timeouts, a failure keeps the last
 * good data, and results reach the UI through the main-thread listener.
 */
final class Feeds {
    interface Listener {
        void onFeedsUpdated();
    }

    /** Arrival times as epoch seconds, soonest first. */
    static final class Arrivals {
        final long[] uptown, downtown;
        final long fetchedAt;

        Arrivals(long[] uptown, long[] downtown, long fetchedAt) {
            this.uptown = uptown;
            this.downtown = downtown;
            this.fetchedAt = fetchedAt;
        }
    }

    static final class Station {
        final int bikes, ebikes;
        final boolean renting;

        Station(int bikes, int ebikes, boolean renting) {
            this.bikes = bikes;
            this.ebikes = ebikes;
            this.renting = renting;
        }
    }

    static final class Weather {
        final double temp, high, low;
        final int code, cloudCover;
        final boolean isDay;
        final double windKmh, windFromDeg, snowDepthM;
        final long sunrise, sunset, fetchedAt; // epoch seconds

        Weather(double temp, double high, double low, int code, int cloudCover, boolean isDay,
                double windKmh, double windFromDeg, double snowDepthM, long sunrise, long sunset, long fetchedAt) {
            this.temp = temp;
            this.high = high;
            this.low = low;
            this.code = code;
            this.cloudCover = cloudCover;
            this.isDay = isDay;
            this.windKmh = windKmh;
            this.windFromDeg = windFromDeg;
            this.snowDepthM = snowDepthM;
            this.sunrise = sunrise;
            this.sunset = sunset;
            this.fetchedAt = fetchedAt;
        }
    }

    /** Health of one data source, shown in the About panel. */
    static final class Status {
        volatile long lastAttempt, lastOk; // epoch seconds
        volatile long lastMillis;          // how long the last attempt took
        volatile String lastError;         // null when the last attempt worked
    }

    static final java.util.Map<String, Status> STATUS = new java.util.LinkedHashMap<>();

    static {
        for (String name : new String[]{"Weather", "Subway", "Buses", "Citi Bike", "Sports"}) STATUS.put(name, new Status());
    }

    // Last good data, kept between screensaver sessions so it opens with something on screen.
    static volatile Arrivals subway, bus, busSbs, busThird;
    static volatile Station[] bikes;
    static volatile Weather weather;

    private static final String SUBWAY_FEED = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct%2Fgtfs";
    private static final String BIKE_FEED = "https://gbfs.lyft.com/gbfs/1.1/bkn/en/station_status.json";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService exec;
    private Listener listener;

    Feeds(Context context) {
        this.context = context.getApplicationContext();
    }

    void start(Listener l) {
        listener = l;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "glasshome-feeds");
            t.setDaemon(true);
            return t;
        });
        Weather w = weather;
        long weatherDelay = w != null && now() - w.fetchedAt < 15 * 60 ? 15 * 60 - (now() - w.fetchedAt) : 0;
        every("Weather", this::fetchWeather, weatherDelay, 15 * 60);
        every("Subway", this::fetchSubway, 0, 30);
        every("Buses", this::fetchBus, 0, 30);
        every("Citi Bike", this::fetchBikes, 0, 120);
        // Sports: checked every minute, but only fetched every minute while a followed game is
        // live (or about to start); otherwise every 15 minutes.
        exec.scheduleWithFixedDelay(() -> {
            boolean due = Sports.games == null || Sports.anyLive() || now() - Sports.fetchedAt >= 15 * 60;
            if (due) runTracked("Sports", this::fetchSports);
        }, 0, 60, TimeUnit.SECONDS);
    }

    void stop() {
        listener = null;
        if (exec != null) exec.shutdownNow();
        exec = null;
    }

    /** Fixed delay, so a slow request never stacks up; a failed run never cancels the next one. */
    private void every(String name, Runnable task, long initialSec, long periodSec) {
        exec.scheduleWithFixedDelay(() -> runTracked(name, task), initialSec, periodSec, TimeUnit.SECONDS);
    }

    private static void runTracked(String name, Runnable task) {
        Status st = STATUS.get(name);
        long start = System.currentTimeMillis();
        st.lastAttempt = start / 1000;
        try {
            task.run();
            st.lastOk = now();
            st.lastError = null;
        } catch (Throwable t) {
            // Keep the last good data; try again next period.
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            if (root instanceof java.io.InterruptedIOException || root instanceof InterruptedException) {
                return; // stopped on purpose (screen went away); not a failure
            }
            st.lastError = root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
            android.util.Log.w("GlassHome", name + " feed failed", t);
        } finally {
            st.lastMillis = System.currentTimeMillis() - start;
        }
    }

    private void notifyUi() {
        main.post(() -> {
            if (listener != null) listener.onFeedsUpdated();
        });
    }

    static long now() {
        return System.currentTimeMillis() / 1000;
    }

    /** Data older than three minutes isn't shown. */
    static boolean fresh(Arrivals a, long now) {
        return a != null && now - a.fetchedAt < 180;
    }

    /** Minutes until the first departure you can still walk to, or -1 if none in the next hour. */
    static int nextCatchable(long[] times, int walkMin, long now) {
        for (long t : times) {
            int min = (int) ((t - now) / 60);
            if (min >= walkMin && min <= 60) return min;
        }
        return -1;
    }

    // ---------------------------------------------------------------- subway (GTFS-realtime protobuf)

    private void fetchSubway() {
        String stop = context.getString(R.string.subway_stop_id);
        byte[] data;
        try (InputStream in = open(SUBWAY_FEED)) {
            data = readAll(in);
        } catch (IOException e) {
            throw new RuntimeException("subway", e);
        }
        subway = parseGtfsRt(data, stop + "N", stop + "S");
        notifyUi();
    }

    /**
     * Minimal GTFS-realtime reader: FeedMessage.entity(2) > trip_update(3) >
     * stop_time_update(2) > { stop_id(4), arrival(2)|departure(3) > time(2) }.
     * Avoids pulling in the protobuf runtime for four fields.
     */
    static Arrivals parseGtfsRt(byte[] data, String upStop, String downStop) {
        long now = now();
        List<Long> up = new ArrayList<>(), down = new ArrayList<>();
        Pb msg = new Pb(data, 0, data.length);
        while (msg.more()) {
            if (msg.next() != 2) { msg.skip(); continue; }
            Pb entity = msg.sub();
            while (entity.more()) {
                if (entity.next() != 3) { entity.skip(); continue; }
                Pb tripUpdate = entity.sub();
                while (tripUpdate.more()) {
                    if (tripUpdate.next() != 2) { tripUpdate.skip(); continue; }
                    Pb stu = tripUpdate.sub();
                    String stop = null;
                    long time = 0;
                    while (stu.more()) {
                        int f = stu.next();
                        if (f == 4) {
                            stop = stu.string();
                        } else if ((f == 2 || f == 3) && time == 0) {
                            Pb event = stu.sub();
                            while (event.more()) {
                                if (event.next() == 2) time = event.varint();
                                else event.skip();
                            }
                        } else {
                            stu.skip();
                        }
                    }
                    if (stop == null || time < now - 30) continue;
                    if (stop.equals(upStop)) up.add(time);
                    else if (stop.equals(downStop)) down.add(time);
                }
            }
        }
        return new Arrivals(soonest(up), soonest(down), now);
    }

    private static long[] soonest(List<Long> times) {
        long[] a = new long[times.size()];
        for (int i = 0; i < a.length; i++) a[i] = times.get(i);
        Arrays.sort(a);
        return Arrays.copyOf(a, Math.min(a.length, 8));
    }

    private static final class Pb {
        private final byte[] b;
        private final int end;
        private int pos, tag;

        Pb(byte[] b, int start, int end) {
            this.b = b;
            this.pos = start;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int next() {
            tag = (int) varint();
            return tag >>> 3;
        }

        long varint() {
            long r = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                byte x = b[pos++];
                r |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) break;
            }
            return r;
        }

        Pb sub() {
            int n = (int) varint();
            Pb p = new Pb(b, pos, pos + n);
            pos += n;
            return p;
        }

        String string() {
            int n = (int) varint();
            String s = new String(b, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        void skip() {
            switch (tag & 7) {
                case 0: varint(); break;
                case 1: pos += 8; break;
                case 2: {
                    // Not "pos += varint()": Java reads pos before varint() advances it.
                    int n = (int) varint();
                    pos += n;
                    break;
                }
                case 5: pos += 4; break;
                default: throw new IllegalStateException("wire type " + (tag & 7));
            }
        }
    }

    // ---------------------------------------------------------------- bus

    /**
     * MTA Bus Time. With a key (set over ADB, see README) this uses the official SIRI API;
     * without one it uses the endpoint the Bus Time website itself calls, which needs no key
     * but is undocumented. Both return the same SIRI StopMonitoring structure.
     */
    private void fetchBus() {
        // Local and SBS stop separately; one failing doesn't hold up the other.
        RuntimeException failure = null;
        try {
            bus = fetchBusStop(context.getString(R.string.bus_stop_id),
                    new String[]{context.getString(R.string.bus_line_ref)}, false);
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            busSbs = fetchBusStop(context.getString(R.string.bus_sbs_stop_id),
                    new String[]{context.getString(R.string.bus_sbs_line_ref)}, false);
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            busThird = fetchBusStop(context.getString(R.string.bus3_stop_id),
                    context.getResources().getStringArray(R.array.bus3_line_refs), true);
        } catch (RuntimeException e) {
            failure = e;
        }
        notifyUi();
        if (failure != null) throw failure;
    }

    /** Arrivals at one stop for any of the given lines, merged and sorted. */
    private Arrivals fetchBusStop(String stop, String[] lines, boolean uptown) {
        List<String> wanted = Arrays.asList(lines);
        String key = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
                .getString(ConfigReceiver.KEY_BUS_KEY, "");
        String url;
        try {
            url = key.isEmpty()
                    ? "https://bustime.mta.info/api/stop-for-id?stopId=" + stop
                    : "https://bustime.mta.info/api/siri/stop-monitoring.json?key=" + key
                            + "&MonitoringRef=" + stop
                            + (lines.length == 1 ? "&LineRef=" + java.net.URLEncoder.encode(lines[0], "UTF-8") : "");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        List<Long> times = new ArrayList<>();
        try (InputStream in = open(url)) {
            JSONObject root = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8).trim());
            JSONObject siri = root.has("siri") ? root.getJSONObject("siri").getJSONObject("Siri") : root.getJSONObject("Siri");
            org.json.JSONArray visits = siri.getJSONObject("ServiceDelivery")
                    .getJSONArray("StopMonitoringDelivery").getJSONObject(0)
                    .optJSONArray("MonitoredStopVisit");
            long now = now();
            for (int i = 0; visits != null && i < visits.length(); i++) {
                JSONObject journey = visits.getJSONObject(i).getJSONObject("MonitoredVehicleJourney");
                if (!wanted.contains(journey.optString("LineRef"))) continue; // stops are shared with other routes
                String expected = journey.getJSONObject("MonitoredCall").optString("ExpectedArrivalTime", "");
                if (expected.isEmpty()) continue; // bus not yet on its way; no prediction
                long t = java.time.OffsetDateTime.parse(expected).toEpochSecond();
                if (t >= now - 30) times.add(t);
            }
        } catch (Exception e) {
            throw new RuntimeException("bus " + stop, e);
        }
        // 2nd and 3rd Ave are one-way, so each stop only has one direction.
        long[] sorted = soonest(times);
        return uptown ? new Arrivals(sorted, new long[0], now()) : new Arrivals(new long[0], sorted, now());
    }

    // ---------------------------------------------------------------- sports (ESPN, see Sports.java)

    private void fetchSports() {
        try {
            Sports.fetch(context);
        } catch (Exception e) {
            throw new RuntimeException("sports", e);
        }
        notifyUi();
    }

    // ---------------------------------------------------------------- Citi Bike (GBFS)

    /** The feed is ~1 MB for every station in the city, so it's streamed and only our stations are kept. */
    private void fetchBikes() {
        String[] ids = context.getResources().getStringArray(R.array.bike_station_ids);
        Station[] found = new Station[ids.length];
        try (InputStream in = open(BIKE_FEED);
             JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                if (!r.nextName().equals("data")) { r.skipValue(); continue; }
                r.beginObject();
                while (r.hasNext()) {
                    if (!r.nextName().equals("stations")) { r.skipValue(); continue; }
                    r.beginArray();
                    while (r.hasNext()) readStation(r, ids, found);
                    r.endArray();
                }
                r.endObject();
            }
        } catch (IOException | IllegalStateException e) {
            throw new RuntimeException("bikes", e);
        }
        bikes = found;
        notifyUi();
    }

    private static void readStation(JsonReader r, String[] ids, Station[] out) throws IOException {
        String id = null;
        int bikes = 0, ebikes = 0;
        boolean renting = true;
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "station_id": id = r.nextString(); break;
                case "num_bikes_available": bikes = r.nextInt(); break;
                case "num_ebikes_available": ebikes = r.nextInt(); break;
                case "is_renting": renting = r.nextInt() == 1; break;
                default: r.skipValue();
            }
        }
        r.endObject();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(id)) out[i] = new Station(bikes, ebikes, renting);
        }
    }

    // ---------------------------------------------------------------- weather (Open-Meteo, no key)

    private void fetchWeather() {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + context.getString(R.string.weather_lat)
                + "&longitude=" + context.getString(R.string.weather_lon)
                + "&current=temperature_2m,weather_code,is_day,cloud_cover,wind_speed_10m,wind_direction_10m,snow_depth"
                + "&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min"
                + "&temperature_unit=fahrenheit" // Celsius is computed for display
                + "&timeformat=unixtime&timezone=auto&forecast_days=1";
        try (InputStream in = open(url)) {
            JSONObject o = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            JSONObject cur = o.getJSONObject("current");
            JSONObject day = o.getJSONObject("daily");
            weather = new Weather(
                    cur.getDouble("temperature_2m"),
                    day.getJSONArray("temperature_2m_max").getDouble(0),
                    day.getJSONArray("temperature_2m_min").getDouble(0),
                    cur.getInt("weather_code"),
                    cur.optInt("cloud_cover", 0),
                    cur.optInt("is_day", 1) == 1,
                    cur.optDouble("wind_speed_10m", 0),
                    cur.optDouble("wind_direction_10m", 0),
                    cur.optDouble("snow_depth", 0),
                    day.getJSONArray("sunrise").getLong(0),
                    day.getJSONArray("sunset").getLong(0),
                    now());
        } catch (Exception e) {
            throw new RuntimeException("weather", e);
        }
        notifyUi();
    }

    // ---------------------------------------------------------------- http

    static InputStream open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "GlassHome/1.0 (Android TV screensaver)");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return new BufferedInputStream(c.getInputStream(), 16 * 1024);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
