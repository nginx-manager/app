package net.nginxmanager.common;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

/** 컨트롤러가 화면에 넘기는 안내·오류 문구를 지금 사용자의 언어로 만든다(messages*.properties). */
@Component
public class Messages {

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String get(String code, Object... args) {
        return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    /**
     * 미리보기 자리에 보일 입력 오류. 값을 바꾸지 못한 오류(숫자 칸이 비었을 때 등)는 기본 문구가 영어라 빼고,
     * 같은 문구는 한 번만 넣는다.
     */
    public String previewErrors(BindingResult bindingResult) {
        StringBuilder out = new StringBuilder(get("sites.form.preview.invalid"));
        bindingResult.getAllErrors().stream()
                .filter(e -> !(e instanceof FieldError field && field.isBindingFailure()))
                .map(e -> messageSource.getMessage(e, LocaleContextHolder.getLocale()))
                .distinct()
                .forEach(message -> out.append("\n· ").append(message));
        return out.toString();
    }
}
