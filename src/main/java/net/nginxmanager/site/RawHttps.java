package net.nginxmanager.site;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원문 사이트의 설정에 Let's Encrypt를 붙인다. 원문은 해석하지 않고 맨 바깥 server 블록의 위치만 찾아,
 * 인증 경로(location)와 443 server 블록을 글자 그대로 더한다. 사용자가 적은 나머지는 건드리지 않는다.
 */
public final class RawHttps {

    public static final String ACME_PATH = "/.well-known/acme-challenge/";
    private static final String WEBROOT = "/var/lib/nginx-manager/acme-webroot";
    private static final String ACME_COMMENT = "# 관리도구가 Let's Encrypt 인증에 쓴다";
    private static final Pattern ACME_SNIPPET = Pattern.compile("\\n[ \\t]*" + Pattern.quote(ACME_COMMENT)
            + "\\n[ \\t]*location \\^~ " + Pattern.quote(ACME_PATH) + " \\{[^}]*\\}\\n");
    private static final Pattern LISTEN = Pattern.compile("(?m)^(\\s*)listen\\s+([^;]*);");
    private static final Pattern SERVER_NAME = Pattern.compile("(?<![\\w$])server_name\\s+([^;]+);");
    private static final Pattern PORT_80 = Pattern.compile("^(?:(.*):)?80$");
    private static final Pattern SSL_CERTIFICATE = Pattern.compile("(?<![\\w$])ssl_certificate\\s+([^;\\s]+);");
    private static final Pattern SSL_CERTIFICATE_KEY = Pattern.compile("(?<![\\w$])ssl_certificate_key\\s+([^;\\s]+);");

    /** 맨 바깥 server 블록 하나. start는 "server", end는 닫는 "}" 다음 글자. open은 여는 "{" 다음 글자. */
    record Block(int start, int open, int end, String text) {

        boolean listensSsl() {
            Matcher m = LISTEN.matcher(withoutComments(text));
            while (m.find()) {
                String params = " " + m.group(2).trim() + " ";
                if (params.contains(" ssl ") || params.contains(" quic ")) {
                    return true;
                }
            }
            return false;
        }

        /** 80 포트를 듣는지. listen이 없으면 nginx 기본값인 80으로 본다. */
        boolean listensPort80() {
            Matcher m = LISTEN.matcher(withoutComments(text));
            boolean any = false;
            while (m.find()) {
                any = true;
                if (PORT_80.matcher(m.group(2).trim().split("\\s+")[0]).matches()) {
                    return true;
                }
            }
            return !any;
        }
    }

    private RawHttps() {
    }

