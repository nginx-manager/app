package net.nginxmanager.audit;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/**
 * 감사 로그를 CSV로 쓴다(030). 엑셀이 한글을 깨뜨리지 않게 UTF-8 BOM을 붙이고, 대상·내용에 사용자 입력이 들어가므로
 * 스프레드시트가 수식으로 읽을 값은 앞에 작은따옴표를 붙인다.
 */
final class AuditCsv {

    static final int MAX_ROWS = 10_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AuditCsv() {
    }

    /**
     * @param header 칸 이름 7개(시각, 사용자, 역할, 동작, 대상, 결과, 내용)
     * @param label  동작·결과 열거값을 화면 문구로 바꾼다
     */
    static String write(List<String> header, List<AuditLogQueryRepository.AuditLogRow> rows, ZoneId zone,
                        Function<Enum<?>, String> label) {
        StringBuilder out = new StringBuilder("﻿");
        line(out, header);
        for (AuditLogQueryRepository.AuditLogRow row : rows) {
            line(out, List.of(TIME.format(row.createdAt().atZone(zone)), text(row.username()), text(row.userRole()),
                    label.apply(row.action()), text(row.target()), label.apply(row.result()), text(row.detail())));
        }
        return out.toString();
    }

    private static void line(StringBuilder out, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(values.get(i)));
        }
        out.append("\r\n");
    }

    static String cell(String value) {
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")
                || !safe.equals(safe.strip())) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
