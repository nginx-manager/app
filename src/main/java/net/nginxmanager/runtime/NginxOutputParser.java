package net.nginxmanager.runtime;

import net.nginxmanager.runtime.ValidationResult.ValidationMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code nginx -t}, {@code nginx -V} 출력 해석. */
public final class NginxOutputParser {

    private static final Pattern MESSAGE =
            Pattern.compile("^nginx: \\[(emerg|alert|crit|error|warn|notice)] (.+?)(?: in (/\\S+):(\\d+))?$");
    private static final Pattern VERSION = Pattern.compile("nginx version: nginx/(\\S+)");
    private static final Pattern WITH_FLAG = Pattern.compile("--with-([A-Za-z0-9_-]+)(=dynamic)?(?=\\s|$)");
    private static final Pattern DYNAMIC_ADDON = Pattern.compile("--add-dynamic-module=(\\S+)");
    private static final Pattern STATIC_ADDON = Pattern.compile("--add-module=(\\S+)");

    private NginxOutputParser() {
    }

    public static List<ValidationMessage> parseTestOutput(String output) {
        List<ValidationMessage> messages = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher m = MESSAGE.matcher(line.strip());
            if (m.matches()) {
                Integer lineNo = m.group(4) == null ? null : Integer.valueOf(m.group(4));
                messages.add(new ValidationMessage(m.group(1), m.group(2), m.group(3), lineNo));
            }
        }
        return messages;
    }

    public static String parseVersion(String output) {
        Matcher m = VERSION.matcher(output);
        return m.find() ? m.group(1) : null;
    }

    /**
     * configure 인자에서 모듈을 읽는다. {@code --with-cc-opt=...}처럼 값을 받는 빌드 옵션은 제외하고,
     * {@code --add-dynamic-module=/path/njs/nginx} 같은 외부 모듈은 경로의 의미 있는 이름으로 표시한다.
     */
    public static List<NginxModule> parseModules(String output) {
        int idx = output.indexOf("configure arguments:");
        if (idx < 0) {
            return List.of();
        }
        String args = output.substring(idx + "configure arguments:".length()).lines().findFirst().orElse("");
        List<NginxModule> modules = new ArrayList<>();
        Matcher with = WITH_FLAG.matcher(args);
        while (with.find()) {
            modules.add(new NginxModule(with.group(1), with.group(2) != null));
        }
        Matcher dynamic = DYNAMIC_ADDON.matcher(args);
        while (dynamic.find()) {
            modules.add(new NginxModule(addonName(dynamic.group(1)), true));
        }
        Matcher stat = STATIC_ADDON.matcher(args);
        while (stat.find()) {
            modules.add(new NginxModule(addonName(stat.group(1)), false));
        }
        return modules;
    }

    private static String addonName(String path) {
        String[] parts = path.replaceAll("/+$", "").split("/");
        String last = parts[parts.length - 1];
        // njs처럼 <소스>/nginx 디렉터리를 가리키는 경우 상위 디렉터리 이름을 쓴다
        if (last.equals("nginx") && parts.length > 1) {
            return parts[parts.length - 2];
        }
        return last;
    }
}
