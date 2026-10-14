package net.nginxmanager.apply;

/**
 * 파일 하나를 바꾸는 내용.
 *
 * @param content 새 내용. null이면 파일을 지운다
 */
public record FileChange(String path, String content) {
}
