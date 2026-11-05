package com.ops.deploy.project;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置文件可用性校验（纯函数，无状态）：校验通过返回 null，不通过返回人类可读告警文案。
 *
 * <p>按 fileKey 分派：
 * <ul>
 *   <li>{@code compose}：SnakeYAML 可解析且顶层含非空 {@code services} 映射；</li>
 *   <li>{@code env}：非空非注释行均匹配 {@code KEY=...}（KEY 为合法标识符）；</li>
 *   <li>{@code nginx}：花括号配平且含 {@code server} 块；</li>
 *   <li>{@code dockerfile}：含 {@code FROM} 指令，且 COPY/ADD 源路径不得引用 {@code extract/} 前缀
 *       （Dockerfile 位于构建上下文 extract 根目录内，该前缀在构建时必然不存在）；</li>
 *   <li>其他 fileKey：不校验（返回 null）。</li>
 * </ul>
 *
 * <p>dockerfile 额外支持包文件树上下文（{@link #validate(String, String, List)}）：
 * CMD/ENTRYPOINT 引用的入口文件必须实际存在于包内文件清单（目录带 {@code /} 后缀），
 * 防止 AI 臆造入口（如包内无 app.py 却写 {@code CMD ["python","app.py"]}）；
 * 清单为 null/空时跳过该检查（包信息不可用不阻断）。
 */
public final class ConfigFileValidator {

    /** .env 行格式：KEY（字母/数字/下划线，字母或下划线开头）= 值（可空，允许 export 前缀）。 */
    private static final Pattern ENV_LINE = Pattern.compile("^\\s*(?:export\\s+)?[A-Za-z_][A-Za-z0-9_]*=.*$");

    /** shell 形式 CMD 的 token 切分：引号串或无引号裸词。 */
    private static final Pattern SHELL_TOKEN = Pattern.compile("\"[^\"]*\"|'[^']*'|[^\\s]+");

    private ConfigFileValidator() {
    }

    /** 校验配置内容；通过返回 null，不通过返回告警文案（入库 warning 列，不阻断保存/部署）。 */
    public static String validate(String fileKey, String content) {
        return validate(fileKey, content, null);
    }

    /**
     * 校验配置内容（带包文件树上下文）。{@code packageTree} 为包内文件/目录相对路径清单
     * （目录带 {@code /} 后缀），null/空时行为与 {@link #validate(String, String)} 一致。
     */
    public static String validate(String fileKey, String content, List<String> packageTree) {
        if (content == null || content.isBlank()) {
            return "配置内容为空";
        }
        switch (fileKey == null ? "" : fileKey) {
            case "compose":
                return validateCompose(content);
            case "env":
                return validateEnv(content);
            case "nginx":
                return validateNginx(content);
            case "dockerfile":
                String base = validateDockerfile(content);
                if (base != null) {
                    return base;
                }
                return validateDockerfileEntry(content, packageTree);
            default:
                return null;
        }
    }

    /**
     * 校验 Dockerfile：必须含 FROM 指令；COPY/ADD 源路径不得以 {@code extract/}（或 {@code ./extract}、
     * {@code /extract/}）开头——本架构下 Dockerfile 与源码同处构建上下文 extract 根目录，
     * 该前缀在构建时必然不存在（实测引发 buildkit "/extract": not found）。
     */
    private static String validateDockerfile(String content) {
        String[] lines = content.split("\\R");
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String[] tokens = s.split("\\s+");
            String instr = tokens[0].toUpperCase();
            if (instr.equals("COPY") || instr.equals("ADD")) {
                for (int i = 1; i < tokens.length; i++) {
                    if (refersExtractRoot(tokens[i])) {
                        return "COPY/ADD 源路径不能引用 extract/ 前缀：" + firstLine(line)
                                + "（Dockerfile 位于构建上下文 extract 根目录内，应使用 COPY . ... 等相对当前上下文的路径）";
                    }
                }
            }
        }
        if (!contentHasInstruction(lines, "FROM")) {
            return "Dockerfile 缺少 FROM 指令";
        }
        return null;
    }

    private static boolean contentHasInstruction(String[] lines, String name) {
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            if (name.equals(s.split("\\s+")[0].toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean refersExtractRoot(String token) {
        String t = token.startsWith("./") ? token.substring(2) : token;
        return t.equals("extract") || t.startsWith("extract/") || t.equals("/extract") || t.startsWith("/extract/");
    }

    /**
     * 入口存在性校验：CMD/ENTRYPOINT 中引用的文件路径必须存在于包文件清单。
     *
     * <p>识别策略（best-effort，宁可漏检不可误杀）：
     * <ul>
     *   <li>JSON 形式（{@code CMD ["python", "app.py"]}）：遍历字符串 token；</li>
     *   <li>shell 形式（{@code CMD python app.py}）：按 shell token 切分；</li>
     *   <li>候选文件 = 带 {@code .} 扩展名、不以 {@code -} 开头、非纯数字、非域名形态
     *       （含 {@code :} 或 {@code //}）的 token；</li>
     *   <li>存在性：精确匹配、忽略大小写匹配、basename 匹配、或位于清单内某目录下
     *       （清单目录条目带 {@code /} 后缀）均视为存在；</li>
     *   <li>清单为 null/空时跳过（包信息不可用）。</li>
     * </ul>
     */
    private static String validateDockerfileEntry(String content, List<String> packageTree) {
        if (packageTree == null || packageTree.isEmpty()) {
            return null;
        }
        Set<String> files = new HashSet<>();
        Set<String> dirs = new HashSet<>();
        Set<String> lowerFiles = new HashSet<>();
        for (String e : packageTree) {
            if (e.endsWith("/")) {
                dirs.add(e);
            } else {
                files.add(e);
                lowerFiles.add(e.toLowerCase());
            }
        }
        for (String line : content.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String instr = s.split("\\s+")[0].toUpperCase();
            if (!instr.equals("CMD") && !instr.equals("ENTRYPOINT")) {
                continue;
            }
            List<String> tokens = cmdTokens(s);
            for (String token : tokens) {
                if (!isCandidateFile(token)) {
                    continue;
                }
                if (!existsInPackage(token, files, dirs, lowerFiles)) {
                    return "CMD/ENTRYPOINT 引用的入口文件不存在于部署包内：" + token
                            + "（请依据部署包文件清单修正启动命令）";
                }
            }
        }
        return null;
    }

    /** 提取 CMD/ENTRYPOINT 的参数 token：JSON 数组解析或 shell 切分。 */
    static List<String> cmdTokens(String line) {
        String s = line.trim();
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        String rest = s.substring(i).trim();
        List<String> out = new ArrayList<>();
        if (rest.startsWith("[")) {
            int end = rest.lastIndexOf(']');
            String body = end > 0 ? rest.substring(1, end) : rest.substring(1);
            Matcher m = Pattern.compile("\"([^\"]*)\"|'([^']*)'").matcher(body);
            while (m.find()) {
                out.add(m.group(1) != null ? m.group(1) : m.group(2));
            }
        } else {
            Matcher m = SHELL_TOKEN.matcher(rest);
            while (m.find()) {
                String t = m.group();
                if (t.length() >= 2
                        && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
                    t = t.substring(1, t.length() - 1);
                }
                out.add(t);
            }
        }
        return out;
    }

    /** 候选入口文件判定：带扩展名、非选项、非纯数字、非域名/模块引用（app.main:app）。 */
    private static boolean isCandidateFile(String token) {
        if (token.isEmpty() || token.startsWith("-")) {
            return false;
        }
        if (token.contains(":") || token.contains("//")) {
            return false;
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }
        String base = token.substring(dot + 1);
        if (base.chars().allMatch(Character::isDigit)) {
            return false; // 端口/版本类纯数字段，如 0.0.0.0、3.12
        }
        if (!base.chars().allMatch(c -> Character.isLetterOrDigit(c))) {
            return false;
        }
        return true;
    }

    private static boolean existsInPackage(String token, Set<String> files,
                                           Set<String> dirs, Set<String> lowerFiles) {
        String t = token.startsWith("./") ? token.substring(2) : token;
        if (files.contains(t) || lowerFiles.contains(t.toLowerCase())) {
            return true;
        }
        // basename 匹配（容器内 WORKDIR 后相对路径可能与包根不完全一致）
        String base = t.substring(t.lastIndexOf('/') + 1);
        for (String f : files) {
            if (f.substring(f.lastIndexOf('/') + 1).equals(base)) {
                return true;
            }
        }
        // 包内目录下的同名文件：token 前缀落在某目录条目内（如 token="app.py" 且目录 "app/" 存在时不成立，
        // 但 token="sub/app.py" 且清单有 "sub/app.py" 已在前匹配；此处覆盖目录前缀变体）
        for (String d : dirs) {
            if (t.startsWith(d)) {
                return true;
            }
        }
        return false;
    }

    private static String validateCompose(String content) {
        Object parsed;
        try {
            parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
        } catch (RuntimeException e) {
            return "YAML 解析失败：" + firstLine(e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            return "顶层必须是键值映射（缺少 services 等顶层键）";
        }
        Object services = ((Map<?, ?>) parsed).get("services");
        if (services == null) {
            return "缺少 services 顶层键";
        }
        if (services instanceof Map && ((Map<?, ?>) services).isEmpty()) {
            return "services 为空";
        }
        if (!(services instanceof Map)) {
            return "services 必须是服务名→定义的映射";
        }
        return null;
    }

    private static String validateEnv(String content) {
        String[] lines = content.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!ENV_LINE.matcher(lines[i]).matches()) {
                return "第 " + (i + 1) + " 行不是合法的 KEY=VALUE 格式：" + firstLine(line);
            }
        }
        return null;
    }

    private static String validateNginx(String content) {
        int depth = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth < 0) {
                    return "花括号不配平（多余的 '}'）";
                }
            }
        }
        if (depth > 0) {
            return "花括号不配平（缺少 " + depth + " 个 '}'）";
        }
        if (!content.contains("server")) {
            return "缺少 server 块";
        }
        return null;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        String first = nl >= 0 ? s.substring(0, nl) : s;
        return first.length() > 120 ? first.substring(0, 120) + "…" : first;
    }

    /** 校验 compose 内容中 services 是否为非空映射（供生成服务复用）。 */
    static boolean composeHasServices(String content) {
        return validate("compose", content) == null;
    }
}
