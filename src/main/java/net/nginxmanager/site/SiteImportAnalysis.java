package net.nginxmanager.site;

import java.util.List;

/**
 * 가져올 설정 파일을 읽은 결과.
 *
 * @param errorKey      사이트로 등록할 수 없으면 그 이유(메시지 키), 등록할 수 있으면 null
 * @param errorArg      errorKey에 넣을 값
 * @param serverCount   찾은 server 블록 수
 * @param domains       server_name에서 읽은 도메인(소문자, 순서 유지). 대표 도메인 후보가 맨 앞이다
 * @param primaryDomain 대표 도메인으로 쓸 수 있는 첫 이름. 없으면 null(직접 적어야 한다)
 * @param listenPort    HTTP 포트(없으면 80)
 * @param form          폼으로 빠짐없이 옮길 수 있으면 그 폼, 아니면 null
 * @param unsupported   폼으로 옮길 수 없는 이유
 * @param changes       폼으로 옮기면 바뀌는 점
 */
public record SiteImportAnalysis(String errorKey, String errorArg, int serverCount, List<String> domains,
                                 String primaryDomain, int listenPort, SiteForm form, List<Note> unsupported,
                                 List<Note> changes) {

    /** 화면에 보일 안내 한 줄(메시지 키와 넣을 값). */
    public record Note(String key, String arg) {
    }

    static SiteImportAnalysis error(String key, String arg) {
        return new SiteImportAnalysis(key, arg, 0, List.of(), null, 80, null, List.of(), List.of());
    }

    public boolean importable() {
        return errorKey == null;
    }

    public boolean formPossible() {
        return form != null;
    }

    /** 대표 도메인을 뺀 나머지 도메인(원문 사이트의 추가 도메인). */
    public List<String> extraDomains(String primary) {
        return domains.stream().filter(d -> !d.equals(primary)).toList();
    }
}
