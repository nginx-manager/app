package net.nginxmanager.site;

import java.util.ArrayList;
import java.util.List;

/**
 * nginx 설정 문법(지시어, 블록, 따옴표, 주석)만 읽는 작은 파서. 지시어의 뜻은 해석하지 않고 include도 따라가지 않는다.
 * 가져오기에서 server 블록을 사이트 폼으로 옮길 수 있는지 볼 때 쓴다.
 */
final class NginxConf {

    /**
     * @param block 블록 지시어(server, location 등)면 안의 지시어, 단순 지시어면 null
     */
    record Directive(String name, List<String> args, List<Directive> block) {

        boolean isBlock() {
            return block != null;
        }

        String arg(int index) {
            return index < args.size() ? args.get(index) : null;
        }
    }

    private final String text;
    private int pos;
    /** 마지막 토큰이 따옴표 문자열이었는지. 따옴표 안의 ; { } 는 구분자가 아니다. */
    private boolean quoted;

    private NginxConf(String text) {
        this.text = text;
    }

    /** @throws IllegalArgumentException 문법이 맞지 않으면(줄 번호를 담는다) */
    static List<Directive> parse(String content) {
        NginxConf parser = new NginxConf(content);
        return parser.directives(false);
    }

    private List<Directive> directives(boolean nested) {
        List<Directive> result = new ArrayList<>();
        List<String> words = new ArrayList<>();
        while (true) {
            String token = next();
            if (token == null) {
                if (nested || !words.isEmpty()) {
                    throw error("unexpected end of file");
                }
                return result;
            }
            switch (quoted ? "" : token) {
                case ";" -> {
                    if (words.isEmpty()) {
                        throw error("unexpected \";\"");
                    }
                    result.add(new Directive(words.getFirst(), List.copyOf(words.subList(1, words.size())), null));
                    words.clear();
                }
                case "{" -> {
                    if (words.isEmpty()) {
                        throw error("unexpected \"{\"");
                    }
                    List<Directive> inner = directives(true);
                    result.add(new Directive(words.getFirst(), List.copyOf(words.subList(1, words.size())), inner));
                    words.clear();
                }
                case "}" -> {
                    if (!nested || !words.isEmpty()) {
                        throw error("unexpected \"}\"");
                    }
                    return result;
                }
                default -> words.add(token);
            }
        }
    }

    /** 다음 토큰. 따옴표 문자열은 따옴표를 벗긴 값. 끝이면 null. */
    private String next() {
        quoted = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '#') {
                while (pos < text.length() && text.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                break;
            }
        }
        if (pos >= text.length()) {
            return null;
        }
        char c = text.charAt(pos);
        if (c == ';' || c == '{' || c == '}') {
            pos++;
            return String.valueOf(c);
        }
        if (c == '"' || c == '\'') {
            quoted = true;
            return quoted(c);
        }
        StringBuilder word = new StringBuilder();
        while (pos < text.length()) {
            char ch = text.charAt(pos);
            if (Character.isWhitespace(ch) || ch == ';' || ch == '}') {
                break;
            }
            if (ch == '{') {
                // ${name} 변수는 단어의 일부다
                if (word.isEmpty() || word.charAt(word.length() - 1) != '$') {
                    break;
                }
                int close = text.indexOf('}', pos);
                if (close < 0) {
                    throw error("unterminated variable");
                }
                word.append(text, pos, close + 1);
                pos = close + 1;
                continue;
            }
            if (ch == '\\' && pos + 1 < text.length()) {
                word.append(ch).append(text.charAt(pos + 1));
                pos += 2;
                continue;
            }
            word.append(ch);
            pos++;
        }
        return word.toString();
    }

    private String quoted(char quote) {
        int start = pos;
        pos++;
        StringBuilder value = new StringBuilder();
        while (pos < text.length()) {
            char ch = text.charAt(pos);
            if (ch == '\\' && pos + 1 < text.length()) {
                char escaped = text.charAt(pos + 1);
                value.append(escaped == quote || escaped == '\\' ? "" : "\\").append(escaped);
                pos += 2;
                continue;
            }
            if (ch == quote) {
                pos++;
                return value.toString();
            }
            value.append(ch);
            pos++;
        }
        pos = start;
        throw error("unterminated string");
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException(message + " (line " + line + ")");
    }
}
