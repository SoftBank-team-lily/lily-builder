package com.lily.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 모델이 낸 unified diff 가 그 커밋의 허용된 파일에만 적용되는지 본다.
 * 적용되지 않거나 비밀·워크플로를 건드리면 거절한다.
 */
public final class DiffCheck {

    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    private static final int MAX_CHARS = 20_000;
    private static final int MAX_FILES = 3;

    private DiffCheck() {
    }

    public static Result check(String diff, Map<String, String> originals, Set<String> allowed) {
        if (diff == null || diff.isBlank()) {
            return Result.reject("diff가 비어 있다");
        }
        if (diff.length() > MAX_CHARS) {
            return Result.reject("diff가 너무 길다");
        }
        List<FilePatch> files;
        try {
            files = parse(diff);
        } catch (IllegalArgumentException e) {
            return Result.reject(e.getMessage());
        }
        if (files.isEmpty()) {
            return Result.reject("파일 변경이 없다");
        }
        if (files.size() > MAX_FILES) {
            return Result.reject("고친 파일이 너무 많다");
        }
        Map<String, String> patched = new LinkedHashMap<>();
        for (FilePatch file : files) {
            if (forbidden(file.path())) {
                return Result.reject("고칠 수 없는 경로: " + file.path());
            }
            if (!allowed.contains(file.path())) {
                return Result.reject("허용되지 않은 경로: " + file.path());
            }
            String original = originals.get(file.path());
            if (original == null) {
                return Result.reject("커밋에 없는 파일: " + file.path());
            }
            String next = apply(original, file.hunks());
            if (next == null) {
                return Result.reject("diff가 그 커밋에 적용되지 않는다: " + file.path());
            }
            patched.put(file.path(), next);
        }
        return Result.ok(patched);
    }

    static boolean forbidden(String path) {
        String name = path.replace('\\', '/');
        if (name.startsWith("/") || name.contains("..") || name.contains("//")) {
            return true;
        }
        String base = name.substring(name.lastIndexOf('/') + 1);
        if (base.equals(".env") || base.startsWith(".env.") || base.endsWith(".pem") || base.endsWith(".key")) {
            return true;
        }
        return name.equals(".github/workflows") || name.startsWith(".github/workflows/")
                || name.contains("/.github/workflows/");
    }

    private static List<FilePatch> parse(String diff) {
        List<FilePatch> files = new ArrayList<>();
        String path = null;
        List<Hunk> hunks = new ArrayList<>();
        Hunk current = null;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ") || line.startsWith("index ")
                    || line.startsWith("new file") || line.startsWith("deleted file")
                    || line.startsWith("--- ")) {
                continue;
            }
            if (line.startsWith("+++ ")) {
                if (current != null) {
                    hunks.add(current);
                    current = null;
                }
                if (path != null) {
                    files.add(new FilePatch(path, List.copyOf(hunks)));
                    hunks = new ArrayList<>();
                }
                path = clean(line.substring(4).trim());
                continue;
            }
            if (line.startsWith("@@")) {
                if (current != null) {
                    hunks.add(current);
                }
                Matcher matcher = HUNK.matcher(line);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("hunk 머리글을 읽지 못했다");
                }
                current = new Hunk(Integer.parseInt(matcher.group(1)), new ArrayList<>());
                continue;
            }
            if (current == null || line.isEmpty()) {
                continue;
            }
            char tag = line.charAt(0);
            if (tag != ' ' && tag != '+' && tag != '-' && tag != '\\') {
                throw new IllegalArgumentException("diff 줄이 아니다");
            }
            current.lines().add(line);
        }
        if (current != null) {
            hunks.add(current);
        }
        if (path != null) {
            files.add(new FilePatch(path, List.copyOf(hunks)));
        }
        return files;
    }

    private static String clean(String path) {
        if (path.startsWith("b/")) {
            return path.substring(2);
        }
        if (path.startsWith("a/")) {
            return path.substring(2);
        }
        return path;
    }

    /** 적용할 수 없으면 null */
    static String apply(String original, List<Hunk> hunks) {
        boolean trailing = original.endsWith("\n");
        List<String> src = lines(original);
        List<String> out = new ArrayList<>();
        int cursor = 0;
        for (Hunk hunk : hunks) {
            int start = Math.max(0, hunk.oldStart() - 1);
            if (start < cursor) {
                return null;
            }
            while (cursor < start) {
                if (cursor >= src.size()) {
                    return null;
                }
                out.add(src.get(cursor++));
            }
            for (String raw : hunk.lines()) {
                char tag = raw.charAt(0);
                String text = raw.length() == 1 ? "" : raw.substring(1);
                if (tag == ' ' || tag == '-') {
                    if (cursor >= src.size() || !src.get(cursor).equals(text)) {
                        return null;
                    }
                    if (tag == ' ') {
                        out.add(src.get(cursor));
                    }
                    cursor++;
                } else if (tag == '+') {
                    out.add(text);
                }
            }
        }
        while (cursor < src.size()) {
            out.add(src.get(cursor++));
        }
        String joined = String.join("\n", out);
        return trailing ? joined + "\n" : joined;
    }

    private static List<String> lines(String text) {
        String[] split = text.split("\n", -1);
        List<String> lines = new ArrayList<>(List.of(split));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private record FilePatch(String path, List<Hunk> hunks) {
    }

    private record Hunk(int oldStart, List<String> lines) {
    }

    public record Result(boolean ok, String reason, Map<String, String> files) {
        static Result reject(String reason) {
            return new Result(false, reason, Map.of());
        }

        static Result ok(Map<String, String> files) {
            return new Result(true, "", Map.copyOf(files));
        }
    }
}
