package net.nginxmanager.common;

/** 다른 사람이 먼저 저장해 읽어 간 버전이 낡았을 때. 덮어쓰지 않고 알린다. */
public class ConcurrentEditException extends RuntimeException {

    public ConcurrentEditException(String target) {
        super(target + " was changed by someone else");
    }
}
