package net.nginxmanager.site;

/** 설정 파일을 사이트로 가져올 수 없다. 이유는 화면 메시지 키와 넣을 값으로 담는다. */
public class SiteImportException extends RuntimeException {

    private final String key;
    private final String arg;

    public SiteImportException(String key, String arg) {
        super(key + (arg == null ? "" : ": " + arg));
        this.key = key;
        this.arg = arg;
    }

    public String getKey() {
        return key;
    }

    public String getArg() {
        return arg;
    }
}
