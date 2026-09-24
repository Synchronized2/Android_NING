package cloud.pcie.openaiq;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

final class WeatherClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final Context context;

    WeatherClient(Context context) {
        this.context = context.getApplicationContext();
    }

    String query(String location, int requestedDays) throws Exception {
        String cleanLocation = location == null ? "" : location.trim();
        if (cleanLocation.isEmpty()) {
            throw new Exception("请提供要查询的城市或地区");
        }
        int days = Math.max(1, Math.min(7, requestedDays));
        JSONObject place = geocode(cleanLocation);
        double latitude = place.getDouble("latitude");
        double longitude = place.getDouble("longitude");
        String timezone = place.optString("timezone", "auto");
        return query(place, latitude, longitude, timezone, days);
    }

    String query(double latitude, double longitude, int requestedDays) throws Exception {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new Exception("定位坐标无效");
        }
        int days = Math.max(1, Math.min(7, requestedDays));
        JSONObject place = reverseGeocode(latitude, longitude);
        return query(place, latitude, longitude, "auto", days);
    }

    private String query(
            JSONObject place,
            double latitude,
            double longitude,
            String timezone,
            int days) throws Exception {
        JSONObject forecast = getJson(
                "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + latitude
                        + "&longitude=" + longitude
                        + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,"
                        + "precipitation,weather_code,wind_speed_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                        + "precipitation_probability_max"
                        + "&forecast_days=" + days
                        + "&timezone=" + encode(timezone));
        return format(place, forecast, days);
    }

    @SuppressWarnings("deprecation")
    private JSONObject reverseGeocode(double latitude, double longitude) {
        JSONObject place = new JSONObject();
        try {
            if (!Geocoder.isPresent()) {
                return place.put("name", "当前位置");
            }
            List<Address> addresses = new Geocoder(context, Locale.CHINA)
                    .getFromLocation(latitude, longitude, 1);
            if (addresses == null || addresses.isEmpty()) {
                return place.put("name", "当前位置");
            }
            Address address = addresses.get(0);
            String name = firstNonEmpty(
                    address.getLocality(),
                    address.getSubAdminArea(),
                    address.getAdminArea(),
                    address.getFeatureName(),
                    "当前位置");
            place.put("name", name);
            place.put("admin1", address.getAdminArea() == null ? "" : address.getAdminArea());
            place.put("country", address.getCountryName() == null ? "" : address.getCountryName());
            return place;
        } catch (Exception ignored) {
            JSONObject fallback = new JSONObject();
            try {
                fallback.put("name", "当前位置");
            } catch (Exception impossible) {
                // A constant string cannot fail JSON serialization.
            }
            return fallback;
        }
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "当前位置";
    }

    private JSONObject geocode(String location) throws Exception {
        JSONObject response = getJson(
                "https://geocoding-api.open-meteo.com/v1/search?name="
                        + encode(location)
                        + "&count=1&language=zh&format=json");
        JSONArray results = response.optJSONArray("results");
        if (results == null || results.length() == 0 || results.optJSONObject(0) == null) {
            throw new Exception("没有找到“" + location + "”，请尝试输入城市全名");
        }
        return results.getJSONObject(0);
    }

    private JSONObject getJson(String endpoint) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URI(endpoint).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "NING-Android/1.0");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new Exception("天气服务返回 HTTP " + status);
            }
            return new JSONObject(readLimited(connection.getInputStream()));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readLimited(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new Exception("天气服务响应过大");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String format(JSONObject place, JSONObject forecast, int requestedDays) {
        String name = place.optString("name", "所选地区");
        String admin = place.optString("admin1", "");
        String country = place.optString("country", "");
        StringBuilder title = new StringBuilder(name);
        if (!admin.isEmpty() && !admin.equals(name)) {
            title.append("，").append(admin);
        }
        if (!country.isEmpty()) {
            title.append("，").append(country);
        }

        JSONObject current = forecast.optJSONObject("current");
        JSONObject daily = forecast.optJSONObject("daily");
        StringBuilder result = new StringBuilder();
        result.append(title).append("的实时天气：");
        if (current != null) {
            result.append(weatherName(current.optInt("weather_code", -1)))
                    .append("，").append(number(current, "temperature_2m")).append("°C")
                    .append("，体感 ").append(number(current, "apparent_temperature")).append("°C")
                    .append("，湿度 ").append(integer(current, "relative_humidity_2m")).append("%")
                    .append("，风速 ").append(number(current, "wind_speed_10m")).append(" km/h")
                    .append("，当前降水 ").append(number(current, "precipitation")).append(" mm。");
        } else {
            result.append("暂时没有当前观测数据。");
        }

        int maxRain = 0;
        double highest = Double.NaN;
        double lowest = Double.NaN;
        if (daily != null) {
            JSONArray dates = daily.optJSONArray("time");
            JSONArray codes = daily.optJSONArray("weather_code");
            JSONArray highs = daily.optJSONArray("temperature_2m_max");
            JSONArray lows = daily.optJSONArray("temperature_2m_min");
            JSONArray rain = daily.optJSONArray("precipitation_probability_max");
            int count = dates == null ? 0 : Math.min(requestedDays, dates.length());
            if (count > 0) {
                result.append("\n未来").append(count).append("天：");
                for (int index = 0; index < count; index++) {
                    double high = value(highs, index);
                    double low = value(lows, index);
                    int rainChance = intValue(rain, index);
                    highest = Double.isNaN(highest) ? high : Math.max(highest, high);
                    lowest = Double.isNaN(lowest) ? low : Math.min(lowest, low);
                    maxRain = Math.max(maxRain, rainChance);
                    result.append("\n")
                            .append(index == 0 ? "今天" : index == 1 ? "明天" : shortDate(dates.optString(index)))
                            .append("：")
                            .append(weatherName(codes == null ? -1 : codes.optInt(index, -1)))
                            .append("，").append(formatNumber(low)).append("～")
                            .append(formatNumber(high)).append("°C")
                            .append("，降水概率 ").append(rainChance).append("%。");
                }
            }
        }
        String advice = advice(maxRain, lowest, highest);
        if (!advice.isEmpty()) {
            result.append("\n出行建议：").append(advice);
        }
        result.append("\n数据来源：Open-Meteo。");
        return result.toString();
    }

    private String advice(int maxRain, double low, double high) {
        StringBuilder advice = new StringBuilder();
        if (maxRain >= 50) {
            advice.append("降水概率较高，建议带伞");
        }
        if (!Double.isNaN(high) && high >= 30) {
            if (advice.length() > 0) advice.append("；");
            advice.append("气温较高，注意防晒补水");
        } else if (!Double.isNaN(low) && low <= 10) {
            if (advice.length() > 0) advice.append("；");
            advice.append("早晚偏凉，注意保暖");
        }
        if (advice.length() > 0) advice.append("。");
        return advice.toString();
    }

    private String weatherName(int code) {
        if (code == 0) return "晴";
        if (code >= 1 && code <= 3) {
            return code == 1 ? "大部晴朗" : code == 2 ? "局部多云" : "阴到多云";
        }
        if (code == 45 || code == 48) return "雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if (code >= 61 && code <= 67) return "雨";
        if (code >= 71 && code <= 77) return "雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code >= 85 && code <= 86) return "阵雪";
        if (code >= 95) return "雷暴";
        return "天气状况未知";
    }

    private String number(JSONObject object, String key) {
        return formatNumber(object.optDouble(key, Double.NaN));
    }

    private String integer(JSONObject object, String key) {
        return object.has(key) ? String.valueOf(object.optInt(key)) : "--";
    }

    private double value(JSONArray values, int index) {
        return values == null ? Double.NaN : values.optDouble(index, Double.NaN);
    }

    private int intValue(JSONArray values, int index) {
        return values == null ? 0 : values.optInt(index, 0);
    }

    private String formatNumber(double value) {
        return Double.isNaN(value) ? "--" : String.format(Locale.CHINA, "%.1f", value);
    }

    private String shortDate(String date) {
        return date != null && date.length() >= 10
                ? date.substring(5, 7) + "月" + date.substring(8, 10) + "日"
                : date;
    }

    private String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }
}
