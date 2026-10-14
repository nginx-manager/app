package net.nginxmanager.common;

import net.nginxmanager.apply.ApplyInProgressException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
public class ErrorAdvice {

    private final AppVersion appVersion;

    public ErrorAdvice(AppVersion appVersion) {
        this.appVersion = appVersion;
    }

    @ExceptionHandler(ConcurrentEditException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String concurrentEdit(Model model) {
        // 예외 처리 화면에는 @ModelAttribute가 붙지 않으므로 footer에 쓸 값을 직접 넣는다
        model.addAttribute("appVersion", appVersion);
        model.addAttribute("titleKey", "error.conflict.title");
        model.addAttribute("detailKey", "error.conflict.detail");
        return "error/conflict";
    }

    @ExceptionHandler(ApplyInProgressException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String applyInProgress(Model model) {
        model.addAttribute("appVersion", appVersion);
        model.addAttribute("titleKey", "error.applying.title");
        model.addAttribute("detailKey", "error.applying.detail");
        return "error/conflict";
    }
}
