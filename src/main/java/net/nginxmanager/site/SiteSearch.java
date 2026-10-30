package net.nginxmanager.site;

import java.util.List;
import java.util.Locale;

/**
 * 사이트 목록 검색(027). 담당자로 거른 행을 검색어와 상태로 한 번 더 거른다.
 *
 * @param q      대표·추가 도메인과 대상(백엔드 주소, 문서 루트, 보낼 주소)에서 찾을 말. 대소문자를 가리지 않는다
 * @param status enabled, disabled, pending, maintenance 중 하나. 비우면 전체
 */
public record SiteSearch(String q, String status) {

    public SiteSearch {
        q = q == null || q.isBlank() ? null : q.strip();
        status = status == null || status.isBlank() ? null : status;
    }

    public boolean active() {
        return q != null || status != null;
    }

    public List<SiteQueryRepository.SiteRow> filter(List<SiteQueryRepository.SiteRow> rows) {
        return active() ? rows.stream().filter(this::matches).toList() : rows;
    }

    boolean matches(SiteQueryRepository.SiteRow row) {
        return matchesText(row) && matchesStatus(row);
    }

    private boolean matchesText(SiteQueryRepository.SiteRow row) {
        if (q == null) {
            return true;
        }
        String needle = q.toLowerCase(Locale.ROOT);
        return contains(row.primaryDomain(), needle) || contains(row.extraDomains(), needle)
                || row.targets().stream().anyMatch(target -> contains(target, needle));
    }

    private boolean matchesStatus(SiteQueryRepository.SiteRow row) {
        if (status == null) {
            return true;
        }
        return switch (status) {
            case "enabled" -> row.enabled();
            case "disabled" -> !row.enabled();
            case "pending" -> row.pending() || row.deleting();
            case "maintenance" -> row.maintenance();
            default -> true;
        };
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