    /** 80 포트를 듣는 HTTP(ssl이 아닌) server 블록의 server_name. 와일드카드·정규식·"_"는 뺀다. */
    public static List<String> domains(String content) {
        Set<String> names = new LinkedHashSet<>();
        for (Block block : httpBlocks(content)) {
            Matcher m = SERVER_NAME.matcher(withoutComments(block.text()));
            while (m.find()) {
                for (String name : m.group(1).trim().split("\\s+")) {
                    if (!name.isEmpty() && !name.equals("_") && !name.startsWith("~") && !name.contains("*")
                            && !name.contains("$")) {
                        names.add(name.toLowerCase());
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    /** 인증 경로가 없는 80 포트 HTTP server 블록마다 맨 앞에 인증 경로를 더한다. 인증 요청은 80으로만 온다. 이미 있으면 그대로 돌려준다. */
    public static String withAcmeChallenge(String content) {
        StringBuilder out = new StringBuilder(content);
        List<Block> blocks = httpBlocks(content);
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            if (withoutComments(block.text()).contains(ACME_PATH)) {
                continue;
            }
            out.insert(block.open(), "\n" + indent(block.text()) + ACME_COMMENT + "\n"
                    + indent(block.text()) + "location ^~ " + ACME_PATH + " {\n"
                    + indent(block.text()) + "    root " + WEBROOT + ";\n"
                    + indent(block.text()) + "    default_type text/plain;\n"
                    + indent(block.text()) + "}\n");
        }
        return out.toString();
    }

    /**
     * 원본을 다시 가져올 때, 관리도구가 이전 원문에 더한 인증 경로와 443 server 블록을 새 원문에도 다시 더한다.
     * 이전 원문에 넘기기 블록(return 301 https://)이 있었으면 새 원문도 넘기게 한다. 새 원문에 이미 있으면 그대로 둔다.
     */
    public static String reapply(String previous, String content) {
        String out = content;
        if (ACME_SNIPPET.matcher(previous).find()) {
            out = withAcmeChallenge(out);
        }
        if (hasHttps(previous) && !hasHttps(out)) {
            String plain = withoutComments(previous);
            Matcher cert = SSL_CERTIFICATE.matcher(plain);
            Matcher key = SSL_CERTIFICATE_KEY.matcher(plain);
            if (cert.find() && key.find()) {
                out = withHttps(out, cert.group(1), key.group(1), plain.contains("return 301 https://"));
            }
        }
        return out;
    }

    /** 이미 HTTPS server 블록이 있는지. */
    public static boolean hasHttps(String content) {
        return serverBlocks(content).stream().anyMatch(Block::listensSsl);
    }

    /**
     * 80 포트를 듣는 첫 HTTP server 블록을 본떠 443 server 블록을 그 뒤에 더한다. listen만 443 ssl로 바꾸고 인증서 경로를 넣는다.
     * HTTPS server 블록이 이미 있거나 HTTP server 블록이 없으면 그대로 돌려준다.
     */
    public static String withHttps(String content, String certPath, String keyPath) {
        return withHttps(content, certPath, keyPath, false);
    }

    /**
     * {@link #withHttps(String, String, String)}에 더해, redirect면 본뜬 HTTP server 블록을 HTTPS로 넘기는 블록으로 바꾼다.
     * 넘기는 블록에는 listen과 server_name, 인증 경로만 남긴다(갱신할 때 인증 요청은 넘기지 않는다).
     * 원래 내용은 443 블록으로 옮겨졌고, 이전 버전으로 되돌릴 수 있다.
     */
    public static String withHttps(String content, String certPath, String keyPath, boolean redirect) {
        if (hasHttps(content)) {
            return content;
        }
        List<Block> blocks = httpBlocks(content);
        if (blocks.isEmpty()) {
            return content;
        }
        Block http = blocks.getFirst();
        String body = http.text();
        Matcher m = LISTEN.matcher(body);
        StringBuilder copy = new StringBuilder();
        boolean replaced = false;
        while (m.find()) {
            String address = m.group(2).trim().split("\\s+")[0];
            Matcher port = PORT_80.matcher(address);
            String host = port.matches() ? port.group(1) : null;
            String listen = host == null ? "443" : host + ":443";
            m.appendReplacement(copy, Matcher.quoteReplacement(m.group(1) + "listen " + listen + " ssl;"));
            replaced = true;
        }
        m.appendTail(copy);
        // 인증 요청은 80으로만 오므로 443 블록에는 관리도구가 더한 인증 경로를 옮기지 않는다
        String https = ACME_SNIPPET.matcher(copy.toString()).replaceAll("");
        if (!replaced) {
            https = insertAfterOpen(https, "listen 443 ssl;");
        }
        String ind = indent(body);
        https = insertAfterOpen(https, "http2 on;\n" + ind + "ssl_certificate " + certPath + ";\n"
                + ind + "ssl_certificate_key " + keyPath + ";");
        String first = redirect ? redirectBlock(body) : body;
        return content.substring(0, http.start()) + first + "\n\n" + https + content.substring(http.end());
    }

    /** HTTP server 블록의 listen·server_name만 남기고, 인증 경로 밖의 요청은 HTTPS로 넘긴다. */
    private static String redirectBlock(String body) {
        String ind = indent(body);
        StringBuilder out = new StringBuilder("server {\n");
        Matcher listen = LISTEN.matcher(withoutComments(body));
        while (listen.find()) {
            out.append(ind).append("listen ").append(listen.group(2).trim()).append(";\n");
        }
        Matcher name = SERVER_NAME.matcher(withoutComments(body));
        while (name.find()) {
            out.append(ind).append("server_name ").append(name.group(1).trim()).append(";\n");
        }
        out.append("\n").append(ind).append(ACME_COMMENT + "\n")
                .append(ind).append("location ^~ ").append(ACME_PATH).append(" {\n")
                .append(ind).append("    root ").append(WEBROOT).append(";\n")
                .append(ind).append("    default_type text/plain;\n")
                .append(ind).append("}\n\n")
                .append(ind).append("location / {\n")
                .append(ind).append("    return 301 https://$host$request_uri;\n")
                .append(ind).append("}\n}");
        return out.toString();
    }

    private static String insertAfterOpen(String block, String lines) {
        int open = block.indexOf('{') + 1;
        return block.substring(0, open) + "\n" + indent(block) + lines + block.substring(open);
    }

    /** 80 포트를 듣는 HTTP server 블록. 다른 포트(예: 9000)를 듣는 블록은 인증서 발급과 상관없다. */
    private static List<Block> httpBlocks(String content) {
        return serverBlocks(content).stream().filter(b -> !b.listensSsl() && b.listensPort80()).toList();
    }

    /** 맨 바깥(또는 http { } 바로 안)의 server 블록. 주석과 따옴표 안의 괄호는 세지 않는다. */
    static List<Block> serverBlocks(String content) {
        List<Block> blocks = new ArrayList<>();
        int depth = 0;
        int httpDepth = -1;
        int start = -1;
        int open = -1;
        int serverDepth = -1;
        StringBuilder word = new StringBuilder();
        String lastWord = null;
        int lastWordStart = -1;
        int wordStart = -1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '#') {
                int nl = content.indexOf('\n', i);
                i = nl < 0 ? content.length() : nl;
                continue;
            }
            if (c == '"' || c == '\'') {
                int close = i + 1;
                while (close < content.length() && content.charAt(close) != c) {
                    if (content.charAt(close) == '\\') {
                        close++;
                    }
                    close++;
                }
                i = close;
                lastWord = null;
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_') {
                if (word.isEmpty()) {
                    wordStart = i;
                }
                word.append(c);
                continue;
            }
            if (!word.isEmpty()) {
                lastWord = word.toString();
                lastWordStart = wordStart;
                word.setLength(0);
            }
            if (c == '{') {
                boolean top = depth == 0 || depth == httpDepth + 1 && httpDepth >= 0;
                if ("server".equals(lastWord) && start < 0 && top) {
                    start = lastWordStart;
                    open = i + 1;
                    serverDepth = depth;
                } else if ("http".equals(lastWord) && depth == 0) {
                    httpDepth = 0;
                }
                depth++;
                lastWord = null;
            } else if (c == '}') {
                depth--;
                if (start >= 0 && depth == serverDepth) {
                    blocks.add(new Block(start, open, i + 1, content.substring(start, i + 1)));
                    start = -1;
                }
                lastWord = null;
            } else if (c == ';') {
                lastWord = null;
            } else if (!Character.isWhitespace(c)) {
                lastWord = null;
            }
        }
        return blocks;
    }

    static String indent(String block) {
        Matcher m = Pattern.compile("\\{\\s*\\n([ \\t]+)\\S").matcher(block);
        return m.find() ? m.group(1) : "    ";
    }

    private static String withoutComments(String text) {
        return text.replaceAll("#[^\\n]*", "");
    }
}
