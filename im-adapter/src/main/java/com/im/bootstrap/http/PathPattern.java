package com.im.bootstrap.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 路径模式匹配器，支持 {@code {param}} 风格路径变量。
 *
 * <p>用法：</p>
 * <pre>{@code
 *   PathPattern p = new PathPattern("/api/user/{userId}/profile");
 *   MatchResult r = p.match("/api/user/u123/profile");
 *   r.matches()      // true
 *   r.variables()    // {userId: "u123"}
 * }</pre>
 *
 * <p>不包含 {@code {}} 的 pattern 为静态路径，可使用 {@link #isStatic()} 判断。
 * 静态路径优先用 HashMap 匹配，动态路径遍历匹配。</p>
 */
public final class PathPattern {

    private final String pattern;
    private final List<Segment> segments;
    private final boolean isStatic;
    private final String normalized;

    public PathPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be null or blank");
        }
        this.pattern = pattern;
        this.normalized = normalizePath(pattern);
        this.segments = parse(normalized);
        this.isStatic = segments.stream().noneMatch(Segment::variable);
    }

    public String pattern() {
        return pattern;
    }

    public boolean isStatic() {
        return isStatic;
    }

    /**
     * 匹配路径并提取变量。
     *
     * @param path 请求路径（不含 query string）
     * @return 匹配结果
     */
    public MatchResult match(String path) {
        if (path == null) return MatchResult.NO_MATCH;
        String normalizedPath = normalizePath(path);

        if (isStatic) {
            return normalized.equals(normalizedPath) ? MatchResult.EMPTY_MATCH : MatchResult.NO_MATCH;
        }

        String[] pathSegments = normalizedPath.split("/", -1);
        if (pathSegments.length != segments.size()) {
            return MatchResult.NO_MATCH;
        }

        Map<String, String> variables = null;
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            String pathSeg = pathSegments[i];
            if (seg.variable()) {
                if (variables == null) {
                    variables = new java.util.HashMap<>();
                }
                variables.put(seg.value(), pathSeg);
            } else if (!seg.value().equals(pathSeg)) {
                return MatchResult.NO_MATCH;
            }
        }

        return variables != null ? new MatchResult(true, variables) : MatchResult.EMPTY_MATCH;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PathPattern that)) return false;
        return normalized.equals(that.normalized);
    }

    @Override
    public int hashCode() {
        return normalized.hashCode();
    }

    @Override
    public String toString() {
        return pattern;
    }

    /**
     * 匹配结果。
     *
     * @param matches   是否匹配
     * @param variables 路径变量（匹配成功时非空）
     */
    public record MatchResult(boolean matches, Map<String, String> variables) {
        static final MatchResult NO_MATCH = new MatchResult(false, Collections.emptyMap());
        static final MatchResult EMPTY_MATCH = new MatchResult(true, Collections.emptyMap());
    }

    private record Segment(boolean variable, String value) {}

    private static List<Segment> parse(String normalized) {
        // 跳过开头的空串（leading "/" 导致 split 产生空首元素）
        String[] parts = normalized.split("/", -1);
        List<Segment> result = new ArrayList<>(parts.length - 1);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("{") && part.endsWith("}")) {
                String name = part.substring(1, part.length() - 1);
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("Variable name must not be empty: " + normalized);
                }
                result.add(new Segment(true, name));
            } else {
                result.add(new Segment(false, part));
            }
        }
        return result;
    }

    private static String normalizePath(String path) {
        String p = path;
        if (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
