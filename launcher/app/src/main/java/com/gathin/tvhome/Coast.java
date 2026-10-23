package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quiberon weather and tides for the home widget.
 *
 * <p>Weather is Open-Meteo (no key), the same source as the CleanYourTV weather card: DWD ICON, with rain
 * rewritten as snow when the wet-bulb temperature is at or below 1 °C. Tides are the public high/low and
 * water-level calls that maree.shom.fr itself publishes for Port-Maria. Times come back in French legal time,
 * which matches a TV set to Europe/Paris.
 */
final class Coast {
    private static final long REFRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final int TIMEOUT_MS = 8_000;
    /** Shown on the free SHOM tide pages; it is not a paid prediction key. */
    private static final String SHOM = "https://services.data.shom.fr/b2q8lrcdl4s04cbabsj4nhcb/hdm/spm/";
    private static final String PREFS = "coast";

    private final Context app;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "coast");
        thread.setDaemon(true);
        return thread;
    });

    private View root;
    private TideCurveView curve;
    private TextView place, temp, sky, detail;
    private TextView today, todayHi, todayLo, tomorrow, tomorrowHi, tomorrowLo;
    private TextView tideLabel, harbor, coeff, nextKind, nextTime, nextHeight, nextWhen, thenLine;

    private Snapshot snapshot;
    private long fetchedAt;
    private long lastAttempt;
    private int generation;
    private boolean running;

    Coast(Context context) {
        app = context.getApplicationContext();
    }

    void attach(View root) {
        this.root = root;
        place = root.findViewById(R.id.coastPlace);
        temp = root.findViewById(R.id.coastTemp);
        sky = root.findViewById(R.id.coastSky);
        detail = root.findViewById(R.id.coastDetail);
        today = root.findViewById(R.id.coastToday);
        todayHi = root.findViewById(R.id.coastTodayHi);
        todayLo = root.findViewById(R.id.coastTodayLo);
        tomorrow = root.findViewById(R.id.coastTomorrow);
        tomorrowHi = root.findViewById(R.id.coastTomorrowHi);
        tomorrowLo = root.findViewById(R.id.coastTomorrowLo);
        tideLabel = root.findViewById(R.id.coastTideLabel);
        harbor = root.findViewById(R.id.coastHarbor);
        coeff = root.findViewById(R.id.coastCoeff);
        curve = root.findViewById(R.id.coastCurve);
        nextKind = root.findViewById(R.id.coastNextKind);
        nextTime = root.findViewById(R.id.coastNextTime);
        nextHeight = root.findViewById(R.id.coastNextHeight);
        nextWhen = root.findViewById(R.id.coastNextWhen);
        thenLine = root.findViewById(R.id.coastThen);
        place.setText(app.getString(R.string.coast_place));
        tideLabel.setText(R.string.coast_tides);
        harbor.setText(R.string.coast_harbor_label);
        today.setText(R.string.coast_today);
        tomorrow.setText(R.string.coast_tomorrow);
        loadCache();
        bind();
    }

    void close() {
        generation++;
        running = false;
        root = null;
        executor.shutdownNow();
    }

    /** Fetch when the cached reading is older than half an hour, or missing and the last try was a while ago. */
    void refresh() {
        if (root == null || running) return;
        long now = System.currentTimeMillis();
        if (fetchedAt > 0 && now - fetchedAt < REFRESH_MS) return;
        if (lastAttempt > 0 && now - lastAttempt < RETRY_MS) return;
        running = true;
        lastAttempt = now;
        final int gen = generation;
        final String harborName = app.getString(R.string.coast_harbor_name);
        final double latitude = Double.parseDouble(app.getString(R.string.coast_lat));
        final double longitude = Double.parseDouble(app.getString(R.string.coast_lon));
        final String day = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        executor.execute(() -> {
            String weatherJson = null, hltJson = null, wlJson = null;
            Weather weather = snapshot == null ? null : snapshot.weather;
            Tides tides = snapshot == null ? null : snapshot.tides;
            boolean weatherOk = false, tideOk = false;
            try {
                weatherJson = get(weatherUrl(latitude, longitude), false);
                weather = parseWeather(weatherJson);
                weatherOk = true;
            } catch (Exception ignored) {
            }
            try {
                String q = "harborName=" + harborName + "&duration=2&date=" + day + "&utc=standard";
                hltJson = get(SHOM + "hlt?" + q + "&correlation=1", true);
                wlJson = get(SHOM + "wl?" + q + "&nbWaterLevels=96", true);
                tides = parseTides(hltJson, wlJson);
                tideOk = true;
            } catch (Exception ignored) {
            }
            Weather w = weather;
            Tides t = tides;
            boolean both = weatherOk && tideOk;
            String wj = weatherJson, hj = hltJson, lj = wlJson;
            if (root == null) return;
            root.post(() -> {
                if (gen != generation || root == null) return;
                running = false;
                if (w != null || t != null) {
                    if (snapshot == null) snapshot = new Snapshot(null, null);
                    snapshot = new Snapshot(w != null ? w : snapshot.weather, t != null ? t : snapshot.tides);
                    if (both) fetchedAt = System.currentTimeMillis();
                    saveCache(wj, hj, lj);
                    bind();
                }
            });
        });
    }

    /** Rewrites "dans 2 h" as the clock moves. The network reading stays put. */
    void tick() {
        if (snapshot != null) bind();
        refresh();
    }

    private void bind() {
        if (root == null) return;
        long now = System.currentTimeMillis();
        Weather weather = snapshot == null ? null : snapshot.weather;
        Tides tides = snapshot == null ? null : snapshot.tides;
        int dayVis = weather == null ? View.INVISIBLE : View.VISIBLE;
        today.setVisibility(dayVis);
        todayHi.setVisibility(dayVis);
        todayLo.setVisibility(dayVis);
        tomorrow.setVisibility(dayVis);
        tomorrowHi.setVisibility(dayVis);
        tomorrowLo.setVisibility(dayVis);
        if (weather == null) {
            temp.setText(R.string.coast_dash);
            sky.setText(R.string.coast_weather_unavailable);
            detail.setText("");
        } else {
            temp.setText(app.getString(R.string.coast_temp, weather.tempC));
            sky.setText(skyRes(weather.code, weather.day));
            detail.setText(app.getString(R.string.coast_detail,
                    app.getString(R.string.coast_feels, weather.feelsC),
                    app.getString(R.string.coast_wind, weather.windKmh, windPoint(weather.windDeg))));
            bindDay(todayHi, todayLo, weather.todayHi, weather.todayLo);
            bindDay(tomorrowHi, tomorrowLo, weather.tomorrowHi, weather.tomorrowLo);
        }
        if (tides == null || tides.extremes.isEmpty()) {
            coeff.setVisibility(View.GONE);
            curve.setSeries(null, null, now);
            nextKind.setText("");
            nextTime.setText(R.string.coast_dash);
            nextHeight.setText("");
            nextWhen.setText(R.string.coast_tides_unavailable);
            thenLine.setText("");
        } else {
            Extreme next = null, after = null, nextHigh = null;
            for (Extreme extreme : tides.extremes) {
                if (extreme.when < now - 10 * 60_000L) continue;
                if (next == null) next = extreme;
                else if (after == null) after = extreme;
                if (nextHigh == null && extreme.high && extreme.coeff >= 0) nextHigh = extreme;
            }
            if (next == null) next = tides.extremes.get(tides.extremes.size() - 1);
            // On the ebb, keep the coefficient of the high just passed. The next high's coefficient
            // takes over once that high is the coming event.
            Extreme shownCoeff = next.high && next.coeff >= 0 ? next : null;
            if (shownCoeff == null) {
                for (int i = tides.extremes.size() - 1; i >= 0; i--) {
                    Extreme earlier = tides.extremes.get(i);
                    if (earlier.when <= next.when && earlier.high && earlier.coeff >= 0) {
                        shownCoeff = earlier;
                        break;
                    }
                }
            }
            if (shownCoeff == null) shownCoeff = nextHigh;
            nextKind.setText(next.high ? R.string.coast_high : R.string.coast_low);
            nextKind.setTextColor(next.high ? 0xFFB7E4F2 : 0xFF9BB0BA);
            nextTime.setText(DateTimeFormatter.ofPattern("HH:mm").format(
                    java.time.Instant.ofEpochMilli(next.when).atZone(ZoneId.systemDefault())));
            nextHeight.setText(meters(next.height));
            nextWhen.setText(when(next.when, now));
            if (shownCoeff != null) {
                coeff.setVisibility(View.VISIBLE);
                coeff.setText(app.getString(R.string.coast_coeff, shownCoeff.coeff));
                coeff.setTextColor(shownCoeff.coeff >= 70 ? 0xFFF2D7A2 : 0xFFE8EEF2);
            } else {
                coeff.setVisibility(View.GONE);
            }
            if (after != null) {
                thenLine.setText(app.getString(R.string.coast_then,
                        app.getString(after.high ? R.string.coast_high : R.string.coast_low).toLowerCase(Locale.getDefault()),
                        DateTimeFormatter.ofPattern("HH:mm").format(
                                java.time.Instant.ofEpochMilli(after.when).atZone(ZoneId.systemDefault())),
                        meters(after.height)));
            } else {
                thenLine.setText("");
            }
            curve.setSeries(tides.levelTimes, tides.levelHeights, now);
        }
        root.setContentDescription(summary(weather, tides, now));
    }

    private void bindDay(TextView hi, TextView lo, int high, int low) {
        if (high == Integer.MIN_VALUE) {
            hi.setText("");
            lo.setText("");
            return;
        }
        hi.setText(app.getString(R.string.coast_temp, high));
        lo.setText(app.getString(R.string.coast_temp, low));
    }

    private String summary(Weather weather, Tides tides, long now) {
        StringBuilder out = new StringBuilder(app.getString(R.string.coast_place));
        if (weather != null) {
            out.append(", ").append(weather.tempC).append("°, ").append(app.getString(skyRes(weather.code, weather.day)));
        }
        if (tides != null) {
            for (Extreme extreme : tides.extremes) {
                if (extreme.when < now - 10 * 60_000L) continue;
                out.append(". ").append(app.getString(extreme.high ? R.string.coast_high : R.string.coast_low))
                        .append(' ').append(DateTimeFormatter.ofPattern("HH:mm").format(
                                java.time.Instant.ofEpochMilli(extreme.when).atZone(ZoneId.systemDefault())));
                if (extreme.coeff >= 0) out.append(", ").append(app.getString(R.string.coast_coeff, extreme.coeff));
                break;
            }
        }
        return out.toString();
    }

    private String when(long event, long now) {
        long mins = Math.round((event - now) / 60_000.0);
        if (mins <= 0) return app.getString(R.string.coast_now);
        if (mins < 60) return app.getString(R.string.coast_in_min, (int) mins);
        int hours = (int) (mins / 60);
        int rest = (int) (mins % 60);
        if (rest == 0) return app.getString(R.string.coast_in_hours, hours);
        return app.getString(R.string.coast_in_hours_min, hours, rest);
    }

    private String meters(double height) {
        return String.format(Locale.getDefault(), "%.1f m", height);
    }

    private String windPoint(int degrees) {
        String[] points = app.getResources().getStringArray(R.array.wind_points);
        int index = Math.floorMod(Math.round(degrees / 22.5f), points.length);
        return points[index];
    }

    private int skyRes(int code, boolean day) {
        switch (code) {
            case 0: return day ? R.string.sky_sunny : R.string.sky_clear;
            case 1: return day ? R.string.sky_mostly_sunny : R.string.sky_mostly_clear;
            case 2: return R.string.sky_partly_cloudy;
            case 3: return R.string.sky_cloudy;
            case 45: case 48: return R.string.sky_fog;
            case 51: case 53: case 55: return R.string.sky_drizzle;
            case 56: case 57: return R.string.sky_freezing_drizzle;
            case 61: return R.string.sky_light_rain;
            case 63: return R.string.sky_rain;
            case 65: return R.string.sky_heavy_rain;
            case 66: case 67: return R.string.sky_freezing_rain;
            case 71: return R.string.sky_light_snow;
            case 73: return R.string.sky_snow;
            case 75: return R.string.sky_heavy_snow;
            case 77: return R.string.sky_snow_grains;
            case 80: case 81: case 82: return R.string.sky_showers;
            case 85: case 86: return R.string.sky_snow_showers;
            default: return code >= 95 ? R.string.sky_thunderstorm : R.string.sky_cloudy;
        }
    }

    private void loadCache() {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        fetchedAt = prefs.getLong("at", 0);
        Weather weather = null;
        Tides tides = null;
        try {
            String raw = prefs.getString("weather", null);
            if (raw != null) weather = parseWeather(raw);
        } catch (Exception ignored) {
            fetchedAt = 0;
        }
        try {
            String hlt = prefs.getString("hlt", null);
            String wl = prefs.getString("wl", null);
            if (hlt != null && wl != null) tides = parseTides(hlt, wl);
        } catch (Exception ignored) {
            fetchedAt = 0;
        }
        if (weather != null || tides != null) snapshot = new Snapshot(weather, tides);
    }

    private void saveCache(String weather, String hlt, String wl) {
        SharedPreferences.Editor editor = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (weather != null) editor.putString("weather", weather);
        if (hlt != null) editor.putString("hlt", hlt);
        if (wl != null) editor.putString("wl", wl);
        if (fetchedAt > 0) editor.putLong("at", fetchedAt);
        editor.apply();
    }

    private static String weatherUrl(double latitude, double longitude) {
        return String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&models=icon_seamless"
                        + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,is_day,"
                        + "wind_speed_10m,wind_direction_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=3",
                latitude, longitude);
    }

    private static String get(String url, boolean shom) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "tvhome");
        if (shom) connection.setRequestProperty("Referer", "https://maree.shom.fr/");
        try {
            int code = connection.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (in == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            in.close();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /** Rain and drizzle become snow of the same strength when the wet-bulb temperature is at or below 1 °C. */
    static int snowIfCold(int code, double tempC, double humidity) {
        if (wetBulb(tempC, humidity) > 1.0) return code;
        switch (code) {
            case 51: case 53: case 61: case 80: return 71;
            case 55: case 63: case 81: return 73;
            case 65: case 82: return 75;
            default: return code;
        }
    }

    /** Stull (2011), air temperature in °C and relative humidity in percent. */
    static double wetBulb(double t, double rh) {
        return t * Math.atan(0.151977 * Math.sqrt(rh + 8.313659)) + Math.atan(t + rh) - Math.atan(rh - 1.676331)
                + 0.00391838 * Math.pow(rh, 1.5) * Math.atan(0.023101 * rh) - 4.686035;
    }

    static Weather parseWeather(String json) throws JSONException {
        JSONObject reply = new JSONObject(json);
        JSONObject current = reply.getJSONObject("current");
        double temp = current.getDouble("temperature_2m");
        double humidity = current.optDouble("relative_humidity_2m", 100);
        int code = snowIfCold(current.getInt("weather_code"), temp, humidity);
        JSONObject daily = reply.getJSONObject("daily");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");
        int todayHi = Integer.MIN_VALUE, todayLo = Integer.MIN_VALUE;
        int tomorrowHi = Integer.MIN_VALUE, tomorrowLo = Integer.MIN_VALUE;
        if (max.length() > 0 && !max.isNull(0) && !min.isNull(0)) {
            todayHi = (int) Math.round(max.getDouble(0));
            todayLo = (int) Math.round(min.getDouble(0));
        }
        if (max.length() > 1 && !max.isNull(1) && !min.isNull(1)) {
            tomorrowHi = (int) Math.round(max.getDouble(1));
            tomorrowLo = (int) Math.round(min.getDouble(1));
        }
        return new Weather((int) Math.round(temp), (int) Math.round(current.optDouble("apparent_temperature", temp)),
                code, current.optInt("is_day", 1) == 1,
                (int) Math.round(current.optDouble("wind_speed_10m", 0)),
                (int) Math.round(current.optDouble("wind_direction_10m", 0)),
                todayHi, todayLo, tomorrowHi, tomorrowLo);
    }

    static Tides parseTides(String hltJson, String wlJson) throws JSONException {
        List<Extreme> extremes = new ArrayList<>();
        JSONObject hlt = new JSONObject(hltJson);
        JSONArray days = hlt.names();
        if (days != null) {
            for (int d = 0; d < days.length(); d++) {
                String date = days.getString(d);
                JSONArray events = hlt.optJSONArray(date);
                if (events == null) continue;
                LocalDate day;
                try {
                    day = LocalDate.parse(date);
                } catch (Exception e) {
                    continue;
                }
                for (int i = 0; i < events.length(); i++) {
                    JSONArray event = events.getJSONArray(i);
                    boolean high = "tide.high".equals(event.optString(0));
                    LocalTime time = LocalTime.parse(event.getString(1));
                    long when = day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    double height = event.getDouble(2);
                    int coeff = -1;
                    String raw = event.optString(3, "");
                    if (!raw.isEmpty() && !raw.contains("-")) {
                        try {
                            coeff = Integer.parseInt(raw);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    extremes.add(new Extreme(high, when, height, coeff));
                }
            }
        }
        Collections.sort(extremes, (a, b) -> Long.compare(a.when, b.when));

        List<Long> levelTimes = new ArrayList<>();
        List<Float> levelHeights = new ArrayList<>();
        JSONObject wl = new JSONObject(wlJson);
        JSONArray levelDays = wl.names();
        if (levelDays != null) {
            List<String> ordered = new ArrayList<>();
            for (int d = 0; d < levelDays.length(); d++) ordered.add(levelDays.getString(d));
            Collections.sort(ordered);
            for (String date : ordered) {
                JSONArray rows = wl.optJSONArray(date);
                if (rows == null) continue;
                LocalDate day;
                try {
                    day = LocalDate.parse(date);
                } catch (Exception e) {
                    continue;
                }
                for (int i = 0; i < rows.length(); i++) {
                    JSONArray row = rows.getJSONArray(i);
                    LocalTime time = LocalTime.parse(row.getString(0));
                    levelTimes.add(day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                    levelHeights.add((float) row.getDouble(1));
                }
            }
        }
        long[] times = new long[levelTimes.size()];
        float[] heights = new float[levelHeights.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = levelTimes.get(i);
            heights[i] = levelHeights.get(i);
        }
        return new Tides(extremes, times, heights);
    }

    static final class Weather {
        final int tempC, feelsC, code, windKmh, windDeg;
        final boolean day;
        final int todayHi, todayLo, tomorrowHi, tomorrowLo;

        Weather(int tempC, int feelsC, int code, boolean day, int windKmh, int windDeg,
                int todayHi, int todayLo, int tomorrowHi, int tomorrowLo) {
            this.tempC = tempC;
            this.feelsC = feelsC;
            this.code = code;
            this.day = day;
            this.windKmh = windKmh;
            this.windDeg = windDeg;
            this.todayHi = todayHi;
            this.todayLo = todayLo;
            this.tomorrowHi = tomorrowHi;
            this.tomorrowLo = tomorrowLo;
        }
    }

    static final class Extreme {
        final boolean high;
        final long when;
        final double height;
        final int coeff;

        Extreme(boolean high, long when, double height, int coeff) {
            this.high = high;
            this.when = when;
            this.height = height;
            this.coeff = coeff;
        }
    }

    static final class Tides {
        final List<Extreme> extremes;
        final long[] levelTimes;
        final float[] levelHeights;

        Tides(List<Extreme> extremes, long[] levelTimes, float[] levelHeights) {
            this.extremes = extremes;
            this.levelTimes = levelTimes;
            this.levelHeights = levelHeights;
        }
    }

    private static final class Snapshot {
        final Weather weather;
        final Tides tides;

        Snapshot(Weather weather, Tides tides) {
            this.weather = weather;
            this.tides = tides;
        }
    }
}
