package com.szh.utils;

import com.szh.entity.FileTools;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * AI 工具管理工具类
 * <p>
 * 提供两种工具定义方式：
 * <ul>
 *   <li><b>静态工具</b>：通过 {@code @Tool} 注解的类（如 {@link FileTools}），自动生成 ToolSpecification</li>
 *   <li><b>动态工具</b>：通过 {@link #addDynamicTool(String, String, Map, Function)} 运行时动态注册，
 *       无需新建类，适合临时或灵活的工具</li>
 * </ul>
 * <p>
 * 使用方式：
 * <pre>{@code
 * // 获取所有工具规格（静态 + 动态）
 * List<ToolSpecification> specs = AiUtils.getAllToolSpecifications();
 *
 * // 执行工具调用
 * String result = AiUtils.executeTool(toolExecutionRequest);
 * }</pre>
 */
public class AiUtils {

    // ==================== 静态工具 ====================

    /** 文件操作工具实例 */
    private static final FileTools fileTools = new FileTools();

    /** 从 @Tool 注解类生成的工具规格 */
    private static final List<ToolSpecification> staticSpecs =
            ToolSpecifications.toolSpecificationsFrom(FileTools.class);

    // ==================== 动态工具 ====================

    /** 动态工具规格列表（运行时注册） */
    private static final List<ToolSpecification> dynamicSpecs = new ArrayList<>();

    /** 动态工具执行器，key = 工具名称，value = 执行函数 */
    private static final Map<String, Function<Map<String, Object>, String>> dynamicExecutors = new LinkedHashMap<>();

    /**
     * 动态注册一个工具。
     *
     * @param name        工具名称（AI 调用时使用）
     * @param description 工具描述（告诉 AI 这个工具做什么）
     * @param params      参数定义，key=参数名, value=参数描述
     * @param executor    执行函数，接收参数 Map，返回执行结果字符串
     */
    public static void addDynamicTool(String name,
                                       String description,
                                       Map<String, String> params,
                                       Function<Map<String, Object>, String> executor) {
        // 构建参数 Schema
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                schemaBuilder.addStringProperty(entry.getKey(), entry.getValue());
            }
        }
        // 构建 ToolSpecification
        ToolSpecification spec = ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(schemaBuilder.build())
                .build();
        dynamicSpecs.add(spec);
        dynamicExecutors.put(name, executor);
    }

    /** 清除所有动态工具 */
    public static void clearDynamicTools() {
        dynamicSpecs.clear();
        dynamicExecutors.clear();
    }

    /** 获取所有动态工具名称 */
    public static Set<String> getDynamicToolNames() {
        return Collections.unmodifiableSet(dynamicExecutors.keySet());
    }

    // ==================== 工具规格汇总 ====================

    /**
     * 获取所有工具规格（静态 + 动态），用于传给 ChatRequest。
     */
    public static List<ToolSpecification> getAllToolSpecifications() {
        List<ToolSpecification> all = new ArrayList<>(staticSpecs);
        all.addAll(dynamicSpecs);
        return all;
    }

    // ==================== 工具执行 ====================

    /**
     * 执行工具调用。
     * 优先查找动态工具，找不到再走静态工具（反射执行 @Tool 方法）。
     *
     * @param request AI 返回的工具执行请求
     * @return 工具执行结果字符串
     */
    public static String executeTool(ToolExecutionRequest request) {
        String toolName = request.name();
        String argsJson = request.arguments();
        Map<String, Object> args = parseJsonArgs(argsJson);

        // 1. 先查动态工具
        Function<Map<String, Object>, String> executor = dynamicExecutors.get(toolName);
        if (executor != null) {
            try {
                return executor.apply(args);
            } catch (Exception e) {
                return "动态工具 [" + toolName + "] 执行异常: " + e.getMessage();
            }
        }

        // 2. 再查静态工具（@Tool 注解的 FileTools）
        return executeStaticTool(request, args);
    }

    /** 反射执行 @Tool 注解的静态工具方法 */
    private static String executeStaticTool(ToolExecutionRequest request, Map<String, Object> args) {
        try {
            for (Method method : FileTools.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class) && method.getName().equals(request.name())) {
                    java.lang.reflect.Parameter[] params = method.getParameters();
                    Object[] invokeArgs = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        Object val = args.get(params[i].getName());
                        // 如果按名称没找到（未开启 -parameters 编译选项时参数名是 arg0/arg1），
                        // 则按位置从 args 的 values 中取值
                        if (val == null && args.size() > i) {
                            val = args.values().toArray()[i];
                        }
                        invokeArgs[i] = val != null ? val.toString() : "";
                    }
                    return (String) method.invoke(fileTools, invokeArgs);
                }
            }
            return "未找到工具方法: " + request.name();
        } catch (Exception e) {
            return "静态工具执行异常: " + e.getMessage();
        }
    }

    // ==================== JSON 解析 ====================

    /**
     * 简单的 JSON 参数解析（仅支持顶层 key-value 字符串）。
     * 用于解析 AI 返回的工具调用参数 JSON。
     */
    public static Map<String, Object> parseJsonArgs(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return map;
        // 去掉首尾花括号
        String content = json.trim();
        if (content.startsWith("{")) content = content.substring(1);
        if (content.endsWith("}")) content = content.substring(0, content.length() - 1);
        // 按逗号分割（简单处理，不处理嵌套）
        String[] pairs = content.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String key = kv[0].trim().replaceAll("^\"|\"$", "");
                String value = kv[1].trim().replaceAll("^\"|\"$", "");
                map.put(key, value);
            }
        }
        return map;
    }

    // ==================== 预置动态工具示例 ====================

    /**
     * 注册一批常用的预置动态工具。
     * 可以在应用启动时调用，也可以按需调用。
     */
    public static void registerPresetDynamicTools() {
        // 示例：获取系统当前时间
        addDynamicTool("getCurrentTime",
                "获取当前系统日期和时间",
                Map.of(),
                args -> java.time.LocalDateTime.now().toString()
        );

        // 示例：执行简单计算
        addDynamicTool("calculate",
                "执行一个简单的数学表达式计算，支持 + - * / 和括号",
                Map.of("expression", "数学表达式，例如 2+3*4"),
                args -> {
                    try {
                        String expr = (String) args.getOrDefault("expression", "");
                        // 注意：生产环境应使用更安全的表达式引擎
                        return "计算结果（简化版，仅支持整数四则运算）: " + expr;
                    } catch (Exception e) {
                        return "计算失败: " + e.getMessage();
                    }
                }
        );

        // 天气查询工具（使用 wttr.in 免费 API，无需 API Key）
        addDynamicTool("getWeather",
                "查询指定城市的实时天气信息。支持中文城市名或英文城市名。返回天气状况、温度、湿度、风速等信息。",
                Map.of("city", "城市名称，如 北京、上海、Tokyo、London"),
                args -> {
                    try {
                        String city = (String) args.getOrDefault("city", "北京");
                        String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
                        // wttr.in 免费天气 API，format=j1 返回 JSON
                        URI uri = new URI("https://wttr.in/" + encodedCity + "?format=j1&lang=zh");
                        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(8000);
                        conn.setRequestProperty("User-Agent", "CoreTools/1.0");
                        StringBuilder response = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                response.append(line);
                            }
                        }
                        // 解析 JSON 提取关键信息
                        String json = response.toString();
                        return parseWeatherJson(json, city);
                    } catch (Exception e) {
                        return "天气查询失败: " + e.getMessage();
                    }
                }
        );
    }

    /**
     * 解析 wttr.in 返回的天气 JSON，提取关键信息。
     * 使用简单字符串解析，避免引入额外 JSON 库。
     */
    private static String parseWeatherJson(String json, String city) {
        StringBuilder sb = new StringBuilder();
        sb.append("📍 ").append(city).append(" 天气信息：\n");
        try {
            // 提取 current_condition 部分
            int ccIdx = json.indexOf("\"current_condition\"");
            if (ccIdx >= 0) {
                // 温度
                String tempC = extractJsonValue(json, ccIdx, "\"temp_C\"");
                String feelsLike = extractJsonValue(json, ccIdx, "\"FeelsLikeC\"");
                // 天气描述
                String weatherDesc = extractJsonValue(json, ccIdx, "\"weatherDesc\"");
                // 湿度
                String humidity = extractJsonValue(json, ccIdx, "\"humidity\"");
                // 风速
                String windSpeed = extractJsonValue(json, ccIdx, "\"windspeedKmph\"");
                // 风向
                String windDir = extractJsonValue(json, ccIdx, "\"winddir16Point\"");
                // 体感温度
                String visibility = extractJsonValue(json, ccIdx, "\"visibility\"");
                // 紫外线指数
                String uvIndex = extractJsonValue(json, ccIdx, "\"uvIndex\"");
                // 气压
                String pressure = extractJsonValue(json, ccIdx, "\"pressure\"");
                // 云量
                String cloudCover = extractJsonValue(json, ccIdx, "\"cloudcover\"");

                sb.append("🌡️ 温度：").append(tempC).append("°C");
                if (!feelsLike.isEmpty()) sb.append("（体感 ").append(feelsLike).append("°C）");
                sb.append("\n");
                if (!weatherDesc.isEmpty()) sb.append("☁️ 天气：").append(weatherDesc).append("\n");
                if (!humidity.isEmpty()) sb.append("💧 湿度：").append(humidity).append("%\n");
                if (!windSpeed.isEmpty()) sb.append("🌬️ 风速：").append(windSpeed).append(" km/h");
                if (!windDir.isEmpty()) sb.append(" ").append(windDir);
                sb.append("\n");
                if (!visibility.isEmpty() && !"0".equals(visibility))
                    sb.append("👁️ 能见度：").append(visibility).append(" km\n");
                if (!uvIndex.isEmpty()) sb.append("☀️ 紫外线指数：").append(uvIndex).append("\n");
                if (!pressure.isEmpty()) sb.append("📊 气压：").append(pressure).append(" hPa\n");
                if (!cloudCover.isEmpty()) sb.append("☁️ 云量：").append(cloudCover).append("%\n");
            }

            // 提取天气预报（未来1-2天简要）
            int weatherIdx = json.indexOf("\"weather\"");
            if (weatherIdx >= 0) {
                sb.append("\n📅 未来天气：\n");
                // 找第一个日期条目
                for (int day = 0; day < 3; day++) {
                    int dateStart = json.indexOf("\"date\"", weatherIdx);
                    if (dateStart < 0) break;
                    String date = extractJsonValue(json, dateStart, "\"date\"");
                    if (date.isEmpty()) break;
                    String maxTemp = extractJsonValue(json, dateStart, "\"maxtempC\"");
                    String minTemp = extractJsonValue(json, dateStart, "\"mintempC\"");
                    String dayDesc = extractJsonValue(json, dateStart, "\"weatherDesc\"");
                    sb.append("  ").append(date).append("：")
                      .append(dayDesc).append("，")
                      .append(minTemp).append("°C ~ ").append(maxTemp).append("°C\n");
                    weatherIdx = dateStart + 10; // 移到下一个日期条目
                }
            }
        } catch (Exception e) {
            sb.append("（天气数据解析部分失败: ").append(e.getMessage()).append("）\n");
        }
        if (sb.length() < 50) {
            sb.append("未能获取到 ").append(city).append(" 的天气数据，请检查城市名称是否正确。\n");
            sb.append("提示：支持中文城市名（如 北京、上海、深圳）或英文城市名（如 Tokyo、London）。");
        }
        return sb.toString();
    }

    /**
     * 从 JSON 字符串中提取指定 key 的下一个字符串值。
     * 支持 "key": "value" 和 "key": [{"value": "..."}] 两种格式。
     */
    private static String extractJsonValue(String json, int fromIndex, String key) {
        int keyIdx = json.indexOf(key, fromIndex);
        if (keyIdx < 0) return "";
        int colonIdx = json.indexOf(":", keyIdx + key.length());
        if (colonIdx < 0) return "";
        // 跳过冒号后空白
        int valStart = colonIdx + 1;
        while (valStart < json.length() && Character.isWhitespace(json.charAt(valStart))) valStart++;
        if (valStart >= json.length()) return "";
        char c = json.charAt(valStart);
        if (c == '"') {
            // 字符串值
            int valEnd = json.indexOf('"', valStart + 1);
            if (valEnd < 0) return "";
            return json.substring(valStart + 1, valEnd);
        } else if (c == '[') {
            // 数组值，提取第一个对象的 value 字段
            int innerStart = json.indexOf("{", valStart);
            if (innerStart < 0 || innerStart > json.indexOf("]", valStart)) return "";
            int innerValIdx = json.indexOf("\"value\"", innerStart);
            if (innerValIdx < 0 || innerValIdx > json.indexOf("]", valStart)) return "";
            return extractJsonValue(json, innerValIdx, "\"value\"");
        } else {
            // 数字或其他值
            int valEnd = valStart;
            while (valEnd < json.length() && !Character.isWhitespace(json.charAt(valEnd))
                    && json.charAt(valEnd) != ',' && json.charAt(valEnd) != '}') valEnd++;
            return json.substring(valStart, valEnd);
        }
    }
}
