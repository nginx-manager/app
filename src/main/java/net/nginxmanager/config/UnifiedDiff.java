package net.nginxmanager.config;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;

import java.util.List;

/** 설정 파일 두 내용의 unified diff. 없는 쪽(null)은 /dev/null로 표시한다. */
public final class UnifiedDiff {

    private UnifiedDiff() {
    }

    public static String of(String path, String before, String after) {
        List<String> original = lines(before);
        List<String> revised = lines(after);
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                before == null ? "/dev/null" : "a/" + path,
                after == null ? "/dev/null" : "b/" + path,
                original, DiffUtils.diff(original, revised), 3);
        return String.join("\n", unified);
    }

    private static List<String> lines(String content) {
        return content == null ? List.of() : content.lines().toList();
    }
}
